package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SourceIdTest {
    @Test public void existingTatoPathsStayUnchanged(){assertEquals("library.db",SourceId.database("tato"));assertEquals("vault",SourceId.vault("tato"));assertEquals("library-ui",SourceId.preferences("tato"));}
    @Test public void telegramHasDistinctCatalogueCacheAndPreferences(){assertEquals("library-telegram.db",SourceId.database("telegram"));assertEquals("vault-telegram",SourceId.vault("telegram"));assertEquals("library-ui-telegram",SourceId.preferences("telegram"));}
    @Test public void webVaultUsesAValidAndSeparateStoragePath() {
        assertTrue(SourceId.vault(WebSource.KEY).matches("[a-z-]+"));
        assertNotEquals(SourceId.database("tato"), SourceId.database(WebSource.KEY));
        assertNotEquals(SourceId.database("telegram"), SourceId.database(WebSource.KEY));
        assertTrue(SourceId.vault(WebSource.GELBOORU).matches("[a-z-]+"));
        assertNotEquals(SourceId.vault(WebSource.KEY), SourceId.vault(WebSource.GELBOORU));
        assertNotEquals(SourceId.database(WebSource.KEY), SourceId.database(WebSource.GELBOORU));
        assertNotEquals(SourceId.preferences(WebSource.KEY), SourceId.preferences(WebSource.GELBOORU));
    }
    @Test public void untrustedSourceCannotChooseStoragePath(){for(String key:new String[]{null,"", "../tato", "Telegram", "owner", "telegram/../vault"}){try{SourceId.require(key);fail("accepted "+key);}catch(IllegalArgumentException expected){}}}
}
