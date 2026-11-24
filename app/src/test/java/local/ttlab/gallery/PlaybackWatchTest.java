package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackWatchTest {
    @Test public void openingAndSeekingDoNotMarkViewed() {
        PlaybackWatch watch=new PlaybackWatch();watch.duration(60000);
        assertFalse(watch.sample(0,0,false));
        assertFalse(watch.sample(1000,10000,true));
        watch.resetSample();
        assertFalse(watch.sample(1500,40000,true));
        assertFalse(watch.sample(2000,40500,true));
    }

    @Test public void fiveSecondsOfAdvancingPlaybackMarkViewed() {
        PlaybackWatch watch=new PlaybackWatch();watch.duration(60000);
        assertFalse(watch.sample(100,0,true));
        for(int n=1;n<10;n++)assertFalse(watch.sample(100+n*500,n*500,true));
        assertTrue(watch.sample(5100,5000,true));
    }

    @Test public void PausingBufferingAndBackgroundTimeDoNotCount() {
        PlaybackWatch watch=new PlaybackWatch();watch.duration(60000);
        assertFalse(watch.sample(0,0,true));
        assertFalse(watch.sample(2000,2000,true));
        assertFalse(watch.sample(22000,2000,false));
        assertFalse(watch.sample(42000,2000,true));
        assertFalse(watch.sample(44000,4000,true));
        watch.resetSample();
        assertFalse(watch.sample(1044000,5000,true));
        assertTrue(watch.sample(1045000,6000,true));
    }

    @Test public void shortClipsMustActuallyPlayMostOfTheirDuration() {
        PlaybackWatch watch=new PlaybackWatch();watch.duration(2000);
        assertFalse(watch.sample(0,0,true));
        assertFalse(watch.sample(1000,1000,true));
        assertTrue(watch.sample(1900,1900,true));
    }
}
