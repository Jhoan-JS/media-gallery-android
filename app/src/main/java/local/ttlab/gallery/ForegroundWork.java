package local.ttlab.gallery;
import android.content.Context;
import android.content.Intent;

/** A platform-denied start is recoverable UI state, not an uncaught activity exception. */
final class ForegroundWork {
    static boolean start(Context c,Intent intent){return attempt(()->c.startForegroundService(intent));}
    static boolean promote(android.app.Service service,int id,android.app.Notification notification){return attempt(()->service.startForeground(id,notification));}
    static boolean attempt(Runnable operation){try{operation.run();return true;}catch(IllegalStateException|SecurityException denied){return false;}}
}
