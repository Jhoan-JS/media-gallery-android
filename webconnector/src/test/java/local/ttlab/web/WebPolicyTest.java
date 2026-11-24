package local.ttlab.web;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class WebPolicyTest {
    @Test public void validatesMediaHostsAndKeepsCredentialsOutOfMediaUrls() throws Exception {
        assertEquals("api-cdn-mp4.rule34.xxx", WebPolicy.media("https://api-cdn-mp4.rule34.xxx/images/a.mp4").getHost());
        for (String address : new String[]{"http://api-cdn.rule34.xxx/a.jpg", "https://api-cdn.rule34.xxx.evil.example/a.jpg",
                "https://api-cdn.rule34.xxx@evil.example/a.jpg", "https://api-cdn.rule34.xxx:8443/a.jpg",
                "https://api-cdn.rule34.xxx/a.jpg?api_key=secret", "https://127.0.0.1/a.jpg"}) {
            assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(address));
        }
    }

    @Test public void acceptsOnlyExactByteRanges() throws Exception {
        assertEquals(5000, WebPolicy.contentRange(206, "bytes 200-299/5000", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(200, null, 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(206, "bytes 0-99/5000", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(206, "bytes 200-299/220", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(416, "bytes */5000", 200, 100));
    }

    @Test public void searchNeverBecomesAFullCatalogueScan() {
        assertEquals("blue_sky -clouds rating:safe", WebPolicy.query("  blue_sky   -clouds "));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query(" "));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query("-clouds"));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query("blue_sky rating:explicit"));
    }
}
