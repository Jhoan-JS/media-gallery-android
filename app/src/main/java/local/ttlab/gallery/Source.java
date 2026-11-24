package local.ttlab.gallery;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.UserManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class Source {
    static final int CHUNK=131072;
    static final int AUTHORIZED_USER=14;
    private final Context context;
    private final String base,connector,label;private final boolean explicitConsent;
    private Vault vault;private final Library library;private String account;private int rangeMax=-1;
    Source(Context c){this(c,null,null);}
    Source(Context c,Vault v,Library l){this(c,v,l,"org.im.ttalk.lab","local.ttlab.bridge",Profiles.owner(),"Tato 2.0");}
    Source(Context c,Vault v,Library l,String connectorPackage,String authority,boolean consent,String connectorLabel){
        context=c.getApplicationContext();vault=v;library=l;connector=connectorPackage;base="content://"+authority+"/";explicitConsent=consent;label=connectorLabel;
    }
    void opened(Item item)throws Exception{}
    int playbackBatch(){return 8;}
    int playbackReaders(){return 4;}
    org.json.JSONObject sponsored(int channel)throws Exception{return null;}
    void sponsoredAction(String action,int channel,String id)throws Exception{}
    void guard()throws Exception{
        if(!Profiles.current()||android.os.Build.VERSION.SDK_INT<31
            ||!((UserManager)context.getSystemService(Context.USER_SERVICE)).isUserForeground())throw new SecurityException("Abre la instalación en su usuario Android activo");
        if(context.getPackageManager().checkSignatures(context.getPackageName(),connector)!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Instala Conector "+label+" en el mismo usuario Android");
    }
    private synchronized String account()throws Exception{
        if(account!=null)return account;
        guard();if(vault==null)vault=new Vault(context);
        byte[] secret=vault.secret("source-account");String stored=secret==null?null:new String(secret,java.nio.charset.StandardCharsets.US_ASCII);
        if(explicitConsent&&library==null)throw new SecurityException("Abre la galería para autorizar esta biblioteca");
        String offered;
        try(Cursor c=query(Uri.parse(base+"identity"))){
            if(!c.moveToFirst())throw new SecurityException("E_CONNECTOR_IDENTITY");offered=c.getString(0);
        }
        boolean hasCatalogue=false;
        if(library!=null)try(Cursor c=library.db.rawQuery("SELECT EXISTS(SELECT 1 FROM media LIMIT 1) OR EXISTS(SELECT 1 FROM channels LIMIT 1)",(String[])null)){c.moveToFirst();hasCatalogue=c.getInt(0)!=0;}
        String accepted=SourceAccount.accept(stored,offered,explicitConsent,hasCatalogue);
        if(stored==null)vault.secret("source-account",accepted.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        account=accepted;return accepted;
    }
    protected Uri uri(String path)throws Exception{return Uri.parse(base+path).buildUpon().appendQueryParameter("account",account()).build();}
    List<Channel> channels()throws Exception{
        guard();List<Channel> result=new ArrayList<>();
        try(Cursor c=query(uri("channels"))){
            int peerKey=c.getColumnIndex("peer_key");while(c.moveToNext())result.add(new Channel(c.getInt(0),c.getString(1),peerKey>=0?c.getString(peerKey):""));
        }return result;
    }
    Page page(int channel,int cursor)throws Exception{
        guard();Page result=new Page();
        Uri uri=uri("media/"+channel).buildUpon().appendQueryParameter("cursor",""+cursor).appendQueryParameter("limit","50").build();
        try(Cursor c=query(uri)){
            while(c.moveToNext())result.items.add(Item.cursor(c));
            result.next=c.getExtras().getInt("next",-1);
            result.newest=c.getExtras().getInt("newest",0);
            for(Item i:result.items)result.newest=Math.max(result.newest,i.id);
        }
        if(result.next==0||(cursor>0&&result.next>=cursor))throw new IllegalStateException("El catálogo no avanzó; vuelve a sincronizar");
        return result;
    }
    byte[] chunk(Item item,int index)throws Exception{
        long offset=(long)index*CHUNK;int length=(int)Math.min(CHUNK,item.size-offset);
        if(index<0||length<=0)throw new IllegalArgumentException("Rango inválido");
        Uri uri=uri("file/"+item.key()).buildUpon().appendQueryParameter("offset",""+offset).appendQueryParameter("length",""+length).appendQueryParameter("revision",item.revision).build();
        return read(uri,CHUNK,length);
    }
    byte[] thumbnail(Item item)throws Exception{return read(uri("thumb/"+item.key()).buildUpon().appendQueryParameter("revision",item.revision).build(),CHUNK);}
    synchronized int rangeSize()throws Exception{
        if(rangeMax>0)return rangeMax;guard();
        try(Cursor c=query(uri("capabilities"))){
            int offered=c.moveToFirst()&&c.getInt(0)>=2?c.getInt(1):CHUNK;
            rangeMax=offered>=CHUNK&&offered<=8*CHUNK&&offered%CHUNK==0?offered:CHUNK;
        }catch(IllegalArgumentException|IllegalStateException legacy){rangeMax=CHUNK;}return rangeMax;
    }
    byte[] range(Item item,int first,int count)throws Exception{
        long offset=(long)first*CHUNK;int length=(int)Math.min((long)count*CHUNK,item.size-offset);
        if(first<0||count<1||count>8||length<=0||length>rangeSize())throw new IllegalArgumentException("Rango de lectura inválido");
        if(rangeSize()==CHUNK)return chunk(item,first);
        return read(uri("stream/"+item.key()).buildUpon().appendQueryParameter("offset",""+offset).appendQueryParameter("length",""+length).appendQueryParameter("revision",item.revision).build(),8*CHUNK,length);
    }
    interface BlockConsumer {void accept(int index,byte[] bytes)throws Exception;}
    protected InputStream openStream(Uri target,android.os.CancellationSignal cancel)throws Exception{android.content.res.AssetFileDescriptor file=context.getContentResolver().openAssetFileDescriptor(target,"r",cancel);if(file==null)return null;try{return file.createInputStream();}catch(Exception failure){file.close();throw failure;}}
    void rangeBlocks(Item item,int first,int count,android.os.CancellationSignal cancel,BlockConsumer consumer)throws Exception{
        long offset=(long)first*CHUNK;int length=(int)Math.min((long)count*CHUNK,item.size-offset);
        if(first<0||count<1||count>8||length<=0||length>rangeSize())throw new IllegalArgumentException("Rango de lectura inválido");
        if(rangeSize()==CHUNK){consumer.accept(first,chunk(item,first));return;}
        final int[] delivered={0};
        ConnectionRetry.read(()->{
            cancel.throwIfCanceled();
            if(delivered[0]==length)return true;
            Uri target=uri("stream/"+item.key()).buildUpon().appendQueryParameter("offset",""+(offset+delivered[0])).appendQueryParameter("length",""+(length-delivered[0])).appendQueryParameter("revision",item.revision).build();
            guard();try(InputStream input=openStream(target,cancel)){
                if(input==null)throw new java.io.IOException("E_CONNECTOR_UNAVAILABLE");
                cancel.setOnCancelListener(()->{try{input.close();}catch(IOException ignored){}});cancel.throwIfCanceled();
                while(delivered[0]<length){byte[] block=new byte[Math.min(CHUNK,length-delivered[0])];int filled=0;
                    while(filled<block.length){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();int n=input.read(block,filled,block.length-filled);if(n<0)throw new java.io.IOException("E_TRANSFER_INCOMPLETE");if(n==0)continue;filled+=n;}
                    consumer.accept(first+delivered[0]/CHUNK,block);delivered[0]+=block.length;
                }
                if(input.read()!=-1)throw new IllegalStateException("Respuesta demasiado grande");return true;
            }finally{cancel.setOnCancelListener(null);}
        });
    }
    protected Cursor query(Uri uri)throws Exception{return ConnectionRetry.read(()->{
        guard();Cursor cursor=context.getContentResolver().query(uri,null,null,null,null);
        if(cursor==null)throw new java.io.IOException("E_CONNECTOR_UNAVAILABLE");return cursor;
    });}
    private byte[] read(Uri uri,int max)throws Exception{return read(uri,max,-1);}
    private byte[] read(Uri uri,int max,int expected)throws Exception{return ConnectionRetry.read(()->{
        guard();try(InputStream input=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            if(input==null)throw new java.io.IOException("E_CONNECTOR_UNAVAILABLE");
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(output.size()+count>max)throw new IllegalStateException("Respuesta demasiado grande");output.write(buffer,0,count);}
            if(output.size()==0||(expected>=0&&output.size()!=expected))throw new java.io.IOException("E_TRANSFER_INCOMPLETE");
            return output.toByteArray();
        }
    });}
    static final class Channel {final int id;final String title,remoteKey;Channel(int i,String t){this(i,t,"");}Channel(int i,String t,String remote){id=i;title=t;remoteKey=remote==null?"":remote;}@Override public String toString(){return title;}}
    static final class Page {final List<Item> items=new ArrayList<>();int next,newest;}
}
