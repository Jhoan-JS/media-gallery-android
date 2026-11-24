package local.ttlab.gallery;
import android.content.Context;
import java.util.concurrent.*;
final class State {
    private static State instance,telegram,local,web;private static boolean restoresChecked;
    final String key;final android.content.SharedPreferences preferences;
    final WebCatalogue webCatalogue;
    final Library library;final Vault vault,securityVault;final Source source;final MediaCache media;final VisualIndex visual;
    final ThreadPoolExecutor gridPreviews=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(128),r->{Thread t=new Thread(r,"gallery-grid-preview");t.setPriority(Thread.MIN_PRIORITY);return t;});
    final ExecutorService database=Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor thumbnails=new ThreadPoolExecutor(3,3,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(48),new ThreadPoolExecutor.DiscardOldestPolicy());
    static volatile boolean unlocked,maintenance;static volatile long paused;
    private State(Context c)throws Exception{this(c,"tato");}
    private State(Context c,String sourceKey)throws Exception{
        gridPreviews.allowCoreThreadTimeOut(true);key=SourceId.require(sourceKey);synchronized(State.class){if(!restoresChecked){GalleryBackup.finishRestore(c);restoresChecked=true;}}securityVault=new Vault(c);
        vault="tato".equals(key)?securityVault:new Vault(c,SourceId.vault(key));
        preferences=PrivatePreferences.open(c,key,vault);
        byte[] databaseKey=vault.databaseKey();try{library=new Library(c.getDatabasePath(SourceId.database(key)),databaseKey);}finally{java.util.Arrays.fill(databaseKey,(byte)0);}
        webCatalogue=SourceId.web(key)?new WebCatalogue(library):null;
        if(SourceId.web(key))source=new WebSource(c,vault,library);
        else if(SourceId.local(key))source=new Source(c,vault,library,"","local.ttlab.no-connector",false,"Archivos propios"){
            @Override void guard()throws java.io.IOException{throw new java.io.FileNotFoundException("La copia local no está disponible");}
            @Override synchronized int rangeSize(){return CHUNK;}
        };
        else source="tato".equals(key)?new Source(c,vault,library):new TelegramSource(c,vault,library);
        visual="tato".equals(key)?new VisualIndex(c,library,vault):null;
        media=new MediaCache(c,vault,source,library);media.visual=visual;
        if(SourceId.local(key)){LocalFiles.prepare(this);database.execute(()->{try{LocalFiles.cleanup(this);}catch(Exception pending){/* Keep the journal to retry on the next import. */}});}
    }
    static synchronized State get(Context c)throws Exception{return get(c,UiSource.key(c));}
    static synchronized State get(Context c,String key)throws Exception{
        SourceId.require(key);if("tato".equals(key)){if(instance==null)instance=new State(c.getApplicationContext(),key);return instance;}
        if(SourceId.local(key)){if(local==null)local=new State(c.getApplicationContext(),key);return local;}
        if(SourceId.web(key)){if(web==null)web=new State(c.getApplicationContext(),key);return web;}
        if(telegram==null)telegram=new State(c.getApplicationContext(),key);return telegram;
    }
}
