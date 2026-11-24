package local.ttlab.gallery;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class Library {
    static final String COLUMNS="id,channel,name,mime,size,date,width,height,duration,revision,content_key";
    static final int MAX_VIDEO_COVERS=1000;
    final SQLiteDatabase db;
    final PersonalLibrary personal;
    final ChatFolders chatFolders;
    Library(Context c,String name)throws Exception{this(c.getDatabasePath(name),new Vault(c).databaseKey());}
    Library(File path,byte[] key)throws Exception{
        System.loadLibrary("sqlcipher");
        if(path==null){
            db=SQLiteDatabase.openOrCreateDatabase(":memory:",key.clone(),null,null);
        }else{
            path.getParentFile().mkdirs();
            // SQLCipher keeps the key for future WAL connections; callers may wipe their input.
            db=SQLiteDatabase.openOrCreateDatabase(path,key.clone(),null,null);
            db.enableWriteAheadLogging();
        }
        db.execSQL("PRAGMA temp_store=MEMORY");
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_collections(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,filter TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS saved_collections_name ON saved_collections(name COLLATE NOCASE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS storage_owners(hash TEXT PRIMARY KEY,channel INTEGER NOT NULL,kind INTEGER NOT NULL CHECK(kind IN(0,1)))");
        column("storage_owners","bytes","INTEGER NOT NULL DEFAULT 0");column("storage_owners","saved","INTEGER NOT NULL DEFAULT 0");column("storage_owners","seen","INTEGER NOT NULL DEFAULT 0");column("storage_owners","updated","INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS storage_seen ON storage_owners(seen,channel)");
        db.execSQL("CREATE TABLE IF NOT EXISTS local_settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS channels(id INTEGER PRIMARY KEY,title TEXT NOT NULL,cursor INTEGER NOT NULL DEFAULT 0,complete INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS media(id INTEGER NOT NULL,channel INTEGER NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,date INTEGER NOT NULL,width INTEGER,height INTEGER,duration INTEGER,revision TEXT NOT NULL DEFAULT '',offline INTEGER NOT NULL DEFAULT 0,UNIQUE(channel,id))");
        column("media","revision","TEXT NOT NULL DEFAULT ''");column("media","content_key","TEXT NOT NULL DEFAULT ''");
        column("channels","remote_key","TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS channels_remote_key ON channels(remote_key) WHERE remote_key<>''");
        column("channels","newest","INTEGER NOT NULL DEFAULT 0");column("channels","scan_cursor","INTEGER NOT NULL DEFAULT 0");
        column("channels","scan_floor","INTEGER NOT NULL DEFAULT 0");column("channels","scan_top","INTEGER NOT NULL DEFAULT 0");column("channels","scanning","INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_order ON media(channel,date DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_all_order ON media(date DESC,id DESC,channel)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_offline ON media(offline,channel,date DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_size_order ON media(size DESC,date DESC,id DESC,channel)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_duration_order ON media(duration DESC,date DESC,id DESC,channel)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_content ON media(content_key,channel,id) WHERE content_key<>''");
        db.execSQL("CREATE TABLE IF NOT EXISTS downloads(channel INTEGER NOT NULL,id INTEGER NOT NULL,added INTEGER NOT NULL,UNIQUE(channel,id))");
        column("downloads","progress","INTEGER NOT NULL DEFAULT 0");column("downloads","status","TEXT NOT NULL DEFAULT 'queued'");
        column("downloads","error","TEXT NOT NULL DEFAULT ''");column("downloads","revision","TEXT NOT NULL DEFAULT ''");
        column("downloads","priority","INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS downloads_priority ON downloads(priority DESC,added,channel,id)");
        db.execSQL("UPDATE downloads SET revision=COALESCE((SELECT revision FROM media WHERE media.channel=downloads.channel AND media.id=downloads.id),'') WHERE revision=''");
        db.execSQL("CREATE TABLE IF NOT EXISTS viewed_media(channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,viewed_at INTEGER NOT NULL,PRIMARY KEY(channel,id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS media_state(channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN(0,1)),later INTEGER NOT NULL DEFAULT 0 CHECK(later IN(0,1)),position_ms INTEGER NOT NULL DEFAULT 0,duration_ms INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)),updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(channel,id))");
        column("media_state","archived","INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1))");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_state_archived ON media_state(archived,channel,id)");
        column("channels","sync_state","INTEGER NOT NULL DEFAULT 0");column("channels","sync_updated","INTEGER NOT NULL DEFAULT 0");column("channels","sync_boundary","INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_state_favorite ON media_state(favorite,channel,id)");db.execSQL("CREATE INDEX IF NOT EXISTS media_state_later ON media_state(later,channel,id)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_state_ad AFTER DELETE ON media BEGIN DELETE FROM media_state WHERE channel=old.channel AND id=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_state_au AFTER UPDATE OF revision ON media WHEN old.revision<>new.revision BEGIN DELETE FROM media_state WHERE channel=old.channel AND id=old.id; END");
        db.execSQL("CREATE TABLE IF NOT EXISTS viewports(query_key TEXT PRIMARY KEY,channel INTEGER NOT NULL,id INTEGER NOT NULL,position INTEGER NOT NULL,top INTEGER NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS viewed_media_ad AFTER DELETE ON media BEGIN DELETE FROM viewed_media WHERE channel=old.channel AND id=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS viewed_media_au AFTER UPDATE OF revision ON media WHEN old.revision<>new.revision BEGIN DELETE FROM viewed_media WHERE channel=old.channel AND id=old.id; END");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS media_search USING fts5(name,content='media',content_rowid='rowid',tokenize='unicode61 remove_diacritics 2')");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_ai AFTER INSERT ON media BEGIN INSERT INTO media_search(rowid,name) VALUES(new.rowid,new.name); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_ad AFTER DELETE ON media BEGIN INSERT INTO media_search(media_search,rowid,name) VALUES('delete',old.rowid,old.name); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_au AFTER UPDATE OF name ON media BEGIN INSERT INTO media_search(media_search,rowid,name) VALUES('delete',old.rowid,old.name); INSERT INTO media_search(rowid,name) VALUES(new.rowid,new.name); END");
        personal=new PersonalLibrary(this);
        chatFolders=new ChatFolders(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS video_covers(channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,jpeg BLOB NOT NULL CHECK(length(jpeg) BETWEEN 1 AND 131072),PRIMARY KEY(channel,id)) WITHOUT ROWID");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS video_cover_delete AFTER DELETE ON media BEGIN DELETE FROM video_covers WHERE channel=old.channel AND id=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS video_cover_revision AFTER UPDATE OF revision ON media WHEN old.revision<>new.revision BEGIN DELETE FROM video_covers WHERE channel=old.channel AND id=old.id; END");
    }
    byte[] cover(Item item) {
        if (!item.video()) return null;
        try(Cursor row=db.rawQuery("SELECT jpeg FROM video_covers WHERE channel=? AND id=? AND revision=?",new String[]{""+item.channel,""+item.id,item.revision})){
            return row.moveToFirst()?row.getBlob(0):null;
        }
    }
    boolean saveCover(Item item,byte[] bytes) {
        if (!item.video()||bytes==null||bytes.length==0||bytes.length>Source.CHUNK) throw new IllegalArgumentException("Portada inválida");
        net.zetetic.database.sqlcipher.SQLiteStatement statement=db.compileStatement("INSERT INTO video_covers(channel,id,revision,jpeg) SELECT channel,id,revision,? FROM media WHERE channel=? AND id=? AND revision=? AND mime LIKE 'video/%' AND (EXISTS(SELECT 1 FROM video_covers WHERE channel=media.channel AND id=media.id) OR (SELECT count(*) FROM video_covers)<"+MAX_VIDEO_COVERS+") ON CONFLICT(channel,id) DO UPDATE SET revision=excluded.revision,jpeg=excluded.jpeg");
        try {
            statement.bindBlob(1,bytes);statement.bindLong(2,item.channel);statement.bindLong(3,item.id);statement.bindString(4,item.revision);
            return statement.executeUpdateDelete()>0;
        } finally {statement.close();}
    }
    void removeCover(Item item) {
        db.execSQL("DELETE FROM video_covers WHERE channel=? AND id=? AND revision=?",new Object[]{item.channel,item.id,item.revision});
    }
    private void column(String table,String name,String definition){
        try(Cursor columns=db.rawQuery("PRAGMA table_info("+table+")",(String[])null)){while(columns.moveToNext())if(name.equals(columns.getString(1)))return;}
        db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+name+" "+definition);
    }
    String setting(String name,String fallback){try(Cursor c=db.rawQuery("SELECT value FROM local_settings WHERE name=?",new String[]{name})){return c.moveToFirst()?c.getString(0):fallback;}}
    void setSetting(String name,String value){if(name==null||name.length()>64||value==null||value.length()>16384)throw new IllegalArgumentException();db.execSQL("INSERT INTO local_settings(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",new Object[]{name,value});}
    List<SavedCollection> collections(){List<SavedCollection> result=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,name,filter,updated FROM saved_collections ORDER BY name COLLATE NOCASE,id LIMIT 100",(String[])null)){while(c.moveToNext())result.add(new SavedCollection(c.getLong(0),c.getString(1),SavedCollection.decode(c.getString(2)),c.getLong(3)));}return result;}
    long saveCollection(long id,String proposed,MediaFilter filter){String name=SavedCollection.checkedName(proposed),json=SavedCollection.encode(filter);if(id<0||id>Integer.MAX_VALUE)throw new IllegalArgumentException();db.beginTransaction();try{try(Cursor duplicate=db.rawQuery("SELECT 1 FROM saved_collections WHERE name=? COLLATE NOCASE AND id<>?",new String[]{name,""+id})){if(duplicate.moveToFirst())throw new IllegalStateException("Ya existe una colección con ese nombre");}if(id==0){try(Cursor c=db.rawQuery("SELECT count(*) FROM saved_collections",(String[])null)){c.moveToFirst();if(c.getInt(0)>=100)throw new IllegalStateException("Puedes guardar hasta 100 colecciones por fuente");}db.execSQL("INSERT INTO saved_collections(name,filter,updated) VALUES(?,?,?)",new Object[]{name,json,System.currentTimeMillis()});try(Cursor c=db.rawQuery("SELECT last_insert_rowid()",(String[])null)){c.moveToFirst();id=c.getLong(0);if(id>Integer.MAX_VALUE)throw new IllegalStateException("No quedan identificadores de colección");}}else{try(Cursor c=db.rawQuery("SELECT 1 FROM saved_collections WHERE id=?",new String[]{""+id})){if(!c.moveToFirst())throw new IllegalStateException("La colección ya no existe");}db.execSQL("UPDATE saved_collections SET name=?,filter=?,updated=? WHERE id=?",new Object[]{name,json,System.currentTimeMillis(),id});}db.setTransactionSuccessful();return id;}finally{db.endTransaction();}}
    void deleteCollection(long id){if(id<=0)throw new IllegalArgumentException();db.execSQL("DELETE FROM saved_collections WHERE id=?",new Object[]{id});}
    static String checkedPeerKey(String key){
        if(key==null||!key.matches("(?:user|group|channel):[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Identidad remota de canal inválida");
        try{if(Long.parseLong(key.substring(key.indexOf(':')+1))<=0)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IllegalArgumentException("Identidad remota de canal inválida",invalid);}return key;
    }
    int localPeer(String remoteKey,String title,int preferred){
        checkedPeerKey(remoteKey);if(title==null||preferred<=0)throw new IllegalArgumentException("Canal remoto inválido");
        db.beginTransaction();try{
            try(Cursor row=db.rawQuery("SELECT id FROM channels WHERE remote_key=?",new String[]{remoteKey})){if(row.moveToFirst()){int id=row.getInt(0);db.execSQL("UPDATE channels SET title=? WHERE id=?",new Object[]{title,id});db.setTransactionSuccessful();return id;}}
            int id=preferred;try(Cursor occupied=db.rawQuery("SELECT 1 FROM channels WHERE id=?",new String[]{""+id})){if(occupied.moveToFirst())try(Cursor maximum=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM channels",(String[])null)){maximum.moveToFirst();long next=maximum.getLong(0)+1;if(next<1||next>Integer.MAX_VALUE)throw new IllegalStateException("No quedan identidades locales de canal");id=(int)next;}}
            db.execSQL("INSERT INTO channels(id,title,remote_key) VALUES(?,?,?)",new Object[]{id,title,remoteKey});db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    void channels(List<Source.Channel> channels){
        java.util.Set<Integer> ids=new java.util.HashSet<>();java.util.Set<String> peers=new java.util.HashSet<>();
        for(Source.Channel channel:channels){if(channel.title==null||channel.remoteKey==null)throw new IllegalArgumentException("Canal inválido");if(!channel.remoteKey.isEmpty()&&(channel.id<=0||!ids.add(channel.id)||!peers.add(checkedPeerKey(channel.remoteKey))))throw new IllegalArgumentException("Identidad remota de canal duplicada");}
        db.beginTransaction();try{for(Source.Channel channel:channels){
            String existing=null;try(Cursor row=db.rawQuery("SELECT remote_key FROM channels WHERE id=?",new String[]{""+channel.id})){if(row.moveToFirst())existing=row.getString(0);}
            if(existing==null)db.execSQL("INSERT INTO channels(id,title,remote_key) VALUES(?,?,?)",new Object[]{channel.id,channel.title,channel.remoteKey});
            else{if(!channel.remoteKey.isEmpty()&&!channel.remoteKey.equals(existing))throw new SecurityException("La identidad del canal local no coincide");db.execSQL("UPDATE channels SET title=? WHERE id=?",new Object[]{channel.title,channel.id});}
        }db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    List<Source.Channel> channels(){List<Source.Channel> out=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,title,remote_key FROM channels ORDER BY title",(String[])null)){while(c.moveToNext())out.add(new Source.Channel(c.getInt(0),c.getString(1),c.getString(2)));}return out;}
    void rows(List<Item> items){for(Item i:items)db.execSQL("INSERT INTO media(id,channel,name,mime,size,date,width,height,duration,revision,content_key) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(channel,id) DO UPDATE SET offline=CASE WHEN media.revision=excluded.revision THEN media.offline ELSE 0 END,name=excluded.name,mime=excluded.mime,size=excluded.size,date=excluded.date,width=excluded.width,height=excluded.height,duration=excluded.duration,revision=excluded.revision,content_key=excluded.content_key",
        new Object[]{i.id,i.channel,i.name,i.mime,i.size,i.date,i.width,i.height,i.duration,i.revision,i.contentKey});}
    void put(List<Item> items){db.beginTransaction();try{rows(items);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    int cursor(int channel){try(Cursor c=db.rawQuery("SELECT cursor,complete FROM channels WHERE id=?",new String[]{""+channel})){return c.moveToFirst()&&c.getInt(1)==0?c.getInt(0):0;}}
    void checkpoint(int channel,int next){db.execSQL("UPDATE channels SET cursor=?,complete=? WHERE id=?",new Object[]{Math.max(0,next),next<0?1:0,channel});}
    SyncCursor beginSync(int channel){
        db.beginTransaction();try{
            try(Cursor c=db.rawQuery("SELECT cursor,complete,newest,scan_cursor,scan_floor,scan_top,scanning FROM channels WHERE id=?",new String[]{""+channel})){
                if(!c.moveToFirst())throw new IllegalStateException("Canal desconocido");
                syncState(channel,1);
                if(c.getInt(1)==0){SyncCursor s=new SyncCursor(false,c.getInt(0),0,c.getInt(2));db.setTransactionSuccessful();return s;}
                if(c.getInt(6)!=0){SyncCursor s=new SyncCursor(true,c.getInt(3),c.getInt(4),c.getInt(5));db.setTransactionSuccessful();return s;}
                int floor=c.getInt(2);db.execSQL("UPDATE channels SET scanning=1,scan_cursor=0,scan_floor=?,scan_top=0 WHERE id=?",new Object[]{floor,channel});
                db.setTransactionSuccessful();return new SyncCursor(true,0,floor,0);
            }
        }finally{db.endTransaction();}
    }
    boolean commitPage(int channel,SyncCursor scan,Source.Page page){
        if(page.next==0||(scan.cursor>0&&page.next>=scan.cursor))throw new IllegalStateException("Cursor sin avance");
        boolean done=page.next<0||(scan.incremental&&scan.floor>0&&page.next<=scan.floor);
        int top=Math.max(scan.top,page.newest);
        db.beginTransaction();try{
            rows(page.items);
            if(scan.incremental){
                db.execSQL("UPDATE channels SET scan_cursor=?,scan_top=?,scanning=?,newest=CASE WHEN ? THEN MAX(newest,?) ELSE newest END WHERE id=?",
                    new Object[]{done?0:page.next,done?0:top,done?0:1,done?1:0,top,channel});
            }else db.execSQL("UPDATE channels SET cursor=?,complete=?,newest=MAX(newest,?) WHERE id=?",new Object[]{done?0:page.next,done?1:0,top,channel});
            long boundary=0;for(Item item:page.items)if(item.date>0&&(boundary==0||item.date<boundary))boundary=item.date;
            db.execSQL("UPDATE channels SET sync_state=?,sync_updated=?,sync_boundary=CASE WHEN ?>0 THEN ? ELSE sync_boundary END WHERE id=?",new Object[]{done?4:1,System.currentTimeMillis(),boundary,boundary,channel});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        scan.cursor=page.next;scan.top=top;return done;
    }
    static final class SyncCursor {final boolean incremental;final int floor;int cursor,top;SyncCursor(boolean i,int c,int f,int t){incremental=i;cursor=c;floor=f;top=t;}}
    private static final String VIEWED="EXISTS(SELECT 1 FROM viewed_media v WHERE v.channel=media.channel AND v.id=media.id AND v.revision=media.revision)";
    private static String stateValue(String column){return "COALESCE((SELECT "+column+" FROM media_state s WHERE s.channel=media.channel AND s.id=media.id AND s.revision=media.revision),0) AS "+column;}
    private static final String STATE_COLUMNS=","+stateValue("favorite")+","+stateValue("later")+","+stateValue("position_ms")+","+stateValue("duration_ms")+","+stateValue("completed")+","+stateValue("archived");
    private String where(MediaFilter f,List<String> args){
        f.check();String sql=" WHERE 1=1";
        if(f.webQuery!=null){sql+=" AND EXISTS(SELECT 1 FROM web_results w WHERE w.id=media.id AND w.query=?)";args.add(f.webQuery);}
        if(f.archive<2)sql+=" AND "+(f.archive==0?"NOT ":"")+"EXISTS(SELECT 1 FROM media_state s WHERE s.channel=media.channel AND s.id=media.id AND s.revision=media.revision AND s.archived=1)";
        if(f.recent)sql+=" AND EXISTS(SELECT 1 FROM recent_media r WHERE r.channel=media.channel AND r.id=media.id AND r.revision=media.revision)";
        if(f.albumId>0){sql+=" AND EXISTS(SELECT 1 FROM personal_members p JOIN personal_groups g ON g.group_id=p.group_id WHERE g.kind=0 AND p.group_id=? AND p.channel=media.channel AND p.id=media.id AND p.revision=media.revision)";args.add(""+f.albumId);}
        if(f.tagId>0){sql+=" AND EXISTS(SELECT 1 FROM personal_members p JOIN personal_groups g ON g.group_id=p.group_id WHERE g.kind=1 AND p.group_id=? AND p.channel=media.channel AND p.id=media.id AND p.revision=media.revision)";args.add(""+f.tagId);}
        if(f.channel>0){sql+=" AND channel=?";args.add(""+f.channel);}
        if(f.type>0){sql+=" AND mime LIKE ?";args.add(f.type==2?"video/%":"image/%");}
        if(f.availability>0)sql+=" AND offline="+(f.availability==1?"1":"0");
        String dateColumn=f.recent?"CAST(("+MediaFilter.LAST_OPENED+"/1000) AS INTEGER)":"date";
        if(f.from>0){sql+=" AND "+dateColumn+">=?";args.add(""+f.from);}if(f.until>0){sql+=" AND "+dateColumn+"<?";args.add(""+f.until);}
        if(f.minBytes>0){sql+=" AND size>=?";args.add(""+f.minBytes);}if(f.maxBytes>0){sql+=" AND size<=?";args.add(""+f.maxBytes);}
        if(f.minSeconds>0){sql+=" AND duration>=?";args.add(""+f.minSeconds);}if(f.maxSeconds>0){sql+=" AND mime LIKE 'video/%' AND duration<=?";args.add(""+f.maxSeconds);}
        if(f.watched>0)sql+=" AND mime LIKE 'video/%' AND "+(f.watched==1?"NOT ":"")+VIEWED;
        if(f.collection>0)sql+=" AND EXISTS(SELECT 1 FROM media_state s WHERE s.channel=media.channel AND s.id=media.id AND s.revision=media.revision AND "+(f.collection==1?"s.favorite=1":f.collection==2?"s.later=1":"s.position_ms>0 AND s.duration_ms>0 AND s.completed=0 AND media.mime LIKE 'video/%'")+")";
        if(f.shape>0)sql+=" AND width>0 AND height>0 AND width"+(f.shape==1?">":f.shape==2?"<":"=")+"height";
        if(f.format>0){sql+=" AND mime=?";args.add(f.mime());}
        if(!f.stackKey.isEmpty()){sql+=" AND content_key=? AND mime=? AND size=?";args.add(f.stackKey);args.add(f.stackMime);args.add(""+f.stackSize);}
        if(f.duplicates)sql+=" AND content_key<>'' AND content_key IN(SELECT content_key FROM media WHERE content_key<>'' GROUP BY content_key HAVING count(*)>1)";
        if(!f.query.trim().isEmpty()){
            StringBuilder match=new StringBuilder();for(String word:f.query.trim().split("\\s+")){if(match.length()>0)match.append(" AND ");match.append('"').append(word.replace("\"","\"\"")).append("\"*");}
            sql+=" AND rowid IN(SELECT rowid FROM media_search WHERE media_search MATCH ?)";args.add(match.toString());
        }return sql;
    }
    private String from(MediaFilter filter,List<String> args){
        String predicate=where(filter,args);
        if(!filter.stacked)return " FROM media"+predicate;
        // Rank identities only, leaving large catalogue strings out of the window result.
        String partition="content_key,mime,size,CASE WHEN length(content_key)=64 AND content_key NOT GLOB '*[^0-9a-f]*' THEN 0 ELSE rowid END";
        return " FROM media JOIN (SELECT stack_rowid,stack_count FROM (SELECT rowid AS stack_rowid,ROW_NUMBER() OVER(PARTITION BY "+partition+" ORDER BY "+filter.order()+") AS stack_rank,COUNT(*) OVER(PARTITION BY "+partition+") AS stack_count FROM media"+predicate+") WHERE stack_rank=1) stacks ON media.rowid=stacks.stack_rowid WHERE 1=1";
    }
    int count(int channel,String query,int type,boolean offline){return count(channel,query,type,offline,0,0,false);}
    int count(int channel,String query,int type,boolean offline,long from,long until,boolean duplicates){return count(new MediaFilter(channel,query,type,offline,from,until,duplicates));}
    int count(MediaFilter f){List<String> args=new ArrayList<>();String sql=from(f,args);try(Cursor c=db.rawQuery("SELECT count(*)"+sql,args.toArray(new String[0]))){c.moveToFirst();return c.getInt(0);}}
    List<Item> page(int channel,String query,int type,boolean offline,int offset,int limit){return page(channel,query,type,offline,0,0,false,offset,limit);}
    List<Item> page(int channel,String query,int type,boolean offline,long from,long until,boolean duplicates,int offset,int limit){return page(new MediaFilter(channel,query,type,offline,from,until,duplicates),offset,limit);}
    List<Item> page(MediaFilter f,int offset,int limit){
        if(offset<0||limit<1||limit>96)throw new IllegalArgumentException();
        List<String> args=new ArrayList<>();String sql=from(f,args);args.add(""+limit);args.add(""+offset);
        List<Item> out=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT "+COLUMNS+","+VIEWED+" AS viewed"+STATE_COLUMNS+(f.stacked?",stack_count":",1 AS stack_count")+sql+" ORDER BY "+f.order()+" LIMIT ? OFFSET ?",args.toArray(new String[0]))){while(c.moveToNext())out.add(Item.cursor(c));}return out;
    }
    List<CombinedSearch.Row> searchByName(String source,String query,CombinedSearch.Key after){
        SourceId.require(source);
        if(query.trim().isEmpty())return java.util.Collections.emptyList();
        MediaFilter filter=new MediaFilter();
        filter.query=query;
        List<String> args=new ArrayList<>();
        String sql=where(filter,args);
        if(after!=null){
            // The source breaks ties when both catalogues have the same date and IDs.
            String op=source.compareTo(after.source)>0?">=":">";
            sql+=" AND date<=? AND (date<? OR (date=? AND (id<? OR (id=? AND channel"+op+"?))))";
            java.util.Collections.addAll(args,""+after.date,""+after.date,""+after.date,""+after.id,""+after.id,""+after.channel);
        }
        List<CombinedSearch.Row> rows=new ArrayList<>();
        String chat="COALESCE((SELECT title FROM channels WHERE id=media.channel),'')";
        // Read in page order so common terms do not sort every match to return a handful.
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+","+chat+" FROM media INDEXED BY media_all_order"+sql+" ORDER BY "+filter.order()+" LIMIT "+(CombinedSearch.PAGE_SIZE+1),args.toArray(new String[0]))){
            while(c.moveToNext())rows.add(new CombinedSearch.Row(source,Item.cursor(c),c.getString(11)));
        }
        return rows;
    }
    Item get(int channel,int id){try(Cursor c=db.rawQuery("SELECT "+COLUMNS+","+VIEWED+" AS viewed"+STATE_COLUMNS+" FROM media WHERE channel=? AND id=?",new String[]{""+channel,""+id})){return c.moveToFirst()?Item.cursor(c):null;}}
    Item neighbor(Item item,MediaFilter filter,int direction){
        if(item==null||(direction!=1&&direction!=-1))throw new IllegalArgumentException("Dirección inválida");
        List<String> args=new ArrayList<>();String sql=from(filter,args);List<String> columns=new ArrayList<>(),values=new ArrayList<>();
        columns.add(filter.primary());values.add(filter.sort==6?""+personal.lastOpened(item):filter.sort==2||filter.sort==3?""+item.size:filter.sort==4?""+item.duration:filter.sort==5?item.name:""+item.date);
        if(!filter.primary().equals("date")){columns.add("date");values.add(""+item.date);}columns.add("id");values.add(""+item.id);columns.add("channel");values.add(""+item.channel);
        StringBuilder predicate=new StringBuilder(" AND (");
        for(int n=0;n<columns.size();n++){if(n>0)predicate.append(" OR ");predicate.append('(');for(int j=0;j<n;j++){predicate.append(columns.get(j)).append("=? AND ");args.add(values.get(j));}boolean ascending=n==0?filter.ascending():columns.get(n).equals("channel")||(filter.sort==1&&columns.get(n).equals("id"));predicate.append(columns.get(n)).append((ascending==(direction>0))?">?":"<?").append(')');args.add(values.get(n));}predicate.append(')');
        String order=filter.order();if(direction<0)order=order.replace(" ASC"," TEMP").replace(" DESC"," ASC").replace(" TEMP"," DESC");
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+","+VIEWED+" AS viewed"+STATE_COLUMNS+(filter.stacked?",stack_count":",1 AS stack_count")+sql+predicate+" ORDER BY "+order+" LIMIT 1",args.toArray(new String[0]))){return c.moveToFirst()?Item.cursor(c):null;}
    }
    static final class Flags {final boolean favorite,later,completed,archived;final int position,duration;Flags(boolean favorite,boolean later,int position,int duration,boolean completed,boolean archived){this.archived=archived;this.favorite=favorite;this.later=later;this.position=position;this.duration=duration;this.completed=completed;}}
    Flags flags(Item item){try(Cursor c=db.rawQuery("SELECT favorite,later,position_ms,duration_ms,completed,archived FROM media_state WHERE channel=? AND id=? AND revision=?",new String[]{""+item.channel,""+item.id,item.revision})){return c.moveToFirst()?new Flags(c.getInt(0)!=0,c.getInt(1)!=0,c.getInt(2),c.getInt(3),c.getInt(4)!=0,c.getInt(5)!=0):new Flags(false,false,0,0,false,false);}}
    void setArchived(Item item,boolean value){flag(item,"archived",value);}
    void setFavorite(Item item,boolean value){flag(item,"favorite",value);}
    void setWatchLater(Item item,boolean value){flag(item,"later",value);}
    private void flag(Item item,String column,boolean value){db.execSQL("INSERT INTO media_state(channel,id,revision,"+column+",updated) SELECT channel,id,revision,?,? FROM media WHERE channel=? AND id=? AND revision=? ON CONFLICT(channel,id) DO UPDATE SET "+column+"=excluded."+column+",updated=excluded.updated",new Object[]{value?1:0,System.currentTimeMillis(),item.channel,item.id,item.revision});}
    void savePlayback(Item item,int position,int duration,boolean completed){if(!item.video())return;if(position<0||duration<0||position>duration)throw new IllegalArgumentException("Progreso inválido");db.execSQL("INSERT INTO media_state(channel,id,revision,position_ms,duration_ms,completed,updated) SELECT channel,id,revision,?,?,?,? FROM media WHERE channel=? AND id=? AND revision=? ON CONFLICT(channel,id) DO UPDATE SET position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,completed=excluded.completed,updated=excluded.updated",new Object[]{position,duration,completed?1:0,System.currentTimeMillis(),item.channel,item.id,item.revision});}
    int position(Item item,int channel,String query,int type,boolean offline,long from,long until,boolean duplicates){return position(item,new MediaFilter(channel,query,type,offline,from,until,duplicates));}
    int position(Item item,MediaFilter f){return position(item,f,false);}
    int position(Item item,MediaFilter f,boolean keepNearby){
        List<String> args=new ArrayList<>();String sql=from(f,args);
        List<String> present=new ArrayList<>(args);present.add(""+item.channel);present.add(""+item.id);Item current;
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+sql+" AND channel=? AND id=?",present.toArray(new String[0]))){
            if(c.moveToFirst())current=Item.cursor(c);
            else if(keepNearby)current=item; // A collapsed copy still has a position in the sort order.
            else return -1;
        }
        List<String> columns=new ArrayList<>(),values=new ArrayList<>();columns.add(f.primary());values.add(f.sort==6?""+personal.lastOpened(current):f.sort==2||f.sort==3?""+current.size:f.sort==4?""+current.duration:f.sort==5?current.name:""+current.date);
        if(!f.primary().equals("date")){columns.add("date");values.add(""+current.date);}columns.add("id");values.add(""+current.id);columns.add("channel");values.add(""+current.channel);
        StringBuilder ranked=new StringBuilder(" AND (");
        for(int n=0;n<columns.size();n++){
            if(n>0)ranked.append(" OR ");ranked.append('(');
            for(int j=0;j<n;j++){ranked.append(columns.get(j)).append("=? AND ");args.add(values.get(j));}
            boolean asc=n==0?f.ascending():columns.get(n).equals("channel")||(f.sort==1&&columns.get(n).equals("id"));ranked.append(columns.get(n)).append(asc?"<?":">?").append(')');args.add(values.get(n));
        }ranked.append(')');
        try(Cursor c=db.rawQuery("SELECT count(*)"+sql+ranked,args.toArray(new String[0]))){c.moveToFirst();return c.getInt(0);}
    }
    void saveViewport(String key,Item anchor,int position,int top){
        if(key==null||key.isEmpty()||position<0)return;
        db.beginTransaction();try{
            db.execSQL("INSERT INTO viewports(query_key,channel,id,position,top,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(query_key) DO UPDATE SET channel=excluded.channel,id=excluded.id,position=excluded.position,top=excluded.top,updated=excluded.updated",new Object[]{key,anchor==null?0:anchor.channel,anchor==null?0:anchor.id,position,top,System.currentTimeMillis()});
            db.execSQL("DELETE FROM viewports WHERE query_key NOT IN(SELECT query_key FROM viewports ORDER BY updated DESC LIMIT 64)");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    Viewport viewport(MediaFilter f){try(Cursor c=db.rawQuery("SELECT channel,id,position,top FROM viewports WHERE query_key=?",new String[]{f.key()})){if(!c.moveToFirst())return null;int position=c.getInt(2);Item anchor=c.getInt(0)>0?get(c.getInt(0),c.getInt(1)):null;if(anchor!=null){int ranked=position(anchor,f);if(ranked>=0)position=ranked;}return new Viewport(position,c.getInt(3));}}
    static final class Viewport {final int position,top;Viewport(int p,int t){position=p;top=t;}}
    boolean offline(Item i){try(Cursor c=db.rawQuery("SELECT offline FROM media WHERE channel=? AND id=? AND revision=?",new String[]{""+i.channel,""+i.id,i.revision})){return c.moveToFirst()&&c.getInt(0)!=0;}}
    boolean viewed(Item i){try(Cursor c=db.rawQuery("SELECT 1 FROM viewed_media WHERE channel=? AND id=? AND revision=?",new String[]{""+i.channel,""+i.id,i.revision})){return c.moveToFirst();}}
    void markViewed(Item i){if(!i.video())return;db.execSQL("INSERT INTO viewed_media(channel,id,revision,viewed_at) SELECT channel,id,revision,? FROM media WHERE channel=? AND id=? AND revision=? ON CONFLICT(channel,id) DO UPDATE SET revision=excluded.revision,viewed_at=excluded.viewed_at",new Object[]{System.currentTimeMillis(),i.channel,i.id,i.revision});}
    void offline(Item i,boolean value){db.execSQL("UPDATE media SET offline=? WHERE channel=? AND id=? AND revision=?",new Object[]{value?1:0,i.channel,i.id,i.revision});}

    DateSections dateSections(MediaFilter filter){
        if(filter.sort>1)return DateSections.EMPTY;
        List<String> args=new ArrayList<>();String sql=from(filter,args);List<String> months=new ArrayList<>();List<Integer> counts=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT CASE WHEN date<=0 THEN '0000-00' ELSE COALESCE(strftime('%Y-%m',date,'unixepoch','localtime'),'9999-99') END AS month,count(*)"+sql+" GROUP BY month ORDER BY MIN(date) "+(filter.sort==1?"ASC":"DESC"),args.toArray(new String[0]))){while(c.moveToNext()){months.add(c.getString(0));counts.add(c.getInt(1));}}
        int[] values=new int[counts.size()];for(int n=0;n<values.length;n++)values[n]=counts.get(n);return new DateSections(months.toArray(new String[0]),values);
    }
    private static void selection(List<Item> items){
        if(items==null||items.isEmpty()||items.size()>1000)throw new IllegalArgumentException("Selecciona entre 1 y 1.000 archivos");java.util.HashSet<String> keys=new java.util.HashSet<>();
        for(Item item:items)if(item==null||item.channel<=0||item.id<=0||item.revision==null||!keys.add(item.key()))throw new IllegalArgumentException("Selección inválida");
    }
    int applySelection(List<Item> items,String action){
        selection(items);if(!java.util.Arrays.asList("favorite","unfavorite","later","unlater","viewed","unviewed","archive","unarchive").contains(action))throw new IllegalArgumentException("Acción inválida");
        int applied=0;db.beginTransaction();try{for(Item selected:items){Item current=get(selected.channel,selected.id);if(current==null||!selected.revision.equals(current.revision))continue;
            switch(action){case "favorite":case "unfavorite":setFavorite(current,action.equals("favorite"));break;case "later":case "unlater":setWatchLater(current,action.equals("later"));break;case "archive":case "unarchive":setArchived(current,action.equals("archive"));break;case "viewed":case "unviewed":if(!current.video())continue;if(action.equals("viewed"))markViewed(current);else db.execSQL("DELETE FROM viewed_media WHERE channel=? AND id=? AND revision=?",new Object[]{current.channel,current.id,current.revision});break;default:throw new IllegalArgumentException();}applied++;
        }db.setTransactionSuccessful();return applied;}finally{db.endTransaction();}
    }
    int enqueueSelection(List<Item> items){
        selection(items);db.beginTransaction();try{List<Item> current=new ArrayList<>();int added=0,total;try(Cursor c=db.rawQuery("SELECT count(*) FROM downloads",(String[])null)){c.moveToFirst();total=c.getInt(0);}
            for(Item selected:items){Item item=get(selected.channel,selected.id);if(item==null||!selected.revision.equals(item.revision)||offline(item))continue;current.add(item);try(Cursor c=db.rawQuery("SELECT 1 FROM downloads WHERE channel=? AND id=?",new String[]{""+item.channel,""+item.id})){if(!c.moveToFirst())added++;}}
            if(total+added>1000)throw new IllegalStateException("La cola tiene un límite de 1.000 archivos. Completa o retira algunos antes de añadir este grupo.");long now=System.currentTimeMillis();
            for(Item item:current)db.execSQL("INSERT INTO downloads(channel,id,added,revision,status,progress,error) VALUES(?,?,?,?,'queued',0,'') ON CONFLICT(channel,id) DO UPDATE SET status=CASE WHEN downloads.status='running' THEN 'running' ELSE 'queued' END,error=''",new Object[]{item.channel,item.id,now++,item.revision});
            db.setTransactionSuccessful();return current.size();
        }finally{db.endTransaction();}
    }
    boolean prioritizeDownload(int channel,int id){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("La cola se modifica fuera de la interfaz");
        db.beginTransaction();try{
            try(Cursor row=db.rawQuery("SELECT 1 FROM downloads d JOIN media m ON m.channel=d.channel AND m.id=d.id AND m.revision=d.revision WHERE d.channel=? AND d.id=?",new String[]{""+channel,""+id})){if(!row.moveToFirst())return false;}
            List<int[]> rows=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT channel,id FROM downloads ORDER BY priority DESC,added,channel,id",(String[])null)){while(c.moveToNext()){if(rows.size()>=1000)throw new IllegalStateException("La cola excede su límite");rows.add(new int[]{c.getInt(0),c.getInt(1)});}}
            int rank=rows.size()-1;for(int[] row:rows)if(row[0]!=channel||row[1]!=id)db.execSQL("UPDATE downloads SET priority=? WHERE channel=? AND id=?",new Object[]{rank--,row[0],row[1]});
            db.execSQL("UPDATE downloads SET priority=? WHERE channel=? AND id=?",new Object[]{rows.size(),channel,id});db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    void syncState(int channel,int status){if(channel<=0||status<0||status>4)throw new IllegalArgumentException("Estado de actualización inválido");db.execSQL("UPDATE channels SET sync_state=?,sync_updated=? WHERE id=?",new Object[]{status,System.currentTimeMillis(),channel});}
    java.util.Map<Integer,ChatStatus> chatStatuses(){
        java.util.Map<Integer,ChatStatus> out=new java.util.HashMap<>();try(Cursor c=db.rawQuery("SELECT c.id,c.complete,c.scanning,c.cursor,c.scan_cursor,c.sync_state,c.sync_updated,c.sync_boundary,count(m.id),MIN(CASE WHEN m.date>0 THEN m.date END) FROM channels c LEFT JOIN media m ON m.channel=c.id GROUP BY c.id",(String[])null)){while(c.moveToNext())out.put(c.getInt(0),new ChatStatus(c.getInt(0),c.getInt(1)!=0,c.getInt(2)!=0,c.getInt(2)!=0?c.getInt(4):c.getInt(3),c.getInt(5),c.getLong(6),c.getLong(7),c.getLong(8),c.isNull(9)?0:c.getLong(9)));}return out;
    }
    static final class ChatStatus {
        final int channel,cursor,state;final boolean complete,scanning;final long updated,boundary,count,oldest;
        ChatStatus(int channel,boolean complete,boolean scanning,int cursor,int state,long updated,long boundary,long count,long oldest){this.channel=channel;this.complete=complete;this.scanning=scanning;this.cursor=cursor;this.state=state;this.updated=updated;this.boundary=boundary;this.count=count;this.oldest=oldest;}
    }
}
