package local.ttlab.gallery;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.widget.Toast;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One serialized worker. Each active transfer keeps its own source and encrypted store. */
public final class DownloadService extends Service {
    static final String CHANGED="local.ttlab.gallery.DOWNLOAD_CHANGED";
    private static final String CHANNEL="private-downloads";
    static volatile boolean running;
    static volatile Transfer transfer;
    private static final Set<String> liveSources=ConcurrentHashMap.newKeySet();
    private final Map<String,State> states=new ConcurrentHashMap<>();
    private HandlerThread thread;private Handler queue;private final Handler main=new Handler(Looper.getMainLooper());
    private NotificationManager notifications;private State state;private volatile boolean closed;
    private Item active;private int nextChunk,handledStart;private long request,startBytes,startElapsed,lastPublish;private boolean scheduled;private String lastSource="tato";
    static boolean running(String source){return liveSources.contains(SourceId.require(source));}
    static Transfer transfer(String source){Transfer current=transfer;return current!=null&&current.source.equals(SourceId.require(source))?current:null;}
    private static String selected(Context c){return c instanceof SecureActivity&&((SecureActivity)c).state!=null?((SecureActivity)c).state.key:UiSource.key(c);}
    static void start(Activity activity,Item item){start(activity,selected(activity),item);}
    static void start(Activity activity,String source,Item item){
        if(SourceId.local(source))return;
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},40);
        boolean started=ForegroundWork.start(activity,route(new Intent(activity,DownloadService.class),source,"enqueue").putExtra("channel",item.channel).putExtra("id",item.id));
        Toast.makeText(activity,started?"Descarga añadida. Puedes ver su progreso en Descargas":"Android no permitió iniciar la descarga. Mantén la galería abierta y reintenta.",Toast.LENGTH_SHORT).show();
    }
    static boolean wake(Activity activity,String source){
        if(SourceId.local(source))return false;
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},40);
        return ForegroundWork.start(activity,route(new Intent(activity,DownloadService.class).setAction("run-queued"),source,"run-queued"));
    }
    static boolean control(Context c,String action,int channel,int id){return control(c,selected(c),action,channel,id);}
    static boolean control(Context c,String source,String action,int channel,int id){return !SourceId.local(source)&&ForegroundWork.start(c,route(new Intent(c,DownloadService.class).setAction(action),source,action).putExtra("channel",channel).putExtra("id",id));}
    private static Intent route(Intent intent,String source,String action){String key=SourceId.require(source);return intent.putExtra("source",key).setData(Uri.parse("gallery://downloads/"+key+"/"+action));}
    @Override public void onCreate(){
        super.onCreate();notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Descargas privadas",NotificationManager.IMPORTANCE_LOW));
        thread=new HandlerThread("EncryptedDownloads");thread.start();queue=new Handler(thread.getLooper());
    }
    private PendingIntent open(String source){return PendingIntent.getActivity(this,0,route(new Intent(this,DownloadsActivity.class),source,"open"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private Notification notification(String source,String message,int progress){
        PendingIntent pause=PendingIntent.getService(this,1,route(new Intent(this,DownloadService.class).setAction("pause-all"),source,"pause-all"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Tato Galería · "+SourceId.label(source)).setContentText(progress<0?"Preparando descargas cifradas…":"Descarga cifrada en curso").setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true).setContentIntent(open(source)).setProgress(100,progress,progress<0).addAction(new Notification.Action.Builder(null,"Pausar",pause).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        Intent command=intent==null?new Intent():new Intent(intent);final String key;
        try{key=SourceId.require(command.getStringExtra("source")==null?"tato":command.getStringExtra("source"));command.putExtra("source",key);}catch(IllegalArgumentException invalid){stopSelf(startId);return START_NOT_STICKY;}
        Transfer current=transfer;String shown=current==null?key:current.source;
        if(!ForegroundWork.promote(this,1,notification(shown,"Preparando descargas cifradas…",-1))){changed(key);stopSelf(startId);return START_NOT_STICKY;}
        queue.post(()->command(command,startId));return START_REDELIVER_INTENT;
    }
    private void guard(State selected){if(!Profiles.current()||!getSystemService(UserManager.class).isUserForeground()||selected.maintenance)throw new SecurityException("Perfil no autorizado o respaldo en curso");}
    private State resources(String source)throws Exception{
        State selected=states.get(source);
        if(selected==null){selected=State.get(this,source);guard(selected);selected.library.db.execSQL("UPDATE downloads SET status='queued' WHERE status='running'");states.put(source,selected);}
        return selected;
    }
    private void command(Intent intent,int startId){
        if(closed)return;handledStart=startId;String key=intent.getStringExtra("source");
        try{
            final State selected=resources(key);guard(selected);String action=intent.getAction();
            int id=intent.getIntExtra("id",0),channel=intent.getIntExtra("channel",0);
            if("cancel".equals(action)||"clear".equals(action)){
                try(Cursor c=selected.library.db.rawQuery("SELECT channel,id,revision,progress FROM downloads",(String[])null)){while(c.moveToNext())discardPartial(selected,c.getInt(0),c.getInt(1),c.getString(2),c.getLong(3));}
                selected.library.db.execSQL("DELETE FROM downloads");clearActive(selected);
                if("clear".equals(action)){selected.vault.clearSaved();selected.library.db.execSQL("UPDATE media SET offline=0");selected.media.clearMemory();}
                liveSources.remove(key);changed(key);finishIfIdle(key,"clear".equals(action)?"Descargas eliminadas.":"Cola cancelada; los archivos completos se conservan.");return;
            }
            if("pause-all".equals(action)){
                selected.library.db.execSQL("UPDATE downloads SET status='paused' WHERE status IN('running','queued')");clearActive(selected);liveSources.remove(key);changed(key);finishIfIdle(key,"Descargas pausadas. Puedes continuarlas desde Descargas.");return;
            }
            if("resume-all".equals(action))selected.library.db.execSQL("UPDATE downloads SET status='queued',error='' WHERE status IN('paused','error','running')");
            else if("pause".equals(action)||"remove".equals(action)){
                if(id<=0)throw new IllegalArgumentException("Archivo inválido");
                if("pause".equals(action))selected.library.db.execSQL("UPDATE downloads SET status='paused' WHERE channel=? AND id=?",new Object[]{channel,id});
                else{
                    try(Cursor c=selected.library.db.rawQuery("SELECT revision,progress FROM downloads WHERE channel=? AND id=?",new String[]{""+channel,""+id})){if(c.moveToFirst())discardPartial(selected,channel,id,c.getString(0),c.getLong(1));}
                    selected.library.db.execSQL("DELETE FROM downloads WHERE channel=? AND id=?",new Object[]{channel,id});
                }
                if(state==selected&&active!=null&&active.channel==channel&&active.id==id)clearActive(selected);
            }else if("priority".equals(action)){
                selected.library.prioritizeDownload(channel,id);
            }else if(id>0){
                Item item=selected.library.get(channel,id);if(item==null)throw new IllegalArgumentException("El archivo ya no está en el catálogo");
                selected.library.enqueueSelection(Collections.singletonList(item));
            }
            updateRunning(selected);changed(key);schedule();
        }catch(Exception error){finishIfIdle(key,error instanceof IllegalStateException&&error.getMessage()!=null&&error.getMessage().startsWith("La cola tiene")?error.getMessage():"No se pudo preparar la descarga. Abre Descargas para reintentar.");}
    }
    private void clearActive(State selected){if(state==selected){active=null;transfer=null;}}
    private static boolean hasWork(State selected){try(Cursor c=selected.library.db.rawQuery("SELECT 1 FROM downloads WHERE status IN('queued','running') LIMIT 1",(String[])null)){return c.moveToFirst();}}
    private void updateRunning(State selected){if(!closed&&hasWork(selected))liveSources.add(selected.key);else liveSources.remove(selected.key);running=!liveSources.isEmpty();}
    private void discardPartial(State selected,int channel,int id,String revision,long bytes)throws Exception{
        Item latest=selected.library.get(channel,id);
        if(latest!=null&&latest.revision.equals(revision)&&selected.library.offline(latest))return;
        if(revision==null||revision.isEmpty()){if(latest!=null&&!selected.library.offline(latest))selected.media.removeDownload(latest);return;}
        Item prefix=new Item(id,channel,"","image/png",Math.min(Math.max(0,bytes),"telegram".equals(selected.key)?4L*1024*1024*1024:Integer.MAX_VALUE),0,0,0,0,revision);
        for(int n=0;n<prefix.chunks();n++)selected.vault.unpin("media/"+prefix.cacheKey()+"/"+n);
    }
    private void schedule(){if(!closed&&!scheduled){scheduled=true;queue.post(this::step);}}
    private void step(){
        scheduled=false;if(closed)return;
        try{
            if(active==null){
                state=null;long oldest=Long.MAX_VALUE;
                for(State candidate:states.values())try(Cursor c=candidate.library.db.rawQuery("SELECT added FROM downloads WHERE status IN('queued','running') ORDER BY priority DESC,added,channel,id LIMIT 1",(String[])null)){
                    if(c.moveToFirst()&&(state==null||c.getLong(0)<oldest)){state=candidate;oldest=c.getLong(0);}updateRunning(candidate);
                }
                if(state==null){finishIfIdle(lastSource,"No hay descargas activas. Las pausadas se conservan.");return;}
                guard(state);int channel,id;long bytes;String revision;
                try(Cursor c=state.library.db.rawQuery("SELECT channel,id,added,progress,revision FROM downloads WHERE status IN('queued','running') ORDER BY priority DESC,added,channel,id LIMIT 1",(String[])null)){
                    if(!c.moveToFirst()){schedule();return;}channel=c.getInt(0);id=c.getInt(1);request=c.getLong(2);bytes=c.getLong(3);revision=c.getString(4);
                }
                active=state.library.get(channel,id);
                if(active==null){discardPartial(state,channel,id,revision,bytes);state.library.db.execSQL("DELETE FROM downloads WHERE channel=? AND id=? AND added=?",new Object[]{channel,id,request});schedule();return;}
                if(!active.revision.equals(revision))discardPartial(state,channel,id,revision,bytes);
                long complete=active.revision.equals(revision)?DownloadProgress.resumeBytes(bytes,active.size):0;
                nextChunk=complete>=active.size?active.chunks():(int)(complete/Source.CHUNK);
                if(state.library.offline(active)){nextChunk=active.chunks();complete=active.size;}
                startBytes=complete;startElapsed=SystemClock.elapsedRealtime();
                state.library.db.execSQL("UPDATE downloads SET status='running',revision=?,progress=?,error='' WHERE channel=? AND id=? AND added=?",new Object[]{active.revision,complete,channel,id,request});publish(true);
            }
            guard(state);Item latest=state.library.get(active.channel,active.id);
            if(latest==null||!latest.revision.equals(active.revision))throw new java.io.IOException("El archivo cambió. Actualiza el catálogo antes de reintentar.");
            if(nextChunk<active.chunks()){
                if(new StatFs(getNoBackupFilesDir().getPath()).getAvailableBytes()<Source.CHUNK+32L*1024*1024)throw new java.io.IOException("Libera espacio para continuar");
                byte[] bytes=state.media.chunk(active,nextChunk);if(closed)return;guard(state);
                state.media.keepChunk(active,nextChunk,bytes);nextChunk++;
                long complete=Math.min(active.size,(long)nextChunk*Source.CHUNK);
                state.library.db.execSQL("UPDATE downloads SET progress=? WHERE channel=? AND id=? AND added=? AND revision=?",new Object[]{complete,active.channel,active.id,request,active.revision});publish(false);
            }
            if(nextChunk>=active.chunks()){
                state.media.keepThumbnail(active);state.library.offline(active,true);if(!state.library.offline(active))throw new java.io.IOException("El archivo cambió");
                state.library.db.execSQL("DELETE FROM downloads WHERE channel=? AND id=? AND added=?",new Object[]{active.channel,active.id,request});lastSource=state.key;active=null;transfer=null;updateRunning(state);changed(state.key);
            }
            schedule();
        }catch(Exception error){
            String key=state==null?"tato":state.key;
            if(active!=null){String message=error.getMessage()!=null&&error.getMessage().startsWith("Libera espacio")?"Libera espacio para continuar":ConnectionMessage.describe(error,Profiles.owner(),key);
                try{state.library.db.execSQL("UPDATE downloads SET status='error',error=? WHERE channel=? AND id=? AND added=?",new Object[]{message,active.channel,active.id,request});}catch(Exception ignored){}
            }
            if(state!=null)try{state.library.db.execSQL("UPDATE downloads SET status='paused' WHERE status IN('queued','running')");}catch(Exception ignored){}
            active=null;transfer=null;if(state!=null)updateRunning(state);changed(key);finishIfIdle(key,"Descarga detenida. Puedes ver el motivo y reintentar desde Descargas.");
        }
    }
    private void publish(boolean force){
        if(closed||active==null)return;long bytes=Math.min(active.size,(long)nextChunk*Source.CHUNK);transfer=new Transfer(state.key,active.channel,active.id,bytes,active.size,startBytes,startElapsed);
        long now=SystemClock.elapsedRealtime();if(force||now-lastPublish>=750){lastPublish=now;notifications.notify(1,notification(state.key,"Guardando archivo cifrado · "+DownloadProgress.percent(bytes,active.size)+" %",DownloadProgress.percent(bytes,active.size)));changed(state.key,false);}
    }
    private void changed(String source){changed(source,true);}
    private void changed(String source,boolean catalogue){sendBroadcast(new Intent(CHANGED).putExtra("source",source).putExtra("catalogue",catalogue).setPackage(getPackageName()),"local.ttlab.permission.GALLERY");}
    private void finishIfIdle(String source,String message){
        for(State selected:states.values())updateRunning(selected);
        if(running){schedule();return;}final int completedStart=handledStart;
        main.post(()->{if(!closed&&stopSelfResult(completedStart)){
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifications.notify("telegram".equals(source)?3:2,new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Tato Galería · "+SourceId.label(source)).setContentText("Revisa el estado en Descargas").setVisibility(Notification.VISIBILITY_SECRET).setAutoCancel(true).setContentIntent(open(source)).build());
        }});
    }
    @Override public void onTimeout(int startId,int foregroundServiceType){stopSelf();}
    @Override public void onDestroy(){closed=true;running=false;transfer=null;liveSources.clear();queue.removeCallbacksAndMessages(null);thread.quitSafely();for(String source:states.keySet())changed(source);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
    static final class Transfer {
        final String source;final int channel,id;final long bytes,total,startBytes,startElapsed;
        Transfer(int channel,int id,long bytes,long total,long startBytes,long startElapsed){this("tato",channel,id,bytes,total,startBytes,startElapsed);}
        Transfer(String source,int channel,int id,long bytes,long total,long startBytes,long startElapsed){this.source=SourceId.require(source);this.channel=channel;this.id=id;this.bytes=bytes;this.total=total;this.startBytes=startBytes;this.startElapsed=startElapsed;}
        long remainingSeconds(){return DownloadProgress.remainingSeconds(bytes,total,startBytes,SystemClock.elapsedRealtime()-startElapsed);}
    }
}
