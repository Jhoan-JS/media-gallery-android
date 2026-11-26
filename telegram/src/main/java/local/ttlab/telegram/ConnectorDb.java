package local.ttlab.telegram;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.List;

final class ConnectorDb {
    static final String[] MEDIA_COLUMNS={"id","channel","name","mime","size","date","width","height","duration","revision","content_key"};
    private final SQLiteDatabase db;
    ConnectorDb(Context context,SecureStore store)throws Exception {this(context,store,"connector.db");}
    ConnectorDb(Context context,SecureStore store,String name)throws Exception {
        System.loadLibrary("sqlcipher");
        if(!name.matches("[a-z-]+\\.db"))throw new IllegalArgumentException();
        File path=new File(context.getNoBackupFilesDir(),name);byte[] key=store.databaseKey();
        try {db=SQLiteDatabase.openOrCreateDatabase(path,key.clone(),null,null);}finally{Arrays.fill(key,(byte)0);}
        db.execSQL("PRAGMA temp_store=MEMORY");
        try(Cursor secure=db.rawQuery("PRAGMA secure_delete=ON",(String[])null)){if(!secure.moveToFirst()||secure.getInt(0)!=1)throw new IllegalStateException("E_TELEGRAM_STORAGE");}
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS peers(local_id INTEGER PRIMARY KEY AUTOINCREMENT,account TEXT NOT NULL,kind TEXT NOT NULL,remote_id TEXT NOT NULL,access_hash TEXT NOT NULL,title TEXT NOT NULL,selected INTEGER NOT NULL DEFAULT 0,UNIQUE(account,kind,remote_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS consent(package TEXT PRIMARY KEY,uid INTEGER NOT NULL,signer TEXT NOT NULL,account TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS media(account TEXT NOT NULL,channel INTEGER NOT NULL,id INTEGER NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,date INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,duration INTEGER NOT NULL,revision TEXT NOT NULL,content_key TEXT NOT NULL,locator TEXT NOT NULL,thumb_locator TEXT,PRIMARY KEY(account,channel,id))");
    }
    synchronized String bound() {try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE name='bound-account'",(String[])null)){return c.moveToFirst()?c.getString(0):null;}}
    synchronized void bind(String account) {
        if(!BridgePolicy.account(account))throw new SecurityException("E_TELEGRAM_IDENTITY");
        String previous=bound();if(previous!=null&&!BridgePolicy.sameAccount(previous,account))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");
        db.execSQL("INSERT OR IGNORE INTO meta(name,value) VALUES('bound-account',?)",new Object[]{account});
    }
    synchronized boolean consent(CallerGate.Caller caller,String account) {
        try(Cursor c=db.rawQuery("SELECT uid,signer,account FROM consent WHERE package=?",new String[]{caller.packageName})) {
            return c.moveToFirst()&&c.getInt(0)==caller.uid&&caller.signer.equals(c.getString(1))&&BridgePolicy.sameAccount(account,c.getString(2));
        }
    }
    synchronized void grant(CallerGate.Caller caller,String account) {
        if(!BridgePolicy.sameAccount(bound(),account))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");
        try(Cursor c=channels(account)){if(c.getCount()==0)throw new SecurityException("E_TELEGRAM_SELECTION_REQUIRED");}
        db.execSQL("INSERT OR REPLACE INTO consent(package,uid,signer,account) VALUES(?,?,?,?)",new Object[]{caller.packageName,caller.uid,caller.signer,account});
    }
    synchronized void revoke(){db.execSQL("DELETE FROM consent");}
    synchronized String selectionToken(String account)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(Cursor c=db.rawQuery("SELECT local_id,kind,remote_id,title FROM peers WHERE account=? AND selected=1 ORDER BY local_id",new String[]{account})) {
            while(c.moveToNext())for(int column=0;column<4;column++) {byte[] value=c.getString(column).getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);}
        }
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    synchronized void select(String account,List<Integer> selected) {
        if(!BridgePolicy.sameAccount(bound(),account))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");
        db.beginTransaction();try {
            for(int local:selected)peer(account,local,false);
            db.execSQL("UPDATE peers SET selected=0 WHERE account=?",new Object[]{account});
            for(int local:selected)db.execSQL("UPDATE peers SET selected=1 WHERE account=? AND local_id=?",new Object[]{account,local});
            revoke();db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized JSONArray remember(String account,JSONArray chats)throws Exception {
        if(chats.length()>101)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
        JSONArray visible=new JSONArray();
        db.beginTransaction();try {
            for(int i=0;i<chats.length();i++) {
                JSONObject chat=chats.getJSONObject(i),peer=chat.getJSONObject("peer");
                String kind=peer.getString("kind"),id=peer.getString("id"),hash=peer.optString("hash","0"),title=chat.getString("title");
                if(!kind.matches("user|group|channel")||!BridgePolicy.decimal(id,false)||title.length()>1024)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
                hash=BridgePolicy.accessHash(kind,hash);
                db.execSQL("INSERT INTO peers(account,kind,remote_id,access_hash,title) VALUES(?,?,?,?,?) ON CONFLICT(account,kind,remote_id) DO UPDATE SET access_hash=excluded.access_hash,title=excluded.title",new Object[]{account,kind,id,hash,title});
                try(Cursor c=db.rawQuery("SELECT local_id,selected FROM peers WHERE account=? AND kind=? AND remote_id=?",new String[]{account,kind,id})) {
                    if(!c.moveToFirst()||c.getLong(0)>Integer.MAX_VALUE)throw new IllegalStateException("E_TELEGRAM_PEER_LIMIT");
                    visible.put(new JSONObject().put("id",c.getInt(0)).put("title",title).put("kind",kind)
                            .put("selected",c.getInt(1)!=0).put("supergroup",chat.optBoolean("supergroup"))
                            .put("unavailable",chat.optBoolean("unavailable")));
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return visible;
    }
    synchronized MatrixCursor channels(String account) {
        MatrixCursor result=new MatrixCursor(new String[]{"id","title","peer_key"});
        try(Cursor c=db.rawQuery("SELECT local_id,title,kind,remote_id FROM peers WHERE account=? AND selected=1 ORDER BY title,local_id",new String[]{account})) {
            while(c.moveToNext())result.addRow(new Object[]{c.getInt(0),c.getString(1),c.getString(2)+":"+c.getString(3)});
        }return result;
    }
    synchronized void requirePeer(String account,int local,String key){
        JSONObject peer=peer(account,local,true);String expected=peer.optString("kind")+":"+peer.optString("id");
        if(!expected.equals(key))throw new SecurityException("E_TELEGRAM_PEER_MISMATCH");
    }
    synchronized JSONObject peer(String account,int local,boolean selected) {
        if(local<=0)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
        try(Cursor c=db.rawQuery("SELECT kind,remote_id,access_hash,title,selected FROM peers WHERE account=? AND local_id=?",new String[]{account,""+local})) {
            if(!c.moveToFirst()||(selected&&c.getInt(4)!=1))throw new SecurityException("E_TELEGRAM_CHAT_NOT_SELECTED");
            try{return new JSONObject().put("kind",c.getString(0)).put("id",c.getString(1)).put("hash",c.getString(2));}catch(Exception impossible){throw new IllegalStateException("E_TELEGRAM_STORAGE");}
        }
    }
    synchronized MatrixCursor page(String account,int channel,JSONObject page)throws Exception {
        peer(account,channel,true);JSONArray items=page.getJSONArray("items");
        int next=page.getInt("next"),newest=page.getInt("newest");
        if(items.length()>50||next==0||next< -1||newest<0)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
        MatrixCursor result=new MatrixCursor(MEDIA_COLUMNS);db.beginTransaction();
        try {
            for(int i=0;i<items.length();i++)result.addRow(storeMedia(account,channel,items.getJSONObject(i)));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        Bundle extras=new Bundle();extras.putInt("next",next);extras.putInt("newest",newest);result.setExtras(extras);return result;
    }
    private Object[] storeMedia(String account,int channel,JSONObject item)throws Exception {
        int id=item.getInt("id"),width=item.optInt("width"),height=item.optInt("height"),duration=item.optInt("duration");
        long size=item.getLong("size"),date=item.getLong("date");
        String name=item.getString("name"),mime=item.getString("mime"),revision=item.getString("revision"),contentKey=item.optString("content_key","");
        String locator=item.getJSONObject("locator").toString();Object thumb=item.opt("thumb_locator");String thumbJson=thumb instanceof JSONObject?thumb.toString():null;
        if(id<=0||size<=0||date<0||width<0||height<0||duration<0||name.length()>1024||mime.length()>128||revision.isEmpty()||revision.length()>256||contentKey.length()>256||locator.length()>32768||(thumbJson!=null&&thumbJson.length()>32768))throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
        db.execSQL("INSERT OR REPLACE INTO media(account,channel,id,name,mime,size,date,width,height,duration,revision,content_key,locator,thumb_locator) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new Object[]{account,channel,id,name,mime,size,date,width,height,duration,revision,contentKey,locator,thumbJson});
        return new Object[]{id,channel,name,mime,size,date,width,height,duration,revision,contentKey};
    }
    synchronized void restoreMedia(String account,int channel,int id,String revision,JSONObject item)throws Exception {
        peer(account,channel,true);
        if(item.getInt("id")!=id||!revision.equals(item.getString("revision")))throw new IllegalStateException("E_MEDIA_CHANGED");
        storeMedia(account,channel,item);
    }
    synchronized Media media(String account,int channel,int id,String revision,boolean thumbnail)throws Exception {
        peer(account,channel,true);if(id<=0||revision==null)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
        try(Cursor c=db.rawQuery("SELECT revision,size,locator,thumb_locator FROM media WHERE account=? AND channel=? AND id=?",new String[]{account,""+channel,""+id})) {
            if(!c.moveToFirst())return null;
            if(!revision.equals(c.getString(0)))throw new IllegalStateException("E_MEDIA_CHANGED");
            String locator=c.getString(thumbnail?3:2);if(locator==null)throw new IllegalStateException("E_TELEGRAM_NO_THUMBNAIL");
            return new Media(c.getLong(1),new JSONObject(locator));
        }
    }
    synchronized void knownMessage(String account,int channel,int id) {
        peer(account,channel,true);try(Cursor c=db.rawQuery("SELECT 1 FROM media WHERE account=? AND channel=? AND id=?",new String[]{account,""+channel,""+id})) {
            if(id<=0||!c.moveToFirst())throw new SecurityException("E_TELEGRAM_MESSAGE_UNKNOWN");
        }
    }
    synchronized void close(){db.close();}
    static final class Media {final long size;final JSONObject locator;Media(long size,JSONObject locator){this.size=size;this.locator=locator;}}
}
