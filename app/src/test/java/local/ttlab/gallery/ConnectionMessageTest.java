package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ConnectionMessageTest {
    @Test public void telegramLabDoesNotAskForTatoOrTestTes(){
        String message=ConnectionMessage.describe(new SecurityException("E_TELEGRAM_LOGIN_REQUIRED"),false,"telegram");
        assertTrue(message.contains("Conector TG"));assertTrue(message.contains("inicia sesión"));assertFalse(message.contains("TestTes"));assertFalse(message.contains("Tato"));
    }
    @Test public void telegramConsentIsSeparateFromTransientFailure(){
        assertTrue(ConnectionMessage.describe(new SecurityException("E_TELEGRAM_CONSENT_REQUIRED"),true,"telegram").contains("Autorizar conexión"));
        String transientFailure=ConnectionMessage.describe(new java.io.IOException("E_CONNECTOR_UNAVAILABLE"),true,"telegram");
        assertTrue(transientFailure.contains("Reintentar"));assertFalse(transientFailure.contains("Autorizar conexión"));
    }
    @Test public void telegramRateLimitRetainsCheckpointWithoutReauthorization(){
        String message=ConnectionMessage.describe(new java.io.IOException("read",new IllegalStateException("E_TELEGRAM_FLOOD_WAIT_120")),true,"telegram");
        assertTrue(message.contains("120"));assertTrue(message.contains("guardado"));assertFalse(message.contains("Autorizar conexión"));
    }
    @Test public void telegramChangedAccountKeepsLibraryAndTatoMessagesStayTheSame(){
        assertTrue(ConnectionMessage.describe(new SecurityException("E_SOURCE_ACCOUNT_CHANGED"),false,"telegram").contains("cuenta original"));
        assertEquals(ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),true),ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),true,"tato"));
    }
    @Test public void consentIsNotRequestedForSelectionLogoutOrNetwork(){
        assertFalse(ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),true).contains("Autorizar conexión"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),true).contains("sigue guardada"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_LOGGED_OUT"),true).contains("inicia sesión"));
        assertTrue(ConnectionMessage.describe(new java.io.IOException("timeout"),true).contains("sin volver a vincular"));
    }
    @Test public void actualMissingConsentAndChangedAccountStayDistinct(){
        assertTrue(ConnectionMessage.describe(new java.io.IOException("read",new SecurityException("E_OWNER_CONSENT_REQUIRED")),true).contains("Autorizar conexión"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_SOURCE_ACCOUNT_CHANGED"),true).contains("cuenta original"));
        assertTrue(ConnectionMessage.describe(new SecurityException("unknown"),false).contains("TestTes"));
    }
    @Test public void stoppedProviderDoesNotSendTheUserThroughAuthorizationAgain(){
        String message=ConnectionMessage.describe(new java.io.IOException("E_CONNECTOR_UNAVAILABLE"),true);
        assertFalse(message.contains("abre Conector"));
        assertTrue(message.contains("Reintentar"));
    }
}
