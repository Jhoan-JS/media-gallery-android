package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public class PinThrottleTest {
 @Test public void backoffSurvivesReconstructionAndCapsWithoutDeletingData(){
  PinThrottle first=PinThrottle.read(null);first.failed(1000,7);
  PinThrottle restarted=PinThrottle.read(first.bytes());
  assertEquals(1500,restarted.remaining(1000,7));
  restarted.failed(2500,7);assertEquals(3000,PinThrottle.read(restarted.bytes()).remaining(2500,7));
  for(int i=0;i<40;i++)restarted.failed(10000,7);
  assertEquals(300000,restarted.remaining(10000,7));
  assertEquals(300000,restarted.remaining(0,8));
  assertEquals(0,restarted.remaining(300000,8));
 }
 @Test public void wallClockIsNotAnInputAndRebootCannotEraseTheWait(){PinThrottle a=PinThrottle.read(null);a.failed(500000,4);PinThrottle b=PinThrottle.read(a.bytes());assertEquals(1499,b.remaining(500001,4));assertEquals(1500,b.remaining(1000,5));assertEquals(1499,PinThrottle.read(b.bytes()).remaining(1001,5));}
 @Test public void malformedStateCannotResetAttempts(){
  try{PinThrottle.read(new byte[12]);fail("corrupt state accepted");}catch(SecurityException expected){}
 }
}
