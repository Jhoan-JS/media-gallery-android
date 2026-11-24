package local.ttlab.gallery;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
public final class ChunkCipher {
    static byte[] encrypt(SecretKey key,String id,byte[] plain)throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);byte[] nonce=cipher.getIV();
        if(nonce.length!=12)throw new GeneralSecurityException("Unsupported GCM nonce");
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(plain);
        byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,12,encrypted.length);return result;
    }
    static byte[] decrypt(SecretKey key,String id,byte[] encrypted)throws GeneralSecurityException {
        if(encrypted.length<28)throw new GeneralSecurityException("Truncated encrypted chunk");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypted,0,12));
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));return cipher.doFinal(encrypted,12,encrypted.length-12);
    }
}
