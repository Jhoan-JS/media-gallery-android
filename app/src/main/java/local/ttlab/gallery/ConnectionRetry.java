package local.ttlab.gallery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;

/** One fresh read can recover a killed provider; consent and validation failures never retry. */
final class ConnectionRetry {
    interface Read<T>{T get()throws Exception;}
    static <T>T read(Read<T> operation)throws Exception {
        try{return operation.get();}catch(Exception failed){if(Thread.currentThread().isInterrupted()||!retryable(failed))throw failed;}
        try{Thread.sleep(150);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        return operation.get();
    }
    static boolean retryable(Throwable failure){
        boolean temporary=false;
        for(int depth=0;failure!=null&&depth<10;depth++,failure=failure.getCause()){
            if(failure instanceof SecurityException||failure instanceof IllegalArgumentException||failure instanceof InterruptedException
                ||failure instanceof InterruptedIOException||failure instanceof CancellationException)return false;
            String message=String.valueOf(failure.getMessage());
            if(message.contains("E_OWNER_")||message.contains("E_TATO_CONSENT_REQUIRED")||message.contains("E_TATO_BINDING")||message.contains("E_SOURCE_ACCOUNT_CHANGED")||message.contains("E_MEDIA_CHANGED")
                ||message.contains("E_MISSING_MEDIA")||message.contains("E_NO_THUMBNAIL")||message.contains("E_RPC_TIMEOUT")
                ||message.contains("E_TELEGRAM_FLOOD_WAIT_")||message.contains("E_TELEGRAM_TIMEOUT")||message.contains("E_TELEGRAM_CANCELLED")
                ||message.contains("E_TELEGRAM_NO_THUMBNAIL")||message.contains("E_TELEGRAM_PEER_MISMATCH")
                ||message.contains("E_RPC_401")||message.contains("E_RPC_403"))return false;
            if(failure instanceof IOException||failure.getClass().getName().equals("android.os.DeadObjectException")
                ||message.contains("E_CONNECTOR_UNAVAILABLE")||message.contains("E_CONNECTOR_STARTUP")||message.contains("E_TATO_UNAVAILABLE"))temporary=true;
        }
        return temporary;
    }
}
