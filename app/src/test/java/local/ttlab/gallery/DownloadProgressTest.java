package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadProgressTest {
    @Test public void progressClampsAndPreservesPartialLastBlock(){
        assertEquals(0,DownloadProgress.percent(-1,100));
        assertEquals(50,DownloadProgress.percent(1L<<30,2L<<30));
        assertEquals(100,DownloadProgress.percent(1001,1000));
        assertEquals(0,DownloadProgress.percent(10,0));
        assertEquals(131072,DownloadProgress.resumeBytes(150000,200000));
        assertEquals(200000,DownloadProgress.resumeBytes(200000,200000));
        assertEquals(0,DownloadProgress.resumeBytes(-5,200000));
    }
    @Test public void remainingUsesOnlyNewBytesSinceResume(){
        assertEquals(-1,DownloadProgress.remainingSeconds(500000,1000000,500000,1000));
        assertEquals(4,DownloadProgress.remainingSeconds(600000,1000000,500000,1000));
        assertEquals(0,DownloadProgress.remainingSeconds(1000000,1000000,500000,1000));
        assertEquals(-1,DownloadProgress.remainingSeconds(500000,1000000,0,0));
    }
    @Test public void safeSizeLabelsAndLongEta(){
        assertEquals("0 B",DownloadProgress.bytes(-8));
        assertEquals("1.0 MiB",DownloadProgress.bytes(1048576));
        assertEquals("Calculando tiempo restante",DownloadProgress.remainingLabel(-1));
        assertEquals("Menos de 1 min restante",DownloadProgress.remainingLabel(59));
        assertEquals("2 h 1 min restantes",DownloadProgress.remainingLabel(7260));
    }
}
