package local.ttlab.telegram;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

final class SecureStore {
    private final File directory;
    private final SecretKey key;
    SecureStore(Context context)throws Exception {this(context,"connector");}
    SecureStore(Context context,String directoryName)throws Exception {
        if(!directoryName.matches("[a-z-]+"))throw new IllegalArgumentException();
        directory=new File(context.getNoBackupFilesDir(),directoryName);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("E_TELEGRAM_STORAGE");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias=context.getPackageName()+"."+directoryName+".v1";
        if(!store.containsAlias(alias)) {
            String[] existing=directory.list();
            if(existing!=null&&existing.length>0)throw new SecurityException("E_TELEGRAM_KEY_UNAVAILABLE");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        key=(SecretKey)store.getKey(alias,null);
    }
    private AtomicFile file(String name) {
        if(!name.matches("[a-z-]{1,24}"))throw new IllegalArgumentException();
        return new AtomicFile(new File(directory,name+".gcm"));
    }
    synchronized byte[] get(String name)throws Exception {
        AtomicFile target=file(name);if(!target.getBaseFile().exists()&&!new File(target.getBaseFile()+".bak").exists())return null;
        byte[] sealed=target.readFully();
        if(sealed.length<28||sealed.length>4*1024*1024)throw new SecurityException("E_TELEGRAM_STORAGE");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,sealed,0,12));
            cipher.updateAAD(name.getBytes(StandardCharsets.US_ASCII));return cipher.doFinal(sealed,12,sealed.length-12);
        } finally {Arrays.fill(sealed,(byte)0);}
    }
    synchronized void put(String name,byte[] clear)throws Exception {
        if(clear==null||clear.length>4*1024*1024-28)throw new IllegalArgumentException();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        cipher.updateAAD(name.getBytes(StandardCharsets.US_ASCII));byte[] sealed=cipher.doFinal(clear);
        AtomicFile target=file(name);FileOutputStream output=null;
        try {output=target.startWrite();output.write(cipher.getIV());output.write(sealed);target.finishWrite(output);}
        catch(Exception failure){if(output!=null)target.failWrite(output);throw failure;}
        finally{Arrays.fill(sealed,(byte)0);}
    }
    synchronized byte[] databaseKey()throws Exception {
        byte[] value=get("database-key");if(value!=null)return value;
        value=new byte[32];new SecureRandom().nextBytes(value);put("database-key",value);return value;
    }
    byte[] loadSession()throws Exception {byte[] session=get("session");return session==null?new byte[0]:session;}
    void saveSession(byte[] session)throws Exception {put("session",session);}
}
