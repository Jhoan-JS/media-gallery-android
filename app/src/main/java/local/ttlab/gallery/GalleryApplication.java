package local.ttlab.gallery;
import android.app.*;import android.os.Bundle;import android.content.*;
/** UI authentication ends when the app leaves the foreground; background jobs retain their existing encrypted access. */
public final class GalleryApplication extends Application implements Application.ActivityLifecycleCallbacks {
 private int visible;
 @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){State.unlocked=false;}},new IntentFilter(Intent.ACTION_SCREEN_OFF),android.os.Build.VERSION.SDK_INT>=33?Context.RECEIVER_NOT_EXPORTED:0);}
 @Override public void onActivityStarted(Activity a){visible++;}
 @Override public void onActivityStopped(Activity a){visible=Math.max(0,visible-1);if(visible==0&&!a.isChangingConfigurations())State.unlocked=false;}
 public void onActivityCreated(Activity a,Bundle b){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
}
