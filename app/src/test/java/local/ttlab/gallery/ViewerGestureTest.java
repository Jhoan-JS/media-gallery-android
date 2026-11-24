package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerGestureTest {
    @Test public void leftAdvancesAndRightReturnsInTheSelectedOrder(){
        assertEquals(1,ViewerGesture.direction(-150,20,-600,1,true));
        assertEquals(-1,ViewerGesture.direction(150,-20,600,1,true));
    }
    @Test public void zoomedImagesAndVerticalOrSlowGesturesKeepTheirOwnInteraction(){
        assertEquals(0,ViewerGesture.direction(-150,0,-600,1,false));
        assertEquals(0,ViewerGesture.direction(-150,100,-600,1,true));
        assertEquals(0,ViewerGesture.direction(-150,0,-100,1,true));
        assertEquals(0,ViewerGesture.direction(-30,0,-600,1,true));
    }
    @Test public void physicalThresholdsScaleWithDisplayDensity(){
        assertEquals(0,ViewerGesture.direction(-100,0,-600,2,true));
        assertEquals(1,ViewerGesture.direction(-160,0,-600,2,true));
        assertEquals(0,ViewerGesture.direction(-160,0,-400,2,true));
    }
}
