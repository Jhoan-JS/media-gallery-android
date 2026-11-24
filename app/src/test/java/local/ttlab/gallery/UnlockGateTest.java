package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnlockGateTest {
    @Test public void currentForegroundAttemptCanUnlock(){assertTrue(UnlockGate.accept(7,7,true,true,false,false));}
    @Test public void pauseOrAnotherAttemptRejectsAnOtherwiseSuccessfulAuthentication(){
        assertFalse(UnlockGate.accept(7,7,false,true,false,false));
        assertFalse(UnlockGate.accept(7,8,true,true,false,false));
    }
    @Test public void backgroundProfileFinishingAndRestoreRejectAuthentication(){
        assertFalse(UnlockGate.accept(7,7,true,false,false,false));
        assertFalse(UnlockGate.accept(7,7,true,true,true,false));
        assertFalse(UnlockGate.accept(7,7,true,true,false,true));
    }
}
