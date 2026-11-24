package local.ttlab.gallery;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Arrays;
final class PinHash {
    static byte[] create(char[] pin)throws Exception{
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);byte[] hash=derive(pin,salt);byte[] out=Arrays.copyOf(salt,48);System.arraycopy(hash,0,out,16,32);return out;
    }
    static boolean matches(char[] pin,byte[] stored)throws Exception{return stored.length==48&&MessageDigest.isEqual(derive(pin,Arrays.copyOf(stored,16)),Arrays.copyOfRange(stored,16,48));}
    private static byte[] derive(char[] pin,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pin,salt,210000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
    }
}
