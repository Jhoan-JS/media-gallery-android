package local.ttlab.gallery;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BiometricMarkerTest {
    @Test public void markerCannotAuthorizeAnotherPinVerifier()throws Exception{
        SecretKey key=KeyGenerator.getInstance("AES").generateKey();byte[] pin=new byte[48];Arrays.fill(pin,(byte)17);
        Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding");encrypt.init(Cipher.ENCRYPT_MODE,key);BiometricUnlock.bind(encrypt,pin);byte[] marker=encrypt.doFinal(new byte[32]);
        Cipher same=Cipher.getInstance("AES/GCM/NoPadding");same.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));BiometricUnlock.bind(same,pin);assertEquals(32,same.doFinal(marker).length);
        byte[] changed=pin.clone();changed[0]^=1;Cipher other=Cipher.getInstance("AES/GCM/NoPadding");other.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));BiometricUnlock.bind(other,changed);
        try{other.doFinal(marker);fail("A biometric marker must remain bound to the original PIN verifier");}catch(javax.crypto.AEADBadTagException expected){}
    }
    @Test public void absentOrMalformedPinCannotEnableBiometrics()throws Exception{
        SecretKey key=KeyGenerator.getInstance("AES").generateKey();
        for(byte[] pin:new byte[][]{null,new byte[0],new byte[47],new byte[49]}){
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
            try{BiometricUnlock.bind(cipher,pin);fail("A valid PIN verifier is required");}catch(GeneralSecurityException expected){}
        }
    }
}
