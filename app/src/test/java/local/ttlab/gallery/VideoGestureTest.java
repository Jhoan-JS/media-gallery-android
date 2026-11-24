package local.ttlab.gallery;
import org.junit.Test;import static org.junit.Assert.*;
public class VideoGestureTest {
 @Test public void centerZoomDoesNotStealSideSeeks(){assertEquals(-1,VideoGesture.doubleTap(.1f));assertEquals(0,VideoGesture.doubleTap(.5f));assertEquals(1,VideoGesture.doubleTap(.9f));}
 @Test public void pinchIsBoundedAndInvalidSamplesDoNotBreakTheSurface(){assertEquals(4f,VideoGesture.scale(2f,10f),0);assertEquals(1f,VideoGesture.scale(2f,.1f),0);assertEquals(2f,VideoGesture.scale(2f,Float.NaN),0);assertEquals(2.5f,VideoGesture.scale(2f,1.25f),0);}
}
