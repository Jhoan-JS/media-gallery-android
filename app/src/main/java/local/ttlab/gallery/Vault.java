package local.ttlab.gallery;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class Vault {
    private final File root,cache,offline;
    private final SecretKey key;final int bootCount;
    private long cacheBytes;
    private long cacheLimit=256L*1024*1024;
    Vault(Context c)throws Exception{this(c,"vault");}
    Vault(Context c,String directory)throws Exception{
        bootCount=android.provider.Settings.Global.getInt(c.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,0);
        if(!directory.matches("[a-z-]+(?:/[a-z-]+)*"))throw new IllegalArgumentException("Ruta interna inválida");
        root=new File(c.getNoBackupFilesDir(),directory);cache=new File(root,"cache");offline=new File(root,"offline");
        if(!cache.isDirectory()&&!cache.mkdirs())throw new java.io.IOException("No se pudo crear la caché");
        if(!offline.isDirectory()&&!offline.mkdirs())throw new java.io.IOException("No se pudo crear el almacén");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("tato-gallery-v1")){
            if(new File(root,"database.key").exists())throw new SecurityException("La clave del dispositivo ya no está disponible");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("tato-gallery-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());generator.generateKey();
        }
        key=(SecretKey)store.getKey("tato-gallery-v1",null);
        byte[] budget=secret("cache-limit");if(budget!=null){if(budget.length!=8)throw new SecurityException("Límite de caché inválido");cacheLimit=java.nio.ByteBuffer.wrap(budget).getLong();checkLimit(cacheLimit);}
        cacheBytes=usage(false);
    }
    synchronized byte[] databaseKey()throws Exception{
        File file=new File(root,"database.key");
        if(file.exists())return ChunkCipher.decrypt(key,"database-key",Files.readAllBytes(file.toPath()));
        byte[] value=new byte[32];new SecureRandom().nextBytes(value);atomic(file,ChunkCipher.encrypt(key,"database-key",value));return value;
    }
    synchronized byte[] secret(String name)throws Exception{
        if(!name.matches("[a-z-]{1,24}"))throw new IllegalArgumentException();
        File file=new File(root,name+".secret");return file.exists()?ChunkCipher.decrypt(key,"secret/"+name,Files.readAllBytes(file.toPath())):null;
    }
    synchronized void secret(String name,byte[] value)throws Exception{
        if(!name.matches("[a-z-]{1,24}"))throw new IllegalArgumentException();atomic(new File(root,name+".secret"),ChunkCipher.encrypt(key,"secret/"+name,value));
    }
    String hash(String id){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));char[] hex="0123456789abcdef".toCharArray(),out=new char[64];for(int n=0;n<bytes.length;n++){out[n*2]=hex[(bytes[n]&255)>>>4];out[n*2+1]=hex[bytes[n]&15];}return new String(out)+".gcm";}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    File file(String id){File pinned=new File(offline,hash(id));return pinned.exists()?pinned:new File(cache,hash(id));}
    synchronized byte[] get(String id)throws Exception{
        File file=file(id);if(!file.exists())return null;
        if(file.length()>Source.CHUNK+28)throw new SecurityException("Archivo cifrado inválido");
        byte[] value=ChunkCipher.decrypt(key,id,Files.readAllBytes(file.toPath()));file.setLastModified(System.currentTimeMillis());return value;
    }
    synchronized void put(String id,byte[] bytes)throws Exception{
        if(bytes.length>Source.CHUNK)throw new IllegalArgumentException("Bloque demasiado grande");
        File target=file(id);long previous=target.length();atomic(target,ChunkCipher.encrypt(key,id,bytes));
        if(target.getParentFile().equals(cache)){cacheBytes+=target.length()-previous;trim();}
    }
    synchronized void pin(String id)throws Exception{
        File current=file(id);if(!current.exists())throw new java.io.IOException("Falta un bloque");
        File target=new File(offline,hash(id));if(!current.equals(target)){long length=current.length();Files.move(current.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);cacheBytes=Math.max(0,cacheBytes-length);}
    }
    synchronized void keep(String id,byte[] bytes)throws Exception{
        if(bytes.length>Source.CHUNK)throw new IllegalArgumentException("Bloque demasiado grande");
        atomic(new File(offline,hash(id)),ChunkCipher.encrypt(key,id,bytes));File cached=new File(cache,hash(id));long length=cached.length();if(Files.deleteIfExists(cached.toPath()))cacheBytes=Math.max(0,cacheBytes-length);
    }
    synchronized void remove(String id)throws Exception{File target=file(id);long length=target.length();if(Files.deleteIfExists(target.toPath())&&target.getParentFile().equals(cache))cacheBytes=Math.max(0,cacheBytes-length);}
    synchronized void removeImportBlock(String id)throws Exception{
        String hash=hash(id);
        Files.deleteIfExists(new File(offline,hash).toPath());
        File cached=new File(cache,hash);
        long length=cached.length();
        if(Files.deleteIfExists(cached.toPath()))cacheBytes=Math.max(0,cacheBytes-length);
        Files.deleteIfExists(new File(offline,hash+".part").toPath());
        Files.deleteIfExists(new File(cache,hash+".part").toPath());
    }
    void syncImportedBlocks()throws Exception{GalleryBackup.syncDirectory(offline);}
    synchronized void unpin(String id)throws Exception{Files.deleteIfExists(new File(offline,hash(id)).toPath());}
    private static void atomic(File file,byte[] bytes)throws Exception{
        File part=new File(file.getParentFile(),file.getName()+".part");
        try{
            try(FileOutputStream stream=new FileOutputStream(part)){stream.write(bytes);stream.getFD().sync();}
            Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(part.toPath());}
    }
    synchronized long usage(boolean pinned){long total=0;try(java.nio.file.DirectoryStream<java.nio.file.Path> paths=Files.newDirectoryStream((pinned?offline:cache).toPath())){for(java.nio.file.Path path:paths)try{total+=Files.size(path);}catch(java.nio.file.NoSuchFileException moved){}}catch(java.io.IOException error){throw new IllegalStateException("No se pudo medir el almacenamiento",error);}return total;}
    synchronized void trim()throws Exception{
        if(cacheBytes<=cacheLimit)return;long size=usage(false);cacheBytes=size;if(size<=cacheLimit)return;
        File[] files=cache.listFiles();if(files==null)return;
        Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));
        for(File file:files){long length=file.length();Files.deleteIfExists(file.toPath());size-=length;cacheBytes=size;if(size<=cacheLimit*7/8)break;}
    }
    synchronized void clearCache()throws Exception{try(java.nio.file.DirectoryStream<java.nio.file.Path> files=Files.newDirectoryStream(cache.toPath())){for(java.nio.file.Path file:files)Files.deleteIfExists(file);}cacheBytes=0;}
    synchronized void clearSaved()throws Exception{try(java.nio.file.DirectoryStream<java.nio.file.Path> files=Files.newDirectoryStream(offline.toPath())){for(java.nio.file.Path file:files)Files.deleteIfExists(file);}}
    private static void checkLimit(long value){if(value!=64L*1024*1024&&value!=128L*1024*1024&&value!=256L*1024*1024&&value!=512L*1024*1024)throw new IllegalArgumentException("Límite de caché inválido");}
    synchronized long cacheLimit(){return cacheLimit;}
    synchronized void setCacheLimit(long value)throws Exception{checkLimit(value);secret("cache-limit",java.nio.ByteBuffer.allocate(8).putLong(value).array());cacheLimit=value;trim();}
    static final class Entry{final String hash;final long bytes;final boolean saved;Entry(String hash,long bytes,boolean saved){this.hash=hash;this.bytes=bytes;this.saved=saved;}}
    void visit(java.util.function.Consumer<Entry> visitor)throws java.io.IOException{for(boolean saved:new boolean[]{false,true})try(java.nio.file.DirectoryStream<java.nio.file.Path> paths=Files.newDirectoryStream((saved?offline:cache).toPath(),"*.gcm")){for(java.nio.file.Path path:paths){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();String name=path.getFileName().toString();if(!name.matches("[0-9a-f]{64}\\.gcm"))continue;try{visitor.accept(new Entry(name,Files.size(path),saved));}catch(java.nio.file.NoSuchFileException moved){}}}}
    synchronized void removeCached(java.util.Collection<String> hashes)throws Exception{for(String hash:hashes){if(!hash.matches("[0-9a-f]{64}\\.gcm"))throw new IllegalArgumentException();File file=new File(cache,hash);long size=file.length();if(Files.deleteIfExists(file.toPath()))cacheBytes=Math.max(0,cacheBytes-size);}}
}
