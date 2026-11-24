package local.ttlab.gallery;
import java.nio.ByteBuffer;
final class PinThrottle {
 private int failures,boot;private long until;
 static PinThrottle read(byte[] bytes){
  PinThrottle state=new PinThrottle();if(bytes==null||bytes.length==0)return state;
  if(bytes.length!=20)throw new SecurityException("E_PIN_STATE");
  ByteBuffer b=ByteBuffer.wrap(bytes);if(b.getInt()!=2)throw new SecurityException("E_PIN_STATE");
  state.failures=b.getInt();state.boot=b.getInt();state.until=b.getLong();if(state.failures<0||state.failures>31||state.until<0)throw new SecurityException("E_PIN_STATE");return state;
 }
 private long delay(){return failures==0?0:Math.min(300000,1500L<<Math.min(8,failures-1));}
 long remaining(long elapsed,int currentBoot){if(failures==0)return 0;if(boot!=currentBoot||until-elapsed>delay()){boot=currentBoot;until=elapsed+delay();}return Math.max(0,until-elapsed);}
 void failed(long elapsed,int currentBoot){failures=Math.min(31,failures+1);boot=currentBoot;until=elapsed+delay();}
 byte[] bytes(){return ByteBuffer.allocate(20).putInt(2).putInt(failures).putInt(boot).putLong(until).array();}
}
