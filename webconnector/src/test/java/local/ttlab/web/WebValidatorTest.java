package local.ttlab.web;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WebValidatorTest {
    private static final String MODIFIED = "Sun, 06 Nov 1994 08:49:37 GMT";
    private static final String SERVER_DATE = "Sun, 06 Nov 1994 08:51:38 GMT";

    @Test public void keepsStrongEtagsAndPrefersTheirConditionalHeader() {
        WebValidator validator = WebValidator.from("\"abc\"", MODIFIED, SERVER_DATE, true);
        assertEquals("If-Match", validator.requestHeader);
        assertEquals("ETag", validator.responseHeader);
        assertEquals("\"abc\"", validator.value);
    }

    @Test public void selectsTimestampForMissingOrWeakEtagsOnGelbooru() {
        for (String etag : new String[]{null, "W/\"weak\""}) {
            WebValidator validator = WebValidator.from(etag, MODIFIED, SERVER_DATE, true);
            assertEquals("If-Unmodified-Since", validator.requestHeader);
            assertEquals("Last-Modified", validator.responseHeader);
            assertEquals(MODIFIED, validator.value);
        }
    }

    @Test public void rejectsUnreliableAndMalformedTimestamps() {
        assertNull(WebValidator.from(null, MODIFIED, null, true));
        assertNull(WebValidator.from(null, MODIFIED, SERVER_DATE, false));
        assertNull(WebValidator.from(null, MODIFIED, MODIFIED, true));
        assertNull(WebValidator.from(null, MODIFIED, "Sun, 06 Nov 1994 08:50:36 GMT", true));
        assertNull(WebValidator.from(null, SERVER_DATE, MODIFIED, true));
        assertNull(WebValidator.from(null, MODIFIED + "\r\nX: x", SERVER_DATE, true));
        assertNull(WebValidator.from(null, "Sun, 32 Nov 1994 08:49:37 GMT", SERVER_DATE, true));
    }
}
