package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackProgressTest {
    @Test public void briefOpeningDoesNotCreateContinueWatching(){
        assertEquals(0,PlaybackProgress.checkpoint(2999,60000));
        assertEquals(3000,PlaybackProgress.checkpoint(3000,60000));
    }
    @Test public void reopeningAnUnfinishedVideoRestoresTheActualPosition(){
        assertEquals(18234,PlaybackProgress.resume(18234,60000,false));
        assertEquals(0,PlaybackProgress.resume(18234,60000,true));
    }
    @Test public void lastTwoSecondsAndLastFivePercentRestartInsteadOfResumingTheEnd(){
        assertEquals(0,PlaybackProgress.resume(18000,20000,false));
        assertEquals(17999,PlaybackProgress.resume(17999,20000,false));
        assertEquals(0,PlaybackProgress.resume(95000,100000,false));
        assertEquals(94999,PlaybackProgress.resume(94999,100000,false));
    }
    @Test public void unknownInvalidAndOverflowingDurationsCannotProduceAResume(){
        assertEquals(0,PlaybackProgress.resume(-1,60000,false));
        assertEquals(0,PlaybackProgress.resume(5000,0,false));
        assertEquals(0,PlaybackProgress.resume(60001,60000,false));
        assertEquals(1500000000,PlaybackProgress.resume(1500000000,2000000000,false));
    }
}
