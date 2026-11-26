package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ConnectionMessageTest {
    @Test public void telegramLoginGuidanceIsSourceSpecific(){
        String message=ConnectionMessage.describe(new SecurityException("E_TELEGRAM_LOGIN_REQUIRED"),"telegram");
        assertTrue(message.contains(BuildConfig.ISOLATED_CLIENT?"Configurar conexión":"Conector TG"));assertTrue(message.contains("inicia sesión"));assertFalse(message.contains("TestTes"));assertFalse(message.contains("Tato"));
    }
    @Test public void telegramConsentIsSeparateFromTransientFailure(){
        assertTrue(ConnectionMessage.describe(new SecurityException("E_TELEGRAM_CONSENT_REQUIRED"),"telegram").contains("Autorizar conexión"));
        String transientFailure=ConnectionMessage.describe(new java.io.IOException("E_CONNECTOR_UNAVAILABLE"),"telegram");
        assertTrue(transientFailure.contains("Reintentar"));assertFalse(transientFailure.contains("Autorizar conexión"));
    }
    @Test public void telegramRateLimitRetainsCheckpointWithoutReauthorization(){
        String message=ConnectionMessage.describe(new java.io.IOException("read",new IllegalStateException("E_TELEGRAM_FLOOD_WAIT_120")),"telegram");
        assertTrue(message.contains("120"));assertFalse(message.contains("actualizar"));assertFalse(message.contains("Autorizar conexión"));
    }
    @Test public void changedTelegramMediaDoesNotRequestReauthorization(){
        String message=ConnectionMessage.describe(new java.io.IOException("E_MEDIA_CHANGED"),"telegram");
        assertTrue(message.contains("archivo cambió"));assertFalse(message.contains("Autorizar conexión"));
    }
    @Test public void telegramChangedAccountKeepsLibraryAndTatoMessagesStayTheSame(){
        assertTrue(ConnectionMessage.describe(new SecurityException("E_SOURCE_ACCOUNT_CHANGED"),"telegram").contains("cuenta original"));
    }
    @Test public void consentIsNotRequestedForSelectionLogoutOrNetwork(){
        assertFalse(ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),"tato").contains("Autorizar conexión"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_ACCOUNT_SELECTION"),"tato").contains("sigue guardada"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_LOGGED_OUT"),"tato").contains("inicia sesión"));
        assertTrue(ConnectionMessage.describe(new java.io.IOException("timeout"),"tato").contains("sin volver a vincular"));
    }
    @Test public void actualMissingConsentAndChangedAccountStayDistinct(){
        assertTrue(ConnectionMessage.describe(new java.io.IOException("read",new SecurityException("E_TATO_CONSENT_REQUIRED")),"tato").contains("Autorizar conexión"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_SOURCE_ACCOUNT_CHANGED"),"tato").contains("cuenta original"));
        assertTrue(ConnectionMessage.describe(new SecurityException("E_TATO_BINDING"),"tato").contains("Autorizar conexión"));
    }
    @Test public void stoppedProviderDoesNotSendTheUserThroughAuthorizationAgain(){
        String message=ConnectionMessage.describe(new java.io.IOException("E_CONNECTOR_UNAVAILABLE"),"tato");
        assertFalse(message.contains("abre Conector"));
        assertTrue(message.contains("Reintentar"));
    }
}
