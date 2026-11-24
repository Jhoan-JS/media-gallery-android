package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public class ItemRevisionTest {
    @Test public void unnamedCameraVideoRetainsRawMetadataAndGetsReadableLabel(){
        Item video=new Item(2,7,"","video/mp4",7,1700000000L,10,10,1,"r1");
        assertEquals("",video.name);assertEquals("Video sin nombre",video.label());
    }
    @Test public void sameSizeReplacementGetsDifferentEncryptedCacheIdentity(){
        Item old=new Item(33,7,"image.png","image/png",80000,1700000000,0,0,0,"revision-a");
        Item replacement=new Item(33,7,"image.png","image/png",80000,1700000000,0,0,0,"revision-b");
        assertEquals(old.key(),replacement.key());assertNotEquals(old.cacheKey(),replacement.cacheKey());
    }
}
