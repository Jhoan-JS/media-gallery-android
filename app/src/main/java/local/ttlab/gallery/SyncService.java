package local.ttlab.gallery;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground catalogue work; immutable jobs keep source-specific resume checkpoints. */
public final class SyncService extends Service {
    static final String CHANGED="local.ttlab.gallery.SYNC_CHANGED";
    static volatile boolean running;
    static volatile String message="Catálogo local · originales bajo demanda";
    private static final String CHANNEL="private-catalogue",IDLE="Catálogo local · originales bajo demanda";
    private static final Map<String,String> messages=new ConcurrentHashMap<>();
    private static final Map<String,Integer> activeChannels=new ConcurrentHashMap<>();
    private static final Set<String> liveSources=ConcurrentHashMap.newKeySet();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private volatile boolean closed;private volatile Job current;private int lastStart;
    static int currentChannel(String source){return activeChannels.getOrDefault(SourceId.require(source),0);}
    static boolean running(String source){return liveSources.contains(SourceId.require(source));}
    static String message(String source){return messages.getOrDefault(SourceId.require(source),IDLE);}
    private static String selected(Context c){return c instanceof SecureActivity&&((SecureActivity)c).state!=null?((SecureActivity)c).state.key:UiSource.key(c);}
    static void start(Context c,int channel){start(c,selected(c),channel);}
    static void start(Context c,String source,int channel){if(!SourceId.local(source))request(c,route(new Intent(c,SyncService.class),source,"start").putExtra("channel",channel));}
    static void pause(Context c){pause(c,selected(c));}
    static void pause(Context c,String source){request(c,route(new Intent(c,SyncService.class).setAction("pause"),source,"pause"));}
    private static Intent route(Intent intent,String source,String action){String key=SourceId.require(source);return intent.putExtra("source",key).setData(Uri.parse("gallery://catalogue/"+key+"/"+action));}
    private static void status(String source,String text){messages.put(source,text);message=text;}
    private static void changed(Context c,String source){c.sendBroadcast(new Intent(CHANGED).putExtra("source",source).setPackage(c.getPackageName()),"local.ttlab.permission.GALLERY");}
    private static void request(Context c,Intent intent){if(!ForegroundWork.start(c,intent)){String key=intent.getStringExtra("source");status(key,"Android no permitió iniciar la actualización. Mantén la galería abierta y vuelve a pulsar Actualizar.");changed(c,key);}}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Catálogo privado",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String source){
        PendingIntent stop=PendingIntent.getService(this,21,route(new Intent(this,SyncService.class).setAction("pause"),source,"pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,20,route(new Intent(this,MainActivity.class),source,"open"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Tato Galería · "+SourceId.label(source)+" · catálogo").setContentText("Actualizando catálogo cifrado…").setVisibility(Notification.VISIBILITY_SECRET).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Pausar",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        lastStart=startId;final String key;
        try{key=SourceId.require(intent==null||intent.getStringExtra("source")==null?"tato":intent.getStringExtra("source"));}catch(IllegalArgumentException invalid){stopSelf(startId);return START_NOT_STICKY;}
        Job active=current;String shown=active==null?key:active.source;
        if(!ForegroundWork.promote(this,20,notification(shown))){status(key,"Android no permitió mantener la actualización activa. Reintenta con la galería abierta.");changed(this,key);if(jobs.isEmpty())stopSelf(startId);return START_NOT_STICKY;}
        if(intent!=null&&"pause".equals(intent.getAction())){
            Job paused=jobs.get(key);if(paused!=null){paused.paused=true;status(key,"Pausando actualización; se conserva el último lote confirmado…");changed(this,key);}
            else{status(key,"Sincronización pausada. Actualizar la continúa.");changed(this,key);if(jobs.isEmpty()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}}
            return START_NOT_STICKY;
        }
        if(jobs.containsKey(key))return START_NOT_STICKY;
        Job job=new Job(key,intent==null?0:intent.getIntExtra("channel",0));jobs.put(key,job);liveSources.add(key);running=true;
        status(key,"Actualización en cola…");changed(this,key);worker.execute(()->synchronize(job));return START_NOT_STICKY;
    }
    private void synchronize(Job job){
        current=job;String done;State state=null;int ending=4;
        try{
            state=State.get(this,job.source);if(state.maintenance||!state.unlocked)throw new IllegalStateException("Abre y desbloquea la galería para actualizar");
            if(job.paused||closed){finish(job,"Sincronización pausada. Actualizar la continúa.");return;}
            state.source.guard();update(job,"Descubriendo chats autorizados…",true);
            List<Source.Channel> channels=state.source.channels();if(channels.isEmpty())throw new IllegalStateException("telegram".equals(job.source)?"No hay chats autorizados. Abre Conector TG, inicia sesión y elige los chats que quieres consultar.":"No hay canales autorizados disponibles en TestTes");
            state.library.channels(channels);update(job,"Chats disponibles. Leyendo solo metadatos…",true);int total=0;
            for(Source.Channel channel:channels){
                if(job.selected>0&&channel.id!=job.selected)continue;
                job.channel=channel.id;activeChannels.put(job.source,channel.id);
                Library.SyncCursor cursor=state.library.beginSync(channel.id);update(job,cursor.incremental?"Buscando novedades del chat…":"Buscando historial antiguo del chat…",true);
                while(!job.paused&&!closed&&!state.maintenance){
                    Source.Page page=state.source.page(channel.id,cursor.cursor);
                    if(job.paused||closed||state.maintenance)break;
                    boolean complete=state.library.commitPage(channel.id,cursor,page);total+=page.items.size();
                    update(job,"Catálogo: "+total+" elementos revisados · sin descargar originales",false);
                    if(complete)break;
                }
                if(job.paused||closed||state.maintenance)break;
            }
            done=job.paused||closed||state.maintenance?"Sincronización pausada. Actualizar la continúa.":"Catálogo actualizado · originales bajo demanda";
        }catch(Exception failure){ending=3;done=ConnectionMessage.describe(failure,Profiles.owner(),job.source);}
        if(job.paused||closed)ending=2;
        if(state!=null&&job.channel>0&&ending!=4)try{Library.ChatStatus last=state.library.chatStatuses().get(job.channel);if(last!=null&&last.state==1)state.library.syncState(job.channel,ending);}catch(Exception ignored){}
        finish(job,done);
    }
    private void update(Job job,String text,boolean force){
        status(job.source,text);long now=SystemClock.elapsedRealtime();if(!force&&now-job.lastUpdate<700)return;job.lastUpdate=now;
        main.post(()->{if(closed||jobs.get(job.source)!=job)return;getSystemService(NotificationManager.class).notify(20,notification(job.source));changed(this,job.source);});
    }
    private void finish(Job job,String text){
        status(job.source,text);main.post(()->{
            if(closed)return;if(jobs.get(job.source)==job)jobs.remove(job.source);activeChannels.remove(job.source);liveSources.remove(job.source);running=!liveSources.isEmpty();if(current==job)current=null;changed(this,job.source);
            if(jobs.isEmpty()){if(stopSelfResult(lastStart))stopForeground(STOP_FOREGROUND_REMOVE);}
            else{Job next=jobs.values().iterator().next();getSystemService(NotificationManager.class).notify(20,notification(next.source));}
        });
    }
    @Override public void onTimeout(int startId,int type){for(Job job:jobs.values())job.paused=true;stopSelf();}
    @Override public void onDestroy(){closed=true;for(Job job:jobs.values()){job.paused=true;changed(this,job.source);}jobs.clear();activeChannels.clear();liveSources.clear();running=false;worker.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
    private static final class Job {
        final String source;final int selected;volatile boolean paused;volatile int channel;long lastUpdate;
        Job(String source,int selected){this.source=SourceId.require(source);this.selected=selected;}
    }
}
