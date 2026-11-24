package local.ttlab.gallery;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BackupMediaSizeTest {
    @Test public void telegramRetainsLongSizesThroughFourGibibytes()throws Exception {
        GalleryBackup.checkMediaSize("telegram",1L+Integer.MAX_VALUE);
        GalleryBackup.checkMediaSize("telegram",4L*1024*1024*1024);
        rejects("telegram",4L*1024*1024*1024+1);
        rejects("telegram",0);
        rejects("telegram",-1);
    }
    @Test public void tatoRetainsItsLegacyLimit()throws Exception {
        GalleryBackup.checkMediaSize("tato",Integer.MAX_VALUE);
        rejects("tato",1L+Integer.MAX_VALUE);
    }
    private static void rejects(String source,long size)throws Exception {try{GalleryBackup.checkMediaSize(source,size);fail("invalid backup size accepted");}catch(IOException expected){}}
}
