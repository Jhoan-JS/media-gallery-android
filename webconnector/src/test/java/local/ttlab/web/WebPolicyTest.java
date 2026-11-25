package local.ttlab.web;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class WebPolicyTest {
    @Test public void validatesMediaHostsAndKeepsCredentialsOutOfMediaUrls() throws Exception {
        assertEquals("api-cdn-mp4.rule34.xxx", WebPolicy.media(WebSite.RULE34, "https://api-cdn-mp4.rule34.xxx/images/a.mp4").getHost());
        for (String address : new String[]{"http://api-cdn.rule34.xxx/a.jpg", "https://api-cdn.rule34.xxx.evil.example/a.jpg",
                "https://api-cdn.rule34.xxx@evil.example/a.jpg", "https://api-cdn.rule34.xxx:8443/a.jpg",
                "https://api-cdn.rule34.xxx/a.jpg?api_key=secret", "https://127.0.0.1/a.jpg"}) {
            assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.RULE34, address));
        }
    }

    @Test public void isolatesMediaHostsAndRespectsEachSitesRatingPolicy() {
        assertEquals("video/webm", WebPolicy.mime(WebPolicy.media(WebSite.GELBOORU,
                "https://img3.gelbooru.com/images/aa/bb/example.webm")));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.GELBOORU,
                "https://api-cdn.rule34.xxx/images/example.mp4"));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.RULE34,
                "https://img3.gelbooru.com/images/example.mp4"));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.GELBOORU,
                "https://img3.gelbooru.com.evil.example/images/example.mp4"));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.GELBOORU,
                "https://img3.gelbooru.com/index.php"));
        assertEquals("blue_sky", WebPolicy.query(WebSite.GELBOORU, "blue_sky"));
        assertEquals("", WebPolicy.query(WebSite.GELBOORU, ""));
        for (String rating : new String[]{"general", "sensitive", "questionable", "explicit", ""}) {
            assertTrue(WebSite.GELBOORU.acceptsRating(rating));
        }
        assertFalse(WebSite.RULE34.acceptsRating("explicit"));
        assertTrue(WebSite.RULE34.acceptsRating("safe"));
        assertFalse(WebSite.RULE34.acceptsRating("general"));
    }

    @Test public void acceptsLegacyPathsAndRecognizesTheOfficialGate() {
        assertEquals("/" + "/images/sample.png",
                WebPolicy.media(WebSite.GELBOORU, "https://img4.gelbooru.com//images/sample.png").getRawPath());
        assertTrue(WebPolicy.gelbooruGate(java.net.URI.create("https://gelbooru.com/hotlink.php?hash=example")));
        assertFalse(WebPolicy.gelbooruGate(java.net.URI.create("https://unrelated.example/hotlink.php")));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.media(WebSite.GELBOORU,
                "https://gelbooru.com/hotlink.php?hash=example"));
    }

    @Test public void acceptsOnlyExactByteRanges() throws Exception {
        assertEquals(5000, WebPolicy.contentRange(206, "bytes 200-299/5000", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(200, null, 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(206, "bytes 0-99/5000", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(206, "bytes 200-299/220", 200, 100));
        assertThrows(IOException.class, () -> WebPolicy.contentRange(416, "bytes */5000", 200, 100));
    }

    @Test public void searchNeverBecomesAFullCatalogueScan() {
        assertEquals("blue_sky -clouds rating:safe", WebPolicy.query(WebSite.RULE34, "  blue_sky   -clouds "));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query(WebSite.RULE34, " "));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query(WebSite.RULE34, "-clouds"));
        assertThrows(IllegalArgumentException.class, () -> WebPolicy.query(WebSite.RULE34, "blue_sky rating:explicit"));
    }
}
