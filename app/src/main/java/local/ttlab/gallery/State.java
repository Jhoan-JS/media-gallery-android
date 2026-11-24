package local.ttlab.gallery;
import android.content.Context;
import java.util.concurrent.*;
final class State {
    private static State instance,telegram,local,web,gelbooru;private static boolean restoresChecked;
    final String key,liveId;final android.content.SharedPreferences preferences;
    private static State liveGelbooru;
    final WebCatalogue webCatalogue;
    final Library library;final Vault vault,securityVault;final Source source;final MediaCache media;final VisualIndex visual;
    final ThreadPoolExecutor gridPreviews=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(128),r->{Thread t=new Thread(r,"gallery-grid-preview");t.setPriority(Thread.MIN_PRIORITY);return t;});
    final ExecutorService database=Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor thumbnails=new ThreadPoolExecutor(3,3,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(48),new ThreadPoolExecutor.DiscardOldestPolicy());
    static volatile boolean unlocked,maintenance;static volatile long paused;
    private State(Context c)throws Exception{this(c,"tato");}
    private State(Context c,String sourceKey)throws Exception{this(c,sourceKey,null);}
    private State(Context c,String sourceKey,State saved)throws Exception{
        gridPreviews.allowCoreThreadTimeOut(true);key=SourceId.require(sourceKey);
        liveId=saved==null?null:java.util.UUID.randomUUID().toString();
        synchronized(State.class){if(!restoresChecked){GalleryBackup.finishRestore(c);restoresChecked=true;}}
        securityVault=saved!=null?saved.securityVault:new Vault(c);
        vault=saved!=null?saved.vault:"tato".equals(key)?securityVault:new Vault(c,SourceId.vault(key));
        preferences=saved!=null?saved.preferences:PrivatePreferences.open(c,key,vault);
        byte[] databaseKey=liveId==null?vault.databaseKey():new byte[0];
        try{library=new Library(liveId==null?c.getDatabasePath(SourceId.database(key)):null,databaseKey);}
        finally{java.util.Arrays.fill(databaseKey,(byte)0);}
        webCatalogue=SourceId.web(key)?new WebCatalogue(library):null;
        if(SourceId.web(key))source=new WebSource(c,vault,saved==null?library:saved.library,key,liveId);
        else if(SourceId.local(key))source=new Source(c,vault,library,"","local.ttlab.no-connector",false,"Archivos propios"){
            @Override void guard()throws java.io.IOException{throw new java.io.FileNotFoundException("La copia local no está disponible");}
            @Override synchronized int rangeSize(){return CHUNK;}
        };
        else source="tato".equals(key)?new Source(c,vault,library):new TelegramSource(c,vault,library);
        visual="tato".equals(key)?new VisualIndex(c,library,vault):null;
        media=new MediaCache(c,vault,source,library,liveId!=null);media.visual=visual;
        if(SourceId.local(key)){LocalFiles.prepare(this);database.execute(()->{try{LocalFiles.cleanup(this);}catch(Exception pending){/* Keep the journal to retry on the next import. */}});}
    }
    static synchronized State startWebLive(Context context, State saved)throws Exception{
        if(!WebSource.GELBOORU.equals(saved.key))throw new IllegalArgumentException();
        State next=new State(context.getApplicationContext(),saved.key,saved);
        try{((WebSource)next.source).beginLive();}
        catch(Exception unavailable){endWebLive(next);throw unavailable;}
        liveGelbooru=next;return next;
    }
    static synchronized State resolve(Context context,String key,String live)throws Exception{
        if(live==null)return get(context,key);
        if(liveGelbooru==null||!liveGelbooru.key.equals(key)||!liveGelbooru.liveId.equals(live)){
            throw new java.io.IOException("La sesión de Gelbooru terminó");
        }
        return liveGelbooru;
    }
    static synchronized void endWebLive(State session){
        if(session==null||session.liveId==null||session.database.isShutdown())return;
        if(liveGelbooru==session)liveGelbooru=null;
        session.gridPreviews.shutdownNow();
        session.thumbnails.shutdownNow();
        session.media.clearMemory();
        session.database.execute(()->{
            ((WebSource)session.source).endLive();
            session.media.clearMemory();
            session.library.db.close();
        });
        session.database.shutdown();
    }
    static synchronized State get(Context c)throws Exception{return get(c,UiSource.key(c));}
    static synchronized State get(Context c,String key)throws Exception{
        SourceId.require(key);if("tato".equals(key)){if(instance==null)instance=new State(c.getApplicationContext(),key);return instance;}
        if(SourceId.local(key)){if(local==null)local=new State(c.getApplicationContext(),key);return local;}
        if(WebSource.GELBOORU.equals(key)){if(gelbooru==null)gelbooru=new State(c.getApplicationContext(),key);return gelbooru;}
        if(SourceId.web(key)){if(web==null)web=new State(c.getApplicationContext(),key);return web;}
        if(telegram==null)telegram=new State(c.getApplicationContext(),key);return telegram;
    }
}
