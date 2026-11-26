package local.ttlab.gallery;

import android.os.Bundle;
import android.os.Handler;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaDataSource;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ViewerActivity extends SecureActivity {
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ThreadPoolExecutor frames=new java.util.concurrent.ThreadPoolExecutor(
        0,1,5,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),task->{
            Thread thread=new Thread(task,"gallery-local-frame");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    private ImageView scrubPreview;
    private boolean savedVideo;
    private boolean scrubAvailabilityRequested,scrubAvailable;
    private int scrubPosition=-1;
    private volatile int scrubGeneration;
    private Runnable pendingScrub;
    private Future<?> scrubWork,coverWork;
    private final Handler handler=new Handler();
    private final UndoBar undo=new UndoBar(this);
    private final java.util.LinkedHashMap<String,PersonalLibrary.Visit> recentVisits=new java.util.LinkedHashMap<>();private long recentVisitBytes;
    private ZoomImage image;private SurfaceView surface;private TextView title,status,currentTime,totalTime;private ImageButton play,more;private SeekBar seek;
    private LinearLayout transport,centerControls;private FrameLayout videoOverlay;private View gestureLayer,topBar,bottomBar;private TextView skipFeedback;
    private ProgressBar progress;private boolean wantsPlay=true,dragging,buffering,viewedQueued,awaitingSeek,playbackCompleted,favoriteValue,laterValue;private int resumePosition,currentChannel,currentId;private float videoZoom=1;private long lastCheckpoint;
    private final java.util.Map<String,Integer> flagEdits=new java.util.HashMap<>();private boolean archivedValue,videoMultiple;private ScaleGestureDetector videoPinch;private GestureDetector videoGestures,playGestures;private MediaFilter filter;private long[] keys;private Item before,after;
    private float playbackSpeed=1,videoVolume=1,touchX,touchY,touchLevel;private boolean boosted;private int levelSide;private long loadStarted,readyMillis=-1,frameMillis=-1;
    private PlaybackWatch watch=new PlaybackWatch();
    private volatile Item item;private VideoPlayer player;private MediaDataSource video;private boolean loading,loadFailed,prepared,surfaceReady;private volatile boolean closed;
    private android.os.CancellationSignal webPrepare, webNextPage;
    private Future<?> lookupWork,mediaWork,prepareWork,neighborWork,thumbnailWork;private volatile int thumbnailEpoch,epoch;private volatile boolean previewActive;private Runnable loadTimeout;
    private final Runnable tick=new Runnable(){public void run(){if(player!=null&&prepared&&!awaitingSeek){try{int position=player.getCurrentPosition();if(!dragging){seek.setProgress(position);elapsed(position);}observePlayback(position,player.isPlaying()&&!buffering&&!dragging);long now=android.os.SystemClock.elapsedRealtime();if(now-lastCheckpoint>=5000){persistPlayback();lastCheckpoint=now;}}catch(IllegalStateException ignored){}}if(!closed&&previewActive)handler.postDelayed(this,500);}};
    private final Runnable hideControls=()->{if(player!=null&&prepared&&!dragging&&previewActive&&!touchExploration())try{if(player.isPlaying())setControlsVisible(false);}catch(IllegalStateException ignored){}};
    private final Runnable hideSkipFeedback=()->skipFeedback.setVisibility(View.GONE);
    @Override public void onCreate(Bundle b){
        super.onCreate(b);if(state==null)return;
        currentChannel=b==null?getIntent().getIntExtra("channel",0):b.getInt("viewer-channel",getIntent().getIntExtra("channel",0));currentId=b==null?getIntent().getIntExtra("id",0):b.getInt("viewer-id",getIntent().getIntExtra("id",0));wantsPlay=b==null||b.getBoolean("viewer-play",true);
        try{Bundle snapshot=getIntent().getBundleExtra("viewer-filter");String savedFilter=b==null||snapshot!=null?null:state.preferences.getString("viewer-filter",null);filter=snapshot!=null?MediaFilter.from(snapshot):savedFilter!=null?SavedCollection.decode(savedFilter):defaultFilter();}catch(RuntimeException invalid){filter=defaultFilter();}
        keys=validKeys(b==null?getIntent().getLongArrayExtra("viewer-keys"):b.getLongArray("viewer-keys"));
        FrameLayout body=new FrameLayout(this);body.setBackgroundColor(android.graphics.Color.BLACK);image=new ZoomImage(this,direction->move(direction));image.setTag("viewer-image");image.setFocusable(true);image.setOnClickListener(v->setControlsVisible(videoOverlay.getVisibility()!=View.VISIBLE));body.addView(image,new FrameLayout.LayoutParams(-1,-1));
        surface=new SurfaceView(this);surface.setSecure(true);surface.setVisibility(View.GONE);body.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));body.addOnLayoutChangeListener((v,l,t,r,bt,ol,ot,or,ob)->{fitVideo();fitImage();});initVideoGestures();
        gestureLayer=new View(this){@Override public boolean onTouchEvent(MotionEvent e){return videoTouch(e,false);}@Override public boolean performClick(){super.performClick();return true;}};gestureLayer.setTag("video-gestures");gestureLayer.setFocusable(true);gestureLayer.setVisibility(View.GONE);gestureLayer.setOnClickListener(v->setControlsVisible(videoOverlay.getVisibility()!=View.VISIBLE));gestureLayer.setContentDescription("Video. Toca para mostrar controles; desliza para cambiar de archivo.");body.addView(gestureLayer,new FrameLayout.LayoutParams(-1,-1));
        videoOverlay=new FrameLayout(this);videoOverlay.setTag("video-overlay");body.addView(videoOverlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(4),dp(8),dp(4));bar.setMinimumHeight(dp(56));bar.setBackgroundColor(0xbc141715);topBar=bar;bar.addOnLayoutChangeListener((v,l,t,r,bt,ol,ot,or,ob)->{fitImage();fitVideo();});
        ImageButton back=GalleryUi.icon(this,R.drawable.ic_viewer_back,"Volver a la biblioteca");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setPadding(dp(8),0,dp(8),0);title=GalleryUi.text(this,"Archivo",17,getColor(R.color.gallery_foreground));title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.addView(title);heading.addView(GalleryUi.text(this,SourceId.label(state.key),12,getColor(R.color.gallery_muted)));bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));more=GalleryUi.icon(this,R.drawable.ic_gallery_more,"Opciones del archivo");more.setOnClickListener(v->showOptions());bar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));videoOverlay.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){surfaceReady=true;startVideo();}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){surfaceReady=false;if(player!=null&&prepared&&!awaitingSeek)try{resumePosition=PlaybackProgress.resume(player.getCurrentPosition(),player.getDuration(),playbackCompleted);}catch(IllegalStateException ignored){}persistPlayback();releaseVideo();}});
        centerControls=new LinearLayout(this);centerControls.setTag("video-center-controls");centerControls.setGravity(Gravity.CENTER);centerControls.setVisibility(View.GONE);play=GalleryUi.icon(this,R.drawable.ic_viewer_play,"Reproducir");play.setEnabled(false);play.setPadding(dp(20),dp(20),dp(20),dp(20));play.setBackground(GalleryUi.background(0xc0000000,dp(36)));centerControls.addView(play,new LinearLayout.LayoutParams(dp(72),dp(72)));videoOverlay.addView(centerControls,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));play.setOnClickListener(v->togglePlay());play.setOnTouchListener((v,e)->videoTouch(e,true));
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setBackgroundColor(0xbc141715);bottomBar=footer;footer.addOnLayoutChangeListener((v,l,t,r,bt,ol,ot,or,ob)->{fitImage();fitVideo();});transport=new LinearLayout(this);transport.setGravity(Gravity.CENTER_VERTICAL);transport.setPadding(dp(16),0,dp(16),dp(4));transport.setVisibility(View.GONE);
        currentTime=GalleryUi.text(this,"0:00",12,getColor(R.color.gallery_foreground));currentTime.setPadding(dp(4),0,dp(4),0);transport.addView(currentTime,new LinearLayout.LayoutParams(-2,-2));seek=new SeekBar(this);seek.setContentDescription("Posición del video");seek.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_primary)));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_primary)));seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_outline)));transport.addView(seek,new LinearLayout.LayoutParams(0,dp(48),1));totalTime=GalleryUi.text(this,"0:00",12,getColor(R.color.gallery_foreground));transport.addView(totalTime,new LinearLayout.LayoutParams(-2,-2));footer.addView(transport);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){dragging=true;watch.resetSample();handler.removeCallbacks(hideControls);showScrubPreview(s.getProgress());}
            public void onStopTrackingTouch(SeekBar s){dragging=false;clearScrubPreview();watch.resetSample();if(player!=null&&prepared&&!awaitingSeek)try{playbackCompleted=false;player.seekTo(s.getProgress(),MediaPlayer.SEEK_CLOSEST_SYNC);}catch(IllegalStateException error){showError();}showControls();}
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user){elapsed(p);if(dragging)showScrubPreview(p);}}
        });
        status=GalleryUi.text(this,"Cargando archivo…",12,getColor(R.color.gallery_muted));status.setMaxLines(4);status.setPadding(dp(16),dp(4),dp(16),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);footer.addView(status);
        videoOverlay.addView(footer,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        scrubPreview=new ImageView(this);
        scrubPreview.setTag("scrub-preview-frame");
        scrubPreview.setBackground(GalleryUi.background(0xe6101717,dp(8)));
        scrubPreview.setClipToOutline(true);
        scrubPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scrubPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrubPreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams previewLayout=new FrameLayout.LayoutParams(dp(144),dp(90),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        previewLayout.bottomMargin=dp(64);
        videoOverlay.addView(scrubPreview,previewLayout);
        progress=new ProgressBar(this);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_primary)));progress.setContentDescription("Cargando archivo");body.addView(progress,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));skipFeedback=GalleryUi.text(this,"",16,getColor(R.color.gallery_foreground));skipFeedback.setTag("video-skip-feedback");skipFeedback.setGravity(Gravity.CENTER);skipFeedback.setPadding(dp(16),dp(12),dp(16),dp(12));skipFeedback.setBackground(GalleryUi.background(0xc0000000,dp(12)));skipFeedback.setVisibility(View.GONE);skipFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(skipFeedback,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        View.AccessibilityDelegate navigation=new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);if(after!=null||canExtendWeb())info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"Archivo siguiente"));if(before!=null)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"Archivo anterior"));info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.getId(),"Opciones del archivo"));}@Override public boolean performAccessibilityAction(View host,int action,Bundle args){if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){move(1);return true;}if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){move(-1);return true;}if(action==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.getId()){showOptions();return true;}return super.performAccessibilityAction(host,action,args);}};image.setAccessibilityDelegate(navigation);gestureLayer.setAccessibilityDelegate(navigation);content(body);
    }
    private void initVideoGestures(){
        videoPinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoomVideo(VideoGesture.scale(videoZoom,detector.getScaleFactor()));return true;}});
        videoPinch.setQuickScaleEnabled(false);
        videoGestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public void onLongPress(MotionEvent e){boost();}@Override public boolean onSingleTapConfirmed(MotionEvent e){gestureLayer.performClick();return true;}@Override public boolean onDoubleTap(MotionEvent e){int action=VideoGesture.doubleTap(e.getX()/Math.max(1,gestureLayer.getWidth()));if(action==0)zoomVideo(videoZoom>1.01f?1:2);else skip(action*10000);return true;}@Override public boolean onScroll(MotionEvent first,MotionEvent last,float dx,float dy){if(videoZoom<=1.01f)return false;float x=surface.getWidth()*(videoZoom-1)/2,y=surface.getHeight()*(videoZoom-1)/2;surface.setTranslationX(Math.max(-x,Math.min(x,surface.getTranslationX()-dx)));surface.setTranslationY(Math.max(-y,Math.min(y,surface.getTranslationY()-dy)));return true;}@Override public boolean onFling(MotionEvent first,MotionEvent last,float vx,float vy){if(first==null||last==null)return false;int direction=ViewerGesture.direction(last.getX()-first.getX(),last.getY()-first.getY(),vx,getResources().getDisplayMetrics().density,!videoMultiple&&videoZoom<=1.01f);if(direction==0)return false;move(direction);return true;}});
        playGestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onSingleTapConfirmed(MotionEvent e){play.performClick();return true;}@Override public boolean onDoubleTap(MotionEvent e){zoomVideo(videoZoom>1.01f?1:2);return true;}});
    }
    private boolean videoTouch(MotionEvent event,boolean onPlay){
        int action=event.getActionMasked();GestureDetector detector=onPlay?playGestures:videoGestures;
        if(action==MotionEvent.ACTION_DOWN){videoMultiple=false;levelSide=0;touchX=event.getX();touchY=event.getY();}
        videoPinch.onTouchEvent(event);
        if(event.getPointerCount()>1){videoMultiple=true;stopBoost();cancelDetector(detector,event);}
        else if(!videoMultiple){
            if(!onPlay&&action==MotionEvent.ACTION_MOVE&&videoZoom<=1.01f&&!boosted){float dx=event.getX()-touchX,dy=event.getY()-touchY;if(levelSide==0&&Math.abs(dy)>dp(24)&&Math.abs(dy)>Math.abs(dx)*1.5f){levelSide=PlayerControls.side(touchX/Math.max(1,gestureLayer.getWidth()));if(levelSide!=0){touchLevel=levelSide<0?brightness():videoVolume;cancelDetector(detector,event);}}if(levelSide!=0){adjustLevel(levelSide,PlayerControls.level(touchLevel,-dy/Math.max(1,gestureLayer.getHeight())*1.5f));return true;}}
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){if(boosted||levelSide!=0){stopBoost();levelSide=0;cancelDetector(detector,event);return true;}}
            detector.onTouchEvent(event);
        }
        if(action==MotionEvent.ACTION_CANCEL)stopBoost();return true;
    }
    private void cancelDetector(GestureDetector detector,MotionEvent event){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);detector.onTouchEvent(cancel);cancel.recycle();}
    private android.media.PlaybackParams params(float rate){return new android.media.PlaybackParams().allowDefaults().setPitch(1).setSpeed(rate);}
    private void effectiveSpeed(float rate){if(player==null||!prepared||awaitingSeek)return;try{if(player.isPlaying())player.setPlaybackParams(params(rate));}catch(IllegalArgumentException|IllegalStateException unsupported){Toast.makeText(this,"Esta velocidad no está disponible para este video",Toast.LENGTH_SHORT).show();}}
    private void startAtSpeed(){try{player.setPlaybackParams(params(playbackSpeed));player.start();}catch(IllegalArgumentException unsupported){player.start();Toast.makeText(this,"El reproductor usará su velocidad compatible",Toast.LENGTH_SHORT).show();}}
    private void boost(){if(videoMultiple||levelSide!=0||videoZoom>1.01f||player==null||!prepared||awaitingSeek)return;try{if(!player.isPlaying())return;boosted=true;watch.resetSample();effectiveSpeed(2);feedback("2× mientras mantienes pulsado");handler.removeCallbacks(hideControls);}catch(IllegalStateException unavailable){}}
    private void stopBoost(){if(!boosted)return;boosted=false;watch.resetSample();effectiveSpeed(playbackSpeed);if(skipFeedback!=null)skipFeedback.setVisibility(View.GONE);if(previewActive&&videoOverlay!=null&&videoOverlay.getVisibility()==View.VISIBLE)showControls();}
    private void chooseSpeed(){stopBoost();float[] speeds={.5f,.75f,1,1.25f,1.5f,1.75f,2};GalleryUi.menu(this,"Velocidad · "+String.valueOf(playbackSpeed).replace('.',',')+"×",new String[]{"0,5×","0,75×","1×","1,25×","1,5×","1,75×","2×"},n->{playbackSpeed=speeds[n];watch.resetSample();effectiveSpeed(playbackSpeed);final float selected=playbackSpeed;final State origin=state;origin.database.execute(()->{try{origin.library.setSetting("player-speed",Float.toString(selected));}catch(Exception failure){runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,"No se pudo guardar la velocidad",Toast.LENGTH_SHORT).show();});}});showControls();});}
    private float brightness(){float value=getWindow().getAttributes().screenBrightness;return value>=0?value:android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS,128)/255f;}
    private void adjustLevel(int side,float value){if(side<0){value=Math.max(.05f,value);WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.screenBrightness=Math.max(.05f,value);getWindow().setAttributes(attributes);}else{videoVolume=value;if(player!=null)try{player.setVolume(value,value);}catch(IllegalStateException ignored){}}feedback((side<0?"Brillo ":"Volumen del video ")+Math.round(value*100)+" %");}
    private void feedback(String value) { feedback(value, 1200); }

    private void feedback(String value, int millis) {
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        skipFeedback.setLayoutParams(position);
        skipFeedback.setText(value);
        skipFeedback.setContentDescription(value);
        skipFeedback.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSkipFeedback);
        if (millis > 0) handler.postDelayed(hideSkipFeedback, millis);
    }
    private void levelOptions(){GalleryUi.menu(this,"Controles del video",new String[]{"Brillo de esta ventana","Volumen del video","Cómo usar los gestos"},n->{if(n==2){android.app.AlertDialog guide=new SecureDialogs.Builder(this).setTitle("Gestos del video").setMessage("Desliza horizontalmente para cambiar de archivo. Doble toque central amplía/restablece; laterales ±10 segundos. Pellizca para ajustar el zoom. Sin zoom, desliza verticalmente a izquierda para brillo o a derecha para volumen. Mantén pulsado el video en reproducción para acelerar a 2×; al soltar vuelve a tu velocidad.").setPositiveButton("Cerrar",null).create();trackPrivateDialog(guide);guide.show();return;}SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(Math.round((n==0?brightness():videoVolume)*100));slider.setContentDescription(n==0?"Brillo de esta ventana":"Volumen del video");slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){if(user)adjustLevel(n==0?-1:1,value/100f);}});android.app.AlertDialog dialog=new SecureDialogs.Builder(this).setTitle(n==0?"Brillo de esta ventana":"Volumen del video").setView(slider).setPositiveButton("Cerrar",null).create();trackPrivateDialog(dialog);dialog.show();});}
    private void zoomVideo(float factor){videoZoom=Math.max(1,Math.min(4,factor));surface.setScaleX(videoZoom);surface.setScaleY(videoZoom);if(videoZoom==1){surface.setTranslationX(0);surface.setTranslationY(0);}}
    private void showOptions(){
        if(!previewActive||!state.unlocked)return;
        stopBoost();
        Item opened=item;
        if(opened==null){
            if(loadFailed)GalleryUi.menu(this,"Archivo",new String[]{"Reintentar","Cerrar visor"},n->{if(n==0)load();else finish();});
            else GalleryUi.menu(this,"Archivo",new String[]{"Cerrar visor"},n->finish());
            return;
        }
        java.util.List<String> options=new java.util.ArrayList<>(java.util.Arrays.asList(
            favoriteValue?"Quitar de Favoritos":"Añadir a Favoritos",laterValue?"Quitar de Ver después":"Ver después",
            SourceId.web(state.key)?"Etiquetas del sitio":UiSource.isLocal(this)?"Eliminar copia importada":"Guardar sin conexión","Organizar",opened.video()?"Reproducción":"Ajustar imagen","Información"));
        if(loadFailed)options.add(0,"Reintentar");
        boolean retryShown=loadFailed;
        GalleryUi.menu(this,"Archivo",options.toArray(new String[0]),index->{
            if(item!=opened)return;
            if(retryShown&&index==0){load();return;}
            switch(index-(retryShown?1:0)){
                case 0:toggleFlag("favorite");break;
                case 1:toggleFlag("later");break;
                case 2:if(SourceId.web(state.key))showWebTags(opened);else if(UiSource.isLocal(this))confirmRemoveCopy(opened);else DownloadService.start(this,state.key,opened);break;
                case 3:organize(opened);break;
                case 4:if(opened.video())playbackOptions(opened);else image.fit();break;
                case 5:showInformation(opened);break;
                default:break;
            }
        });
    }
    private void showWebTags(Item opened) {
        final State origin = state;
        final int generation = epoch;
        origin.database.execute(() -> {
            if (!current(generation, opened)) return;
            String saved = origin.webCatalogue.tags(opened.id).trim();
            String[] tags = saved.isEmpty() ? new String[0] : saved.split("\\s+");
            String[] options = java.util.Arrays.copyOf(tags, Math.min(80, tags.length));
            runOnUiThread(() -> {
                if (!current(generation, opened)) return;
                if (options.length == 0) {
                    feedback("Sin etiquetas disponibles");
                    return;
                }
                GalleryUi.menu(this, "Etiquetas del sitio", options,
                        selected -> showWebTagActions(options[selected], opened, generation));
            });
        });
    }

    private void showWebTagActions(String tag, Item opened, int generation) {
        if (!current(generation, opened)) return;
        GalleryUi.menu(this, tag, new String[]{"Añadir a la búsqueda", "Excluir de la búsqueda",
                "Buscar solo esta etiqueta"}, action -> {
            if (!current(generation, opened)) return;
            String base = action == 2 ? "" : WebCatalogue.tagsForQuery(state, filter.webQuery);
            String result = WebTags.edit(base, tag, action == 1);
            if (result == null) {
                feedback("Usa hasta ocho etiquetas e incluye al menos una.", 4200);
                return;
            }
            setResult(RESULT_OK, new Intent().putExtra("web-query", result));
            finish();
        });
    }
    private void organize(Item opened){
        String archive=archivedValue?"Mostrar en Biblioteca":"Archivar";
        String[] options=opened.video()?new String[]{"Álbumes","Etiquetas","Marcadores","Portada del video",archive}:new String[]{"Álbumes","Etiquetas",archive};
        GalleryUi.menu(this,"Organizar",options,n->{
            if(item!=opened)return;
            if(n<2)PersonalUi.memberships(this,state,java.util.Collections.singletonList(opened),n,()->{});
            else if(n==options.length-1)toggleFlag("archive");
            else if(n==2)showBookmarks();
            else showCoverOptions();
        });
    }
    private void playbackOptions(Item opened){
        GalleryUi.menu(this,"Reproducción",new String[]{"Desde el principio","Velocidad de reproducción","Brillo, volumen y gestos"},n->{
            if(item!=opened)return;
            if(n==0)restartVideo();else if(n==1)chooseSpeed();else levelOptions();
        });
    }
    private void showInformation(Item opened){
        android.app.AlertDialog info=new SecureDialogs.Builder(this).setTitle(opened.label())
            .setMessage(SourceId.label(state.key)+"\n"+metadata()+(readyMillis>=0?"\nPreparación observada: "+readyMillis+" ms":"")+(frameMillis>=0?"\nPrimer cuadro observado: "+frameMillis+" ms":""))
            .setPositiveButton("Cerrar",null).create();
        trackPrivateDialog(info);info.show();
    }
    private void confirmRemoveCopy(Item opened){
        State origin=state;long unlock=unlockEpoch();
        android.app.AlertDialog confirm=new SecureDialogs.Builder(this).setTitle("¿Eliminar la copia importada?")
            .setMessage("Se eliminará la copia cifrada y sus marcas de Archivos propios. El archivo de origen seguirá en su ubicación.")
            .setNegativeButton("Conservar",null).setPositiveButton("Eliminar copia",(d,w)->{
                if(item!=opened||!biometricCurrent(unlock))return;
                origin.database.execute(()->{
                    try{
                        LocalFiles.remove(origin,opened);
                        runOnUiThread(()->{if(item==opened&&!isDestroyed())finish();});
                    }catch(Exception failure){
                        runOnUiThread(()->{if(biometricCurrent(unlock))Toast.makeText(this,"No se pudo quitar la copia por completo. Vuelve a abrir la biblioteca.",Toast.LENGTH_LONG).show();});
                    }
                });
            }).create();
        trackPrivateDialog(confirm);confirm.show();
    }
    private void showScrubPreview(int milliseconds) {
        Item opened=item;
        if(!dragging||!prepared||opened==null||!opened.video()||!previewActive||!state.unlocked)return;
        int interval=Math.max(200,Math.min(2000,seek.getMax()/60));
        int position=Math.max(0,milliseconds/interval*interval);
        if(position==scrubPosition)return;
        scrubPosition=position;
        int request=scrubGeneration, generation=epoch;
        State origin=state;
        if(!scrubAvailabilityRequested){
            scrubAvailabilityRequested=true;
            origin.database.execute(()->{
                boolean available;
                try{available=origin.library.offline(opened);}catch(RuntimeException unavailable){return;}
                runOnUiThread(()->{
                    if(!scrubbing(request,generation,opened))return;
                    savedVideo=scrubAvailable=available;
                    scheduleScrub(origin,opened,request,generation);
                });
            });
            return;
        }
        scheduleScrub(origin,opened,request,generation);
    }

    private boolean scrubbing(int request,int generation,Item opened) {
        return request==scrubGeneration&&current(generation,opened)&&dragging&&prepared;
    }

    private void scheduleScrub(State origin,Item opened,int request,int generation) {
        if(!scrubAvailable||pendingScrub!=null||scrubWork!=null||!scrubbing(request,generation,opened))return;
        pendingScrub=()->{
            pendingScrub=null;
            if(!scrubbing(request,generation,opened))return;
            if(coverWork!=null&&!coverWork.isDone()){scrubPosition=-1;return;}
            int position=scrubPosition;
            try {
                scrubWork=frames.submit(()->{
                    Bitmap decoded=null;
                    try {
                        decoded=origin.media.videoFrame(opened,position,192,false,
                            ()->request!=scrubGeneration||!current(generation,opened));
                    } catch(java.io.IOException unavailable) { /* Time and seeking remain available. */ }
                    Bitmap frame=decoded;
                    runOnUiThread(()->{
                        if(!scrubbing(request,generation,opened)){if(frame!=null)frame.recycle();return;}
                        scrubWork=null;
                        if(frame!=null){
                            scrubPreview.setImageBitmap(frame);
                            scrubPreview.setVisibility(View.VISIBLE);
                        }
                        if(position!=scrubPosition)scheduleScrub(origin,opened,request,generation);
                    });
                });
            } catch(java.util.concurrent.RejectedExecutionException busy) {scrubPosition=-1;}
        };
        handler.postDelayed(pendingScrub,100);
    }

    private void cancelFrame(Future<?> work) {
        if(work==null)return;
        work.cancel(true);
        if(work instanceof Runnable)frames.remove((Runnable)work);
    }

    private void clearScrubPreview() {
        scrubGeneration++;
        scrubPosition=-1;
        scrubAvailabilityRequested=scrubAvailable=false;
        if(pendingScrub!=null)handler.removeCallbacks(pendingScrub);
        pendingScrub=null;
        cancelFrame(scrubWork);
        scrubWork=null;
        if(scrubPreview!=null){
            scrubPreview.setImageDrawable(null);
            scrubPreview.setVisibility(View.GONE);
        }
    }

    private void showCoverOptions() {
        Item opened=item;
        if(opened==null||!opened.video())return;
        int generation=epoch;
        GalleryUi.menu(this,"Portada del video",new String[]{"Guardar fotograma actual","Restaurar portada automática"},choice->{
            if(!current(generation,opened))return;
            if(choice==0&&(!prepared||player==null||awaitingSeek||frameMillis<0)){
                Toast.makeText(this,"Espera a que aparezca el video",Toast.LENGTH_SHORT).show();
                return;
            }
            if(coverWork!=null&&!coverWork.isDone())return;
            int position=choice==0?Math.max(0,Math.min(player.getCurrentPosition(),player.getDuration()-1)):0;
            boolean offline=savedVideo;
            State origin=state;
            clearScrubPreview();
            try {
                coverWork=frames.submit(()->{
                    java.util.function.BooleanSupplier stopped=()->!current(generation,opened);
                    Bitmap frame=null;
                    try {
                        if(choice==0){
                            frame=origin.media.videoFrame(opened,position,384,true,stopped);
                            origin.media.saveCover(opened,frame,stopped);
                        }else origin.media.resetCover(opened,stopped);
                        coverMessage(generation,opened,choice==0?"Portada guardada":"Portada automática restaurada");
                    } catch(Exception unavailable) {
                        String detail=choice==1?"No se pudo restaurar la portada":offline
                            ?"No se pudo guardar este fotograma. Prueba otro momento del video."
                            :"No se pudo guardar este fotograma. Guarda el video sin conexión e inténtalo de nuevo.";
                        coverMessage(generation,opened,detail);
                    } finally {if(frame!=null)frame.recycle();}
                });
            } catch(java.util.concurrent.RejectedExecutionException busy) {
                coverMessage(generation,opened,"Espera un momento y vuelve a intentarlo");
            }
        });
    }

    private void coverMessage(int generation,Item opened,String message) {
        runOnUiThread(()->{if(current(generation,opened))Toast.makeText(this,message,Toast.LENGTH_LONG).show();});
    }

    private void showBookmarks(){
        if(item==null||!item.video()||player==null||!prepared||awaitingSeek){Toast.makeText(this,"Espera a que el video esté listo",Toast.LENGTH_SHORT).show();return;}
        final Item opened=item;final int generation=epoch;final VideoPlayer currentPlayer=player;
        PersonalUi.bookmarks(this,state,opened,currentPlayer.getCurrentPosition(),currentPlayer.getDuration(),position->{if(current(generation,opened)&&player==currentPlayer&&prepared){watch.resetSample();playbackCompleted=false;currentPlayer.seekTo(position,MediaPlayer.SEEK_CLOSEST);seek.setProgress(position);elapsed(position);showControls();}});
    }
    boolean personalItemCurrent(Item opened){return opened!=null&&item==opened&&previewActive&&!closed&&state!=null&&state.unlocked;}
    private void refreshPersonalFlags(){
        final Item opened=item;final State origin=state;final int generation=epoch;if(opened==null)return;
        origin.database.execute(()->{try{Library.Flags flags=origin.library.flags(opened);runOnUiThread(()->{if(current(generation,opened)){favoriteValue=flags.favorite;laterValue=flags.later;archivedValue=flags.archived;resolveNeighbors();}});}catch(Exception ignored){}});
    }
    private void recordRecent(Item opened,State origin){
        long now=System.currentTimeMillis();
        if(filter.recent||filter.sort==6){
            PersonalLibrary.Visit visit=new PersonalLibrary.Visit(opened,now),previous=recentVisits.remove(opened.key());recentVisits.put(opened.key(),visit);if(previous!=null)recentVisitBytes-=64+previous.revision.length()*2L;recentVisitBytes+=64+visit.revision.length()*2L;
            // shortcut: unusually long revisions may lose their oldest pending recency update; use encrypted staging if this 2 MiB budget is routinely reached.
            while(recentVisits.size()>10000||recentVisitBytes>2L*1024*1024){String first=recentVisits.keySet().iterator().next();PersonalLibrary.Visit removed=recentVisits.remove(first);recentVisitBytes-=64+removed.revision.length()*2L;}
        }else origin.database.execute(()->{try{origin.library.personal.recordOpened(opened,now);}catch(Exception ignored){}});
    }
    private void flushRecentVisits(){
        if(recentVisits.isEmpty()||state==null)return;final State origin=state;final java.util.List<PersonalLibrary.Visit> visits=new java.util.ArrayList<>(recentVisits.values());recentVisits.clear();recentVisitBytes=0;
        origin.database.execute(()->{try{origin.library.personal.recordVisits(visits);}catch(Exception ignored){}});
    }
    private MediaFilter defaultFilter(){MediaFilter f=new MediaFilter();f.channel=currentChannel;return f;}
    private long[] validKeys(long[] proposed){if(proposed==null||proposed.length==0||proposed.length>96)return null;java.util.HashSet<Long> unique=new java.util.HashSet<>();boolean includes=false;for(long key:proposed){if((int)(key>>>32)<=0||(int)key<=0||!unique.add(key))return null;if(key==key(currentChannel,currentId))includes=true;}return includes?proposed.clone():null;}
    private static long key(int channel,int id){return (long)channel<<32|(id&0xffffffffL);}
    private boolean current(int generation,Item opened){return !closed&&previewActive&&state.unlocked&&epoch==generation&&item==opened;}
    @Override protected void onResume(){super.onResume();previewActive=true;if(state!=null&&state.unlocked)load();}
    @Override void onUnlocked(){if(previewActive)load();}
    private void load(){
        if(closed||!previewActive||state==null||!state.unlocked)return;
        undo.clear();persistPlayback();cancelRequest();releaseVideo();loadStarted=android.os.SystemClock.elapsedRealtime();readyMillis=frameMillis=-1;final int generation=epoch;final int channel=currentChannel,id=currentId;final State origin=state;
        item=null;flagEdits.clear();before=after=null;openedQueued=false;viewedQueued=false;watch=new PlaybackWatch();loading=true;loadFailed=false;awaitingSeek=false;image.clear();surface.setVisibility(View.GONE);image.setVisibility(View.VISIBLE);gestureLayer.setVisibility(View.GONE);centerControls.setVisibility(View.GONE);transport.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);skipFeedback.setVisibility(View.GONE);title.setText("Archivo");status.setMaxLines(2);status.setText("Cargando archivo…");status.setVisibility(View.VISIBLE);showControls();
        loadTimeout=()->{if(!closed&&epoch==generation&&loading){if(webPrepare!=null)webPrepare.cancel();cancel(mediaWork);cancel(prepareWork);showError();}};handler.postDelayed(loadTimeout,WebSource.GELBOORU.equals(origin.key)?45000:30000);
        final android.os.CancellationSignal remote=SourceId.web(origin.key)?new android.os.CancellationSignal():null;
        webPrepare=remote;
        lookupWork=(remote==null?origin.database:worker).submit(()->{try{
            Item row=origin.library.get(channel,id);
            if(row!=null&&!row.video()&&WebSource.GELBOORU.equals(origin.key)){
                Bitmap preview=origin.media.memoryPreview(row);
                if(preview!=null)runOnUiThread(()->{
                    if(!closed&&previewActive&&origin.unlocked&&epoch==generation)image.show(preview);
                });
            }
            Item found=remote==null||row==null?row:((WebSource)origin.source).prepare(row,remote);
            if(!filter.stackKey.isEmpty()&&(found==null||!filter.stackKey.equals(found.contentKey)||!filter.stackMime.equals(found.mime)||filter.stackSize!=found.size)){
                runOnUiThread(()->{if(!closed&&previewActive&&origin.unlocked&&epoch==generation){showError();status.setText("El archivo cambió y ya no pertenece a esta pila. Vuelve a la lista para actualizarla.");}});return;
            }
            if(found==null)throw new java.io.IOException("Archivo no disponible");Library.Flags flags=origin.library.flags(found);float speed;try{speed=PlayerControls.speed(Float.parseFloat(origin.library.setting("player-speed","1")));}catch(NumberFormatException invalid){speed=1;}final float selectedSpeed=speed;boolean viewed=found.video()&&origin.library.viewed(found),offline=found.video()&&origin.library.offline(found);
            runOnUiThread(()->{if(closed||!previewActive||!origin.unlocked||epoch!=generation)return;item=found;savedVideo=offline;playbackSpeed=selectedSpeed;favoriteValue=flags.favorite;laterValue=flags.later;archivedValue=flags.archived;resumePosition=PlaybackProgress.resume(flags.position,flags.duration,flags.completed);playbackCompleted=flags.completed;viewedQueued=viewed;title.setText(found.label());status.setText(metadata());resolveNeighbors();if(found.video()){image.setVisibility(View.GONE);surface.setVisibility(View.VISIBLE);gestureLayer.setVisibility(View.VISIBLE);centerControls.setVisibility(View.VISIBLE);transport.setVisibility(View.VISIBLE);seek.setVisibility(View.VISIBLE);startVideo();}else{fitImage();decodeImage(found,generation,origin);}});
        }catch(Exception error){runOnUiThread(()->{if(!closed&&previewActive&&epoch==generation){showError(error);}});}});
    }
    private void decodeImage(Item opened,int generation,State origin){
        mediaWork=worker.submit(()->{try{
            if(!current(generation,opened))return;
            try{Bitmap preview=origin.media.availableThumbnail(opened);runOnUiThread(()->{if(current(generation,opened))image.show(preview);});}catch(Exception absent){}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream input=origin.media.input(opened)){BitmapFactory.decodeStream(input,null,options);}if(!current(generation,opened)||Thread.currentThread().isInterrupted())return;
            if(options.outWidth<=0||options.outHeight<=0)throw new java.io.IOException("Formato no compatible");options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2560||options.outHeight/options.inSampleSize>2560)options.inSampleSize*=2;options.inJustDecodeBounds=false;
            Bitmap bitmap;try(InputStream input=origin.media.input(opened)){bitmap=BitmapFactory.decodeStream(input,null,options);}if(bitmap==null)throw new java.io.IOException("Formato no compatible");
            if(!current(generation,opened)){bitmap.recycle();return;}try{origin.media.remember(opened,bitmap);}catch(Exception thumbnailOnly){}
            runOnUiThread(()->{if(current(generation,opened)){image.show(bitmap);readyMillis=android.os.SystemClock.elapsedRealtime()-loadStarted;status.setVisibility(View.GONE);loading=false;clearLoadTimeout();progress.setVisibility(View.GONE);notifyOpened();}else bitmap.recycle();});
        }catch(Exception error){runOnUiThread(()->{if(current(generation,opened))showError(error);});}});
    }
    private void resolveNeighbors(){
        if(item==null||closed)return;cancel(neighborWork);final Item opened=item;final int generation=epoch;final State origin=state;final MediaFilter snapshot=filter.copy();final long[] sequence=keys;before=after=null;
        neighborWork=origin.database.submit(()->{try{Item left,right;if(sequence==null){left=origin.library.neighbor(opened,snapshot,-1);right=origin.library.neighbor(opened,snapshot,1);}else{int index=-1;for(int n=0;n<sequence.length;n++)if(sequence[n]==key(opened.channel,opened.id)){index=n;break;}left=sequenceNeighbor(origin,sequence,index,-1);right=sequenceNeighbor(origin,sequence,index,1);}final Item previousItem=left,nextItem=right;runOnUiThread(()->{if(current(generation,opened)){before=previousItem;after=nextItem;}});}catch(Exception failure){runOnUiThread(()->{if(current(generation,opened)){loadFailed=true;status.setText(metadata()+" · No se pudo consultar el siguiente archivo. Reintenta la carga.");}});}});
    }
    private static Item sequenceNeighbor(State origin,long[] sequence,int index,int direction)throws Exception{if(index<0)return null;for(int n=index+direction;n>=0&&n<sequence.length;n+=direction){long key=sequence[n];Item found=origin.library.get((int)(key>>>32),(int)key);if(found!=null)return found;}return null;}
    private void move(int direction) {
        if (!previewActive || state == null || !state.unlocked) return;
        Item destination = direction < 0 ? before : after;
        if (destination != null) goTo(destination);
        else if (direction > 0 && canExtendWeb()) extendWebFeed();
    }

    private void goTo(Item destination) {
        currentChannel = destination.channel;
        currentId = destination.id;
        wantsPlay = true;
        getIntent().putExtra("channel", currentChannel).putExtra("id", currentId);
        load();
    }

    private boolean canExtendWeb() {
        return state != null && item != null && state.webCatalogue != null && keys == null
                && filter != null && filter.webQuery != null && !filter.webQuery.isEmpty();
    }

    private void extendWebFeed() {
        final State origin = state;
        if (webNextPage != null || origin.database.isShutdown()) return;
        final Item opened = item;
        final int generation = epoch;
        final MediaFilter snapshot = filter.copy();
        final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        webNextPage = signal;
        feedback("Buscando más publicaciones…", 0);
        origin.database.execute(() -> {
            Item destination = null;
            String message = "";
            try {
                signal.throwIfCanceled();
                destination = origin.library.neighbor(opened, snapshot, 1);
                int page = origin.webCatalogue.next(snapshot.webQuery);
                for (int n = 0; destination == null && page > 0 && n < WebCatalogue.PAGE_SCAN_LIMIT; n++) {
                    WebCatalogue.fetchPage(origin, snapshot.webQuery, page, signal);
                    destination = origin.library.neighbor(opened, snapshot, 1);
                    page = origin.webCatalogue.next(snapshot.webQuery);
                }
                if (destination == null) message = page > 0 ? "Desliza de nuevo para buscar más."
                        : "Llegaste al final.";
            } catch (Exception failure) {
                message = WebSource.message(failure) + " Desliza para reintentar.";
            }
            final Item next = destination;
            final String notice = message;
            runOnUiThread(() -> {
                if (webNextPage != signal) return;
                webNextPage = null;
                if (!current(generation, opened)) return;
                if (next != null) goTo(next);
                else feedback(notice, 4200);
            });
        });
    }

    private void cancelWebPage() {
        if (webNextPage == null) return;
        webNextPage.cancel();
        webNextPage = null;
        skipFeedback.setVisibility(View.GONE);
    }
    private void toggleFlag(String field){
        if(item==null||!previewActive||!state.unlocked)return;final Item opened=item;final State origin=state;final int generation=epoch;final boolean old=field.equals("favorite")?favoriteValue:field.equals("later")?laterValue:archivedValue;final int edit=flagEdits.getOrDefault(field,0)+1;flagEdits.put(field,edit);if(field.equals("favorite"))favoriteValue=!old;else if(field.equals("later"))laterValue=!old;else archivedValue=!old;
        origin.database.execute(()->{try{String action=field.equals("favorite")?(old?"unfavorite":"favorite"):field.equals("later")?(old?"unlater":"later"):(old?"unarchive":"archive");PersonalLibrary.Change change=origin.library.personal.change(java.util.Collections.singletonList(opened),action);runOnUiThread(()->{if(current(generation,opened)){resolveNeighbors();if(flagEdits.getOrDefault(field,0)==edit)undo.show(origin,change,"Marca guardada",96,this::refreshPersonalFlags);}});}catch(Exception error){runOnUiThread(()->{if(current(generation,opened)&&flagEdits.getOrDefault(field,0)==edit){if(field.equals("favorite"))favoriteValue=old;else if(field.equals("later"))laterValue=old;else archivedValue=old;status.setVisibility(View.VISIBLE);status.setText("No se pudo guardar la marca. Reintenta desde Opciones.");showControls();}});}});
    }
    private void startVideo(){
        if(!surfaceReady||item==null||!item.video()||player!=null||closed||!previewActive||!state.unlocked)return;
        final Item opened=item;final int generation=epoch;final State origin=state;final VideoPlayer candidate=new VideoPlayer(this);final MediaDataSource source=origin.media.video(opened);player=candidate;video=source;progress.setVisibility(View.VISIBLE);
        candidate.setOnSpeedUnsupportedListener(()->Toast.makeText(this,"El reproductor usará su velocidad compatible",Toast.LENGTH_SHORT).show());candidate.setOnVideoSizeChangedListener(()->{if(current(generation,opened)&&player==candidate)fitVideo();});
        candidate.setOnPreparedListener(p->{if(!current(generation,opened)||player!=p)return;prepared=true;buffering=false;int duration=p.getDuration();watch.duration(duration);watch.resetSample();seek.setMax(duration);totalTime.setText(time(duration));totalTime.setContentDescription("Duración total: "+time(duration));fitVideo();
            int target=PlaybackProgress.resume(resumePosition,duration,playbackCompleted);resumePosition=target;awaitingSeek=target>0;
            if(awaitingSeek){p.setOnSeekCompleteListener(seeked->{if(!current(generation,opened)||player!=seeked||!awaitingSeek)return;awaitingSeek=false;seeked.setOnSeekCompleteListener(null);finishPrepared(seeked,opened,generation);});p.seekTo(target,MediaPlayer.SEEK_CLOSEST);}else finishPrepared(p,opened,generation);
        });
        candidate.setOnCompletionListener(p->{if(current(generation,opened)&&player==p){boosted=false;observePlayback(p.getDuration(),!buffering);playbackCompleted=true;persistPlayback();wantsPlay=false;playIcon(false);status.setText(metadata()+" · Terminado");showControls();}});
        candidate.setOnErrorListener((p,what,extra)->{if(current(generation,opened)&&player==p)showError();return true;});
        candidate.setOnInfoListener((p,what,extra)->{if(!current(generation,opened)||player!=p)return false;if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){buffering=true;watch.resetSample();progress.setVisibility(View.VISIBLE);}else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END||what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){buffering=false;watch.resetSample();if(!awaitingSeek)progress.setVisibility(View.GONE);cacheVideoThumbnail();if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){if(frameMillis<0)frameMillis=android.os.SystemClock.elapsedRealtime()-loadStarted;notifyOpened();}}return false;});
        final Surface output=surface.getHolder().getSurface();
        prepareWork=worker.submit(()->{try{if(source instanceof BufferedMediaSource)((BufferedMediaSource)source).prepare();if(!current(generation,opened)||player!=candidate)return;candidate.setDataSource(source);candidate.setSurface(output);candidate.setVolume(videoVolume,videoVolume);candidate.prepareAsync();}catch(Exception error){runOnUiThread(()->{if(current(generation,opened)&&player==candidate)showError(error);});}});
    }
    private void finishPrepared(VideoPlayer p,Item opened,int generation){if(!current(generation,opened)||player!=p)return;resumePosition=0;readyMillis=android.os.SystemClock.elapsedRealtime()-loadStarted;loading=false;status.setVisibility(View.GONE);clearLoadTimeout();play.setEnabled(true);progress.setVisibility(View.GONE);seek.setProgress(p.getCurrentPosition());elapsed(p.getCurrentPosition());status.setText(metadata()+(p.getCurrentPosition()>=3000?" · Continuando desde "+time(p.getCurrentPosition()):""));lastCheckpoint=android.os.SystemClock.elapsedRealtime();handler.removeCallbacks(tick);handler.post(tick);if(wantsPlay){playbackCompleted=false;startAtSpeed();playIcon(true);}else playIcon(false);showControls();handler.postDelayed(()->{if(current(generation,opened))cacheVideoThumbnail();},1000);}
    private void togglePlay(){stopBoost();if(player==null||!prepared||awaitingSeek)return;try{watch.resetSample();if(player.isPlaying()){wantsPlay=false;player.pause();playIcon(false);persistPlayback();}else{wantsPlay=true;if(playbackCompleted){restartVideo();return;}startAtSpeed();playIcon(true);}showControls();}catch(IllegalStateException error){showError();}}
    private void restartVideo(){if(player==null||!prepared||awaitingSeek)return;try{final VideoPlayer p=player;final Item opened=item;final int generation=epoch;boolean playing=p.isPlaying();p.pause();watch.resetSample();playbackCompleted=false;awaitingSeek=true;play.setEnabled(false);p.setOnSeekCompleteListener(seeked->{if(!current(generation,opened)||player!=seeked)return;seeked.setOnSeekCompleteListener(null);awaitingSeek=false;play.setEnabled(true);seek.setProgress(0);elapsed(0);persistPlayback();if(playing||wantsPlay){startAtSpeed();playIcon(true);}else playIcon(false);showControls();});p.seekTo(0,MediaPlayer.SEEK_CLOSEST);status.setText(metadata());}catch(IllegalStateException error){showError();}}
    private void persistPlayback(){
        if(item==null||!item.video()||player==null||!prepared||awaitingSeek)return;
        try{final Item opened=item;final State origin=state;final int generation=epoch;final int duration=player.getDuration(),position=PlaybackProgress.checkpoint(player.getCurrentPosition(),duration);final boolean completed=playbackCompleted;if(duration<=0)return;
            origin.database.execute(()->{try{origin.library.savePlayback(opened,position,duration,completed);}catch(Exception error){runOnUiThread(()->{if(current(generation,opened))status.setText(metadata()+" · No se pudo guardar el progreso. Reintenta.");});}});
        }catch(IllegalStateException ignored){}
    }
    private boolean openedQueued;
    private void notifyOpened(){if(openedQueued||closed||!previewActive||!state.unlocked||item==null)return;openedQueued=true;final Item opened=item;final State origin=state;final int generation=epoch;setResult(RESULT_OK,new Intent().putExtra("source",origin.key).putExtra("channel",opened.channel).putExtra("id",opened.id));recordRecent(opened,origin);worker.execute(()->{try{if(current(generation,opened))origin.source.opened(opened);}catch(Exception unavailable){runOnUiThread(()->{if(current(generation,opened))status.setText(metadata()+" · No se pudo confirmar la lectura en "+SourceId.label(origin.key));});}});}
    private void cacheVideoThumbnail(){
        if(closed||!previewActive||!state.unlocked||item==null||!item.video()||(thumbnailWork!=null&&!thumbnailWork.isDone()))return;
        final Item opened=item;final State origin=state;final int generation=epoch,thumbnailGeneration=thumbnailEpoch;
        thumbnailWork=origin.thumbnails.submit(()->{try{origin.media.rememberVideoThumbnail(opened,false,()->!current(generation,opened)||thumbnailGeneration!=thumbnailEpoch);}catch(Exception thumbnailOnly){}});
    }
    private void cancelVideoThumbnail(){thumbnailEpoch++;if(thumbnailWork!=null)thumbnailWork.cancel(true);thumbnailWork=null;}
    private void skip(int milliseconds){if(player==null||!prepared||awaitingSeek)return;try{watch.resetSample();playbackCompleted=false;int target=(int)Math.max(0,Math.min(player.getDuration(),(long)player.getCurrentPosition()+milliseconds));player.seekTo(target,MediaPlayer.SEEK_CLOSEST_SYNC);elapsed(target);seek.setProgress(target);skipFeedback.setText(milliseconds<0?"−10 s":"+10 s");skipFeedback.setContentDescription(milliseconds<0?"Retroceder 10 segundos":"Adelantar 10 segundos");FrameLayout.LayoutParams feedback=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|(milliseconds<0?Gravity.LEFT:Gravity.RIGHT));feedback.leftMargin=feedback.rightMargin=dp(28);skipFeedback.setLayoutParams(feedback);skipFeedback.setVisibility(View.VISIBLE);handler.removeCallbacks(hideSkipFeedback);handler.postDelayed(hideSkipFeedback,900);showControls();}catch(IllegalStateException error){showError();}}
    private void observePlayback(int position,boolean playing){
        boolean eligible=watch.sample(android.os.SystemClock.elapsedRealtime(),position,playing&&previewActive&&state.unlocked,actualSpeed());
        if(!eligible||viewedQueued||item==null)return;viewedQueued=true;final Item watched=item;final State origin=state;final int generation=epoch;
        origin.database.execute(()->{try{origin.library.markViewed(watched);}catch(Exception unavailable){runOnUiThread(()->{if(current(generation,watched))viewedQueued=false;});}});
    }
    private float actualSpeed(){try{return player==null?1:PlayerControls.speed(player.getPlaybackParams().getSpeed());}catch(IllegalStateException unavailable){return 1;}}
    private boolean touchExploration(){android.view.accessibility.AccessibilityManager a=getSystemService(android.view.accessibility.AccessibilityManager.class);return a!=null&&a.isTouchExplorationEnabled();}
    private void showControls(){setControlsVisible(true);}
    private void setControlsVisible(boolean visible){
        if(videoOverlay==null)return;handler.removeCallbacks(hideControls);videoOverlay.setVisibility(visible?View.VISIBLE:View.GONE);gestureLayer.setContentDescription(visible?"Ocultar controles del video":"Mostrar controles del video");
        if(visible&&item!=null&&item.video()&&!dragging)handler.postDelayed(hideControls,3000);
    }
    private void fitImage(){if(item==null||item.video()||topBar==null||bottomBar==null)return;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)image.getLayoutParams();int top=topBar.getHeight(),bottom=bottomBar.getHeight();if(p.topMargin!=top||p.bottomMargin!=bottom){p.topMargin=top;p.bottomMargin=bottom;image.setLayoutParams(p);}}
    private void fitVideo(){if(centerControls!=null&&topBar!=null&&bottomBar!=null)centerControls.setTranslationY((topBar.getHeight()-bottomBar.getHeight())/2f);if(player==null||!prepared)return;int w=player.getVideoWidth(),h=player.getVideoHeight();FrameLayout parent=(FrameLayout)surface.getParent();if(w<=0||h<=0||parent.getWidth()==0||parent.getHeight()==0)return;float scale=Math.min((float)parent.getWidth()/w,(float)parent.getHeight()/h);int width=(int)(w*scale),height=(int)(h*scale);if(surface.getLayoutParams().width!=width||surface.getLayoutParams().height!=height)surface.setLayoutParams(new FrameLayout.LayoutParams(width,height,Gravity.CENTER));}
    private String metadata(){String value=android.text.format.Formatter.formatFileSize(this,item.size);if(item.width>0&&item.height>0)value+=" · "+item.width+" × "+item.height;if(item.date>0)value+=" · "+java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(item.date*1000));return value;}
    private void elapsed(int position){String value=time(position);currentTime.setText(value);currentTime.setContentDescription("Tiempo transcurrido: "+value);}
    private void playIcon(boolean playing){surface.setKeepScreenOn(playing&&previewActive);play.setImageResource(playing?R.drawable.ic_viewer_pause:R.drawable.ic_viewer_play);play.setContentDescription(playing?"Pausar":"Reproducir");}
    private static String time(int milliseconds){int seconds=Math.max(0,milliseconds/1000),hours=seconds/3600,minutes=seconds/60%60;return hours>0?String.format(java.util.Locale.getDefault(),"%d:%02d:%02d",hours,minutes,seconds%60):String.format(java.util.Locale.getDefault(),"%d:%02d",seconds/60,seconds%60);}
    private void showError(){showError(null);}
    private void showError(Throwable cause){
        if(closed)return;
        Throwable failure=cause!=null?cause:video instanceof BufferedMediaSource?((BufferedMediaSource)video).failure():null;
        persistPlayback();cancelRequest();releaseVideo();loading=false;loadFailed=true;progress.setVisibility(View.GONE);
        status.setMaxLines(4);status.setVisibility(View.VISIBLE);
        status.setText(SourceId.web(state.key)?WebSource.message(failure):ConnectionMessage.describe(failure, state.key));
        showControls();handler.removeCallbacks(hideControls);
    }
    private static void cancel(Future<?> work){if(work!=null)work.cancel(true);}
    private void clearLoadTimeout(){if(loadTimeout!=null)handler.removeCallbacks(loadTimeout);loadTimeout=null;}
    private void cancelRequest(){stopBoost();epoch++;cancelWebPage();if(webPrepare!=null){webPrepare.cancel();webPrepare=null;}cancelFrame(coverWork);coverWork=null;cancel(lookupWork);cancel(mediaWork);cancel(prepareWork);cancel(neighborWork);clearLoadTimeout();cancelVideoThumbnail();handler.removeCallbacks(hideSkipFeedback);}
    private void releaseVideo(){clearScrubPreview();boosted=false;levelSide=0;if(surface!=null){surface.setKeepScreenOn(false);surface.setScaleX(1);surface.setScaleY(1);surface.setTranslationX(0);surface.setTranslationY(0);}videoZoom=1;cancel(prepareWork);cancelVideoThumbnail();watch.resetSample();buffering=false;dragging=false;VideoPlayer old=player;MediaDataSource data=video;player=null;video=null;prepared=false;awaitingSeek=false;if(play!=null)play.setEnabled(false);handler.removeCallbacks(tick);handler.removeCallbacks(hideControls);if(data instanceof BufferedMediaSource)try{data.close();}catch(Exception ignored){}if(old!=null)old.close();if(data!=null&&!(data instanceof BufferedMediaSource))new Thread(()->{try{data.close();}catch(Exception ignored){}},"CloseGalleryVideoSource").start();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&previewActive&&state!=null&&state.unlocked&&getWindow().getDecorView().hasWindowFocus()){
            int code=event.getKeyCode();View focus=getCurrentFocus();boolean media=focus==null||focus==image||focus==gestureLayer;
            if(code==KeyEvent.KEYCODE_PAGE_DOWN||code==KeyEvent.KEYCODE_MEDIA_NEXT){move(1);return true;}if(code==KeyEvent.KEYCODE_PAGE_UP||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS){move(-1);return true;}
            if(item!=null&&item.video()){
                if(code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||(event.isShiftPressed()&&code==KeyEvent.KEYCODE_DPAD_RIGHT)){skip(10000);return true;}if(code==KeyEvent.KEYCODE_MEDIA_REWIND||(event.isShiftPressed()&&code==KeyEvent.KEYCODE_DPAD_LEFT)){skip(-10000);return true;}
                if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||(media&&code==KeyEvent.KEYCODE_SPACE)){togglePlay();return true;}
            }
            if(media&&(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_LEFT)){move(code==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);return true;}
            if(media&&(code==KeyEvent.KEYCODE_PLUS||code==KeyEvent.KEYCODE_EQUALS||code==KeyEvent.KEYCODE_MINUS)){if(item!=null&&item.video())zoomVideo(code==KeyEvent.KEYCODE_MINUS?1:2);else if(code==KeyEvent.KEYCODE_MINUS)image.fit();else image.toggleZoom();return true;}
        }return super.dispatchKeyEvent(event);
    }
    @Override void onCovered(){cancelWebPage();undo.clear();clearScrubPreview();cancelFrame(coverWork);coverWork=null;}
    @Override protected void onPause(){undo.clear();persistPlayback();flushRecentVisits();previewActive=false;cancelRequest();loading=false;releaseVideo();super.onPause();}
    @Override protected void onStop(){
        if(state!=null&&state.liveId!=null&&!isFinishing()&&!isChangingConfigurations()){
            State.endWebLive(state);
            finish();
        }
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out){if(state==null||state.liveId!=null){super.onSaveInstanceState(out);return;}persistPlayback();out.putInt("viewer-channel",currentChannel);out.putInt("viewer-id",currentId);out.putBoolean("viewer-play",wantsPlay);if(filter!=null&&filter.stackKey.isEmpty())try{state.preferences.edit().putString("viewer-filter",SavedCollection.encode(filter)).apply();}catch(RuntimeException unavailable){}out.remove("viewer-filter");if(keys!=null)out.putLongArray("viewer-keys",keys);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){undo.clear();closed=true;cancelRequest();handler.removeCallbacksAndMessages(null);releaseVideo();if(image!=null)image.clear();frames.shutdownNow();worker.shutdownNow();super.onDestroy();}

    private static final class ZoomImage extends ImageView {
        private final android.graphics.Matrix matrix=new android.graphics.Matrix();
        private final ScaleGestureDetector pinch;private final GestureDetector gestures;
        private float scale=1,minimum=1,lastX,lastY;private boolean multiple,pageGesture;
        ZoomImage(android.content.Context c,java.util.function.IntConsumer navigate){super(c);setScaleType(ScaleType.MATRIX);setContentDescription("Imagen. Pellizca o toca dos veces para ampliar y restablecer; desliza para cambiar de archivo.");
            pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float next=Math.max(minimum,Math.min(minimum*8,scale*d.getScaleFactor()));matrix.postScale(next/scale,next/scale,d.getFocusX(),d.getFocusY());scale=next;setImageMatrix(matrix);return true;}});
            pinch.setQuickScaleEnabled(false);
            gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onSingleTapConfirmed(MotionEvent e){performClick();return true;}@Override public boolean onDoubleTap(MotionEvent e){pageGesture=false;toggleZoom();return true;}@Override public boolean onFling(MotionEvent first,MotionEvent last,float vx,float vy){if(first==null||last==null)return false;int direction=ViewerGesture.direction(last.getX()-first.getX(),last.getY()-first.getY(),vx,getResources().getDisplayMetrics().density,pageGesture&&!multiple&&scale<=minimum*1.01f);if(direction==0)return false;navigate.accept(direction);return true;}});
        }
        void show(Bitmap bitmap){
            // A full-resolution replacement must retain a zoom or pan made on its preview.
            float zoom=scale/minimum;float[] center={getWidth()/2f,getHeight()/2f};android.graphics.Matrix inverse=new android.graphics.Matrix();
            boolean replacing=getDrawable()!=null&&matrix.invert(inverse);if(replacing){inverse.mapPoints(center);center[0]/=getDrawable().getIntrinsicWidth();center[1]/=getDrawable().getIntrinsicHeight();}
            setImageBitmap(bitmap);fit();
            if(replacing&&zoom>1.01f&&getWidth()>0&&getHeight()>0){float w=getDrawable().getIntrinsicWidth(),h=getDrawable().getIntrinsicHeight();scale=minimum*zoom;matrix.setScale(scale,scale);matrix.postTranslate(getWidth()/2f-center[0]*w*scale,getHeight()/2f-center[1]*h*scale);setImageMatrix(matrix);}
        }
        void clear(){setImageDrawable(null);matrix.reset();scale=minimum=1;}
        void toggleZoom(){if(getDrawable()==null)return;if(scale>minimum*1.1f){fit();return;}float next=minimum*2;matrix.postScale(next/scale,next/scale,getWidth()/2f,getHeight()/2f);scale=next;setImageMatrix(matrix);}
        private void fit(){if(getDrawable()==null||getWidth()==0||getHeight()==0)return;float w=getDrawable().getIntrinsicWidth(),h=getDrawable().getIntrinsicHeight();minimum=Math.min(getWidth()/w,getHeight()/h);scale=minimum;matrix.reset();matrix.postScale(scale,scale);matrix.postTranslate((getWidth()-w*scale)/2,(getHeight()-h*scale)/2);setImageMatrix(matrix);}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fit();}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){multiple=false;pageGesture=scale<=minimum*1.01f;}if(e.getPointerCount()>1)multiple=true;pinch.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(!pinch.isInProgress()&&e.getPointerCount()==1&&scale>minimum){matrix.postTranslate(e.getX()-lastX,e.getY()-lastY);setImageMatrix(matrix);}lastX=e.getX();lastY=e.getY();}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
