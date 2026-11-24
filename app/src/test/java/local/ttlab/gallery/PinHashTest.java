package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public class PinHashTest {
    @Test public void pinRejectsWrongAndMalformedCredentials()throws Exception{
        byte[] hash=PinHash.create("123456".toCharArray());
        assertTrue(PinHash.matches("123456".toCharArray(),hash));
        assertFalse(PinHash.matches("123457".toCharArray(),hash));
        assertFalse(PinHash.matches("123456".toCharArray(),new byte[8]));
    }
}
