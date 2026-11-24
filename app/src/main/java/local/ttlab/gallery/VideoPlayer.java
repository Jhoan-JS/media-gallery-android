package local.ttlab.gallery;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.IOException;
import java.util.function.Consumer;

/** Streaming, seeks and samples belong to one thread; the UI only reads snapshots. */
@SuppressLint("UnsafeOptInUsageError")
final class VideoPlayer implements AutoCloseable {
    interface ErrorListener { boolean onError(VideoPlayer player,int what,int extra); }
    interface InfoListener { boolean onInfo(VideoPlayer player,int what,int extra); }
    private final HandlerThread thread=new HandlerThread("gallery-video-player");
    private final Handler owner,ui=new Handler(Looper.getMainLooper());
    private ExoPlayer nativePlayer;
    private volatile MediaDataSource source;
    private volatile boolean closed;
    private boolean ready,failed,seekPending,firstFramePending;
    private volatile Snapshot snapshot=new Snapshot(0,0,0,0,false,1);
    private volatile Consumer<VideoPlayer> onPrepared,onCompletion,onSeek;
    private volatile ErrorListener onError;
    private volatile InfoListener onInfo;
    private volatile Runnable onUnsupportedSpeed,onSize;
    private static final class Snapshot {
        final int duration,position,width,height;final boolean playing;final float speed;
        Snapshot(int d,int p,int w,int h,boolean playing,float speed){duration=d;position=p;width=w;height=h;this.playing=playing;this.speed=speed;}
    }
    VideoPlayer(Context context){
        thread.start();owner=new Handler(thread.getLooper());Context app=context.getApplicationContext();
        command(()->{
            // Bound encoded samples (including 15 s of history) instead of Media3's much larger default.
            DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(10000,20000,5000,4000)
                .setTargetBufferBytes(48*1024*1024).setPrioritizeTimeOverSizeThresholds(false).setBackBuffer(15000,true).build();
            nativePlayer=new ExoPlayer.Builder(app).setLooper(thread.getLooper()).setUsePlatformDiagnostics(false).setLoadControl(load).build();
            nativePlayer.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(closed)return;refresh();
                    if(state==Player.STATE_BUFFERING)info(MediaPlayer.MEDIA_INFO_BUFFERING_START);
                    if(state==Player.STATE_READY){
                        if(!ready){ready=true;emit(()->{if(onPrepared!=null)onPrepared.accept(VideoPlayer.this);});owner.post(poll);}
                        info(MediaPlayer.MEDIA_INFO_BUFFERING_END);if(firstFramePending){firstFramePending=false;info(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);}finishSeek();
                    }
                    if(state==Player.STATE_ENDED){info(MediaPlayer.MEDIA_INFO_BUFFERING_END);finishSeek();emit(()->{if(onCompletion!=null)onCompletion.accept(VideoPlayer.this);});}
                }
                @Override public void onVideoSizeChanged(VideoSize size){refresh();emit(()->{if(onSize!=null)onSize.run();});}
                @Override public void onRenderedFirstFrame(){if(ready)info(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);else firstFramePending=true;}
                @Override public void onPlayerError(PlaybackException failure){error(failure.errorCode);}
            });
        });
    }
    private final Runnable poll=new Runnable(){@Override public void run(){if(closed||!ready||failed)return;refresh();owner.postDelayed(this,250);}};
    private void refresh(){
        VideoSize size=nativePlayer.getVideoSize();long duration=nativePlayer.getDuration();int state=nativePlayer.getPlaybackState();
        snapshot=new Snapshot(duration==C.TIME_UNSET?0:(int)Math.min(Integer.MAX_VALUE,duration),(int)Math.min(Integer.MAX_VALUE,nativePlayer.getCurrentPosition()),size.width,size.height,
            nativePlayer.getPlayWhenReady()&&state!=Player.STATE_ENDED&&state!=Player.STATE_IDLE,nativePlayer.getPlaybackParameters().speed);
    }
    private void finishSeek(){if(seekPending){seekPending=false;refresh();emit(()->{if(onSeek!=null)onSeek.accept(this);});}}
    private void info(int what){emit(()->{if(onInfo!=null)onInfo.onInfo(this,what,0);});}
    private void emit(Runnable event){if(!closed)ui.post(()->{if(!closed)event.run();});}
    private void error(int code){if(closed||failed)return;failed=true;emit(()->{if(onError!=null)onError.onError(this,MediaPlayer.MEDIA_ERROR_UNKNOWN,code);});}
    private interface Operation {void run()throws Exception;}
    private void command(Operation operation){if(!closed)owner.post(()->{if(closed||failed)return;try{operation.run();}catch(Exception failure){error(MediaPlayer.MEDIA_ERROR_UNKNOWN);}});}
    void setOnPreparedListener(Consumer<VideoPlayer> listener){onPrepared=listener;}
    void setOnCompletionListener(Consumer<VideoPlayer> listener){onCompletion=listener;}
    void setOnSeekCompleteListener(Consumer<VideoPlayer> listener){onSeek=listener;}
    void setOnErrorListener(ErrorListener listener){onError=listener;}
    void setOnInfoListener(InfoListener listener){onInfo=listener;}
    void setOnSpeedUnsupportedListener(Runnable listener){onUnsupportedSpeed=listener;}
    void setOnVideoSizeChangedListener(Runnable listener){onSize=listener;}
    synchronized void setDataSource(MediaDataSource data){if(closed){closeSource(data);return;}source=data;command(()->{
        // This is the only factory: Media3 cannot open network URLs or plaintext files.
        nativePlayer.setMediaSource(new ProgressiveMediaSource.Factory(()->new EncryptedReader(data)).createMediaSource(MediaItem.fromUri("ttlab://local/video")));
    });}
    void setSurface(Surface surface){command(()->nativePlayer.setVideoSurface(surface));}
    void setVolume(float left,float right){command(()->nativePlayer.setVolume((left+right)/2));}
    void prepareAsync(){command(()->{if(source instanceof BufferedMediaSource)((BufferedMediaSource)source).setReadAhead(true);nativePlayer.prepare();});}
    void start(){command(()->{if(source instanceof BufferedMediaSource)((BufferedMediaSource)source).setReadAhead(true);nativePlayer.play();refresh();});}
    void pause(){command(()->{nativePlayer.pause();refresh();});}
    void seekTo(long position,int mode){command(()->{
        seekPending=true;nativePlayer.setSeekParameters(mode==MediaPlayer.SEEK_CLOSEST?SeekParameters.EXACT:SeekParameters.CLOSEST_SYNC);nativePlayer.seekTo(position);
        if(nativePlayer.getPlaybackState()==Player.STATE_READY)finishSeek();refresh();
    });}
    void setPlaybackParams(PlaybackParams params){float rate=PlayerControls.speed(params.getSpeed());command(()->{try{nativePlayer.setPlaybackParameters(new PlaybackParameters(rate,1));}catch(IllegalArgumentException unsupported){nativePlayer.setPlaybackParameters(PlaybackParameters.DEFAULT);emit(()->{if(onUnsupportedSpeed!=null)onUnsupportedSpeed.run();});}refresh();});}
    int getCurrentPosition(){return snapshot.position;}
    int getDuration(){return snapshot.duration;}
    int getVideoWidth(){return snapshot.width;}
    int getVideoHeight(){return snapshot.height;}
    boolean isPlaying(){return !closed&&snapshot.playing;}
    PlaybackParams getPlaybackParams(){return new PlaybackParams().allowDefaults().setPitch(1).setSpeed(snapshot.speed);}
    private static void closeSource(MediaDataSource data){if(data==null)return;if(data instanceof BufferedMediaSource){try{data.close();}catch(Exception ignored){}}else new Thread(()->{try{data.close();}catch(Exception ignored){}},"gallery-close-source").start();}
    @Override public synchronized void close(){
        if(closed)return;closed=true;closeSource(source);owner.removeCallbacksAndMessages(null);
        owner.post(()->{try{if(nativePlayer!=null)nativePlayer.release();}finally{thread.quitSafely();}});
    }
    void release(){close();}

    private static final class EncryptedReader extends BaseDataSource {
        private final MediaDataSource data;private long position,remaining;private boolean opened;private Uri uri;
        EncryptedReader(MediaDataSource data){super(true);this.data=data;}
        @Override public long open(DataSpec spec)throws IOException{
            transferInitializing(spec);long size=data.getSize();if(spec.position>size)throw new IOException("Posición fuera del video");
            position=spec.position;remaining=spec.length==C.LENGTH_UNSET?size-position:Math.min(spec.length,size-position);uri=spec.uri;opened=true;transferStarted(spec);return remaining;
        }
        @Override public int read(byte[] out,int offset,int length)throws IOException{
            if(length==0)return 0;if(remaining==0)return C.RESULT_END_OF_INPUT;int count=data.readAt(position,out,offset,(int)Math.min(length,remaining));
            if(count<0){remaining=0;return C.RESULT_END_OF_INPUT;}position+=count;remaining-=count;bytesTransferred(count);return count;
        }
        @Override public Uri getUri(){return uri;}
        // ExoPlayer closes/reopens its cursor on seeks; only the viewer closes the shared source.
        @Override public void close(){uri=null;if(opened){opened=false;transferEnded();}}
    }
}
