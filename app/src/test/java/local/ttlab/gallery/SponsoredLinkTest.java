package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SponsoredLinkTest {
    @Test public void httpsLinksKeepTrackingAndTelegramResolveIsConverted(){
        assertEquals("https://t.me/example_channel?start=ref%26x",SponsoredLink.browser("https://t.me/example_channel?start=ref%26x"));
        assertEquals("https://t.me/example_bot?start=campaign",SponsoredLink.browser("tg://resolve?domain=example_bot&start=campaign"));
    }
    @Test public void onlyExplicitSafeWebDestinationsAreAccepted(){
        for(String bad:new String[]{"javascript:alert(1)","file:///sdcard/Download/x","intent://x#Intent;end","http://example.org","https://user:pass@example.org/x","tg://proxy?server=x","tg://resolve?domain=one&domain=two","https://example.org/\n"})assertNull(bad,SponsoredLink.browser(bad));
        assertNull(SponsoredLink.browser(null));assertNull(SponsoredLink.browser("https://example.org/"+"a".repeat(2048)));
    }
}
