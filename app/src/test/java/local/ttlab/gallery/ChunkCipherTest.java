package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.util.Arrays;
public class ChunkCipherTest {
    @Test public void encryptedChunksRoundTripAndRejectTamperingOrWrongIdentity() throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
        byte[] plain=new byte[131072];Arrays.fill(plain,(byte)42);
        byte[] encrypted=ChunkCipher.encrypt(key,"image-31/0",plain);
        assertFalse(Arrays.equals(plain,encrypted));
        assertArrayEquals(plain,ChunkCipher.decrypt(key,"image-31/0",encrypted));
        encrypted[encrypted.length-1]^=1;
        try{ChunkCipher.decrypt(key,"image-31/0",encrypted);fail("tampered chunk accepted");}catch(GeneralSecurityException expected){}
        byte[] fresh=ChunkCipher.encrypt(key,"image-31/0",plain);
        try{ChunkCipher.decrypt(key,"image-31/1",fresh);fail("chunk substitution accepted");}catch(GeneralSecurityException expected){}
        assertFalse(Arrays.equals(fresh,ChunkCipher.encrypt(key,"image-31/0",plain)));
    }
}
