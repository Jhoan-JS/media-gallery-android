package local.ttlab.gallery;
import org.junit.Test;import static org.junit.Assert.*;
public class PlayerControlsTest {
 @Test public void watchedTimeSupportsVariableSpeedWithoutCountingSeeks(){PlaybackWatch watch=new PlaybackWatch();watch.duration(20000);watch.sample(0,0,true,2);for(int n=1;n<=5;n++)assertEquals(n==5,watch.sample(n*1000,n*2000,true,2));PlaybackWatch seek=new PlaybackWatch();seek.duration(20000);seek.sample(0,0,true,2);assertFalse(seek.sample(1000,15000,true,2));assertFalse(seek.sample(9000,15000,false,2));}
 @Test public void controlsClampAndKeepCenterFree(){assertEquals(.5f,PlayerControls.speed(.1f),0);assertEquals(2f,PlayerControls.speed(8f),0);assertEquals(1f,PlayerControls.speed(Float.NaN),0);assertEquals(1f,PlayerControls.level(.7f,2f),0);assertEquals(0f,PlayerControls.level(.2f,-1f),0);assertEquals(-1,PlayerControls.side(.2f));assertEquals(0,PlayerControls.side(.5f));assertEquals(1,PlayerControls.side(.8f));}
}
