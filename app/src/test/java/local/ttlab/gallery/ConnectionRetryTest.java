package local.ttlab.gallery;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ConnectionRetryTest {
    @Test public void interruptedProviderGetsOneFreshRead()throws Exception{
        AtomicInteger attempts=new AtomicInteger();
        assertEquals("fresh",ConnectionRetry.read(()->{if(attempts.incrementAndGet()==1)throw new IOException("E_TRANSFER_INCOMPLETE");return "fresh";}));
        assertEquals(2,attempts.get());
    }
    @Test public void failedRecoveryIsBounded()throws Exception{
        AtomicInteger attempts=new AtomicInteger();
        try{ConnectionRetry.read(()->{attempts.incrementAndGet();throw new IOException("E_CONNECTOR_UNAVAILABLE");});fail();}catch(IOException expected){}
        assertEquals(2,attempts.get());
    }
    @Test public void consentAndSelectionFailuresNeverRetry()throws Exception{
        for(Throwable denied:new Throwable[]{new SecurityException("E_OWNER_CONSENT_REQUIRED"),new IOException("binder",new SecurityException("E_ACCOUNT_SELECTION")),new IOException("E_LAB_MEDIA_UNAVAILABLE:E_MEDIA_CHANGED")})assertFalse(ConnectionRetry.retryable(denied));
    }
    @Test public void cancellationAndFullProtocolTimeoutNeverRetry(){
        assertFalse(ConnectionRetry.retryable(new InterruptedException()));
        assertFalse(ConnectionRetry.retryable(new InterruptedIOException()));
        assertFalse(ConnectionRetry.retryable(new IOException("E_LAB_MEDIA_UNAVAILABLE:E_RPC_TIMEOUT")));
        assertFalse(ConnectionRetry.retryable(new IllegalStateException("Respuesta demasiado grande")));
    }
    @Test public void interruptedWorkerDoesNotStartARecoveryRead()throws Exception{
        AtomicInteger attempts=new AtomicInteger();Thread.currentThread().interrupt();
        try{ConnectionRetry.read(()->{attempts.incrementAndGet();throw new IOException("closed provider");});fail();}
        catch(IOException expected){assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
        assertEquals(1,attempts.get());
    }
    @Test public void telegramFileWaitsAndTerminalErrorsNeverRepeatImmediately()throws Exception{
        for(String code:new String[]{"E_TELEGRAM_FLOOD_WAIT_120","E_TELEGRAM_TIMEOUT","E_TELEGRAM_CANCELLED","E_TELEGRAM_NO_THUMBNAIL","E_TELEGRAM_PEER_MISMATCH"}){
            AtomicInteger attempts=new AtomicInteger();try{ConnectionRetry.read(()->{attempts.incrementAndGet();throw new java.io.FileNotFoundException(code);});fail();}catch(java.io.FileNotFoundException expected){}
            assertEquals(code,1,attempts.get());
        }
    }
}
