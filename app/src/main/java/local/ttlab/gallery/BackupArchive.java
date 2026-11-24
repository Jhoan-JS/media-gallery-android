package local.ttlab.gallery;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/** Streaming portable backup. Consumers must stage entries until read returns successfully. */
public final class BackupArchive {
    public static final int MAX_ENTRY_BYTES=262144;
    public static final long MAX_ENTRIES=20000000L;
    private static final int ITERATIONS=210000,HEADER_BYTES=32,MAX_NAME_BYTES=160;
    private static final int ENTRY=1,FOOTER=2,FOOTER_BYTES=40;
    private static final byte[] MAGIC="TTLABBK2".getBytes(StandardCharsets.US_ASCII);

    private BackupArchive() {}

    @FunctionalInterface public interface Contents { void write(Writer writer) throws IOException; }
    @FunctionalInterface public interface EntryConsumer { void accept(String name,byte[] bytes) throws IOException; }

    public static void write(OutputStream output,char[] passphrase,Contents contents) throws IOException {
        Objects.requireNonNull(output);Objects.requireNonNull(contents);
        byte[] header=new byte[HEADER_BYTES];
        new SecureRandom().nextBytes(header);
        ByteBuffer.wrap(header).put(MAGIC).putInt(ITERATIONS);
        Key key=derive(passphrase,header);
        Writer writer=null;
        try {
            writer=new Writer(output,header,key);
            output.write(header);
            contents.write(writer);
            writer.finish();
        } catch(GeneralSecurityException e) { throw invalid(e); }
        finally { if(writer!=null)writer.deactivate();key.erase(); }
    }

    /** Entry byte arrays belong to the consumer; return only means success after footer and EOF. */
    public static void read(InputStream input,char[] passphrase,EntryConsumer consumer) throws IOException {
        Objects.requireNonNull(input);Objects.requireNonNull(consumer);
        DataInputStream in=new DataInputStream(input);
        byte[] header=new byte[HEADER_BYTES];in.readFully(header);
        if(!Arrays.equals(MAGIC,Arrays.copyOf(header,MAGIC.length))||ByteBuffer.wrap(header,8,4).getInt()!=ITERATIONS)
            throw new IOException("Unsupported backup header");
        Key key=derive(passphrase,header);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            MessageDigest transcript=MessageDigest.getInstance("SHA-256");transcript.update(header);
            long count=0;
            while(true) {
                byte[] frame=new byte[13];in.readFully(frame);
                ByteBuffer metadata=ByteBuffer.wrap(frame);
                int type=metadata.get()&255;long sequence=metadata.getLong();int length=metadata.getInt();
                if(sequence!=count)throw new IOException("Invalid backup sequence");
                if(type==ENTRY) {
                    validateEntryCount(count+1);
                    if(length<19||length>MAX_ENTRY_BYTES+MAX_NAME_BYTES+18)throw new IOException("Invalid backup frame length");
                } else if(type==FOOTER) {
                    if(length!=FOOTER_BYTES+16)throw new IOException("Invalid backup footer length");
                } else throw new IOException("Invalid backup frame type");
                byte[] encrypted=new byte[length];in.readFully(encrypted);
                byte[] plain=crypt(cipher,Cipher.DECRYPT_MODE,key,header,frame,encrypted);
                try {
                    if(type==FOOTER) {
                        long declared=ByteBuffer.wrap(plain).getLong();
                        byte[] digest=Arrays.copyOfRange(plain,8,FOOTER_BYTES);
                        if(declared!=count||!MessageDigest.isEqual(digest,transcript.digest()))throw new IOException("Invalid backup transcript");
                        if(in.read()!=-1)throw new IOException("Trailing backup data");
                        return;
                    }
                    int nameLength=ByteBuffer.wrap(plain).getShort()&65535;
                    if(nameLength<1||nameLength>MAX_NAME_BYTES||nameLength>plain.length-2||plain.length-2-nameLength>MAX_ENTRY_BYTES)
                        throw new IOException("Invalid backup entry");
                    String name=new String(plain,2,nameLength,StandardCharsets.US_ASCII);validateName(name);
                    transcript.update(frame);transcript.update(encrypted);count++;
                    consumer.accept(name,Arrays.copyOfRange(plain,2+nameLength,plain.length));
                } finally { Arrays.fill(plain,(byte)0); }
            }
        } catch(GeneralSecurityException e) { throw invalid(e); }
        finally { key.erase(); }
    }

    public static final class Writer {
        private final OutputStream output;
        private final byte[] header;
        private final Key key;
        private final Cipher cipher;
        private final MessageDigest transcript;
        private long count;
        private boolean active=true;

        private Writer(OutputStream output,byte[] header,Key key) throws GeneralSecurityException {
            this.output=output;this.header=header;this.key=key;
            cipher=Cipher.getInstance("AES/GCM/NoPadding");
            transcript=MessageDigest.getInstance("SHA-256");transcript.update(header);
        }

        public synchronized void put(String name,byte[] bytes) throws IOException {
            if(!active)throw new IOException("Backup writer is closed");
            validateName(name);Objects.requireNonNull(bytes);
            if(bytes.length>MAX_ENTRY_BYTES)throw new IOException("Backup entry is too large");
            validateEntryCount(count+1);
            byte[] encoded=name.getBytes(StandardCharsets.US_ASCII);
            byte[] plain=ByteBuffer.allocate(2+encoded.length+bytes.length).putShort((short)encoded.length).put(encoded).put(bytes).array();
            try { frame(ENTRY,plain);count++; }
            catch(GeneralSecurityException e) { active=false;throw invalid(e); }
            catch(IOException|RuntimeException e) { active=false;throw e; }
            finally { Arrays.fill(plain,(byte)0); }
        }

        private void frame(int type,byte[] plain) throws GeneralSecurityException,IOException {
            byte[] metadata=ByteBuffer.allocate(13).put((byte)type).putLong(count).putInt(plain.length+16).array();
            byte[] encrypted=crypt(cipher,Cipher.ENCRYPT_MODE,key,header,metadata,plain);
            output.write(metadata);output.write(encrypted);
            if(type==ENTRY) { transcript.update(metadata);transcript.update(encrypted); }
        }

        private synchronized void finish() throws GeneralSecurityException,IOException {
            if(!active)throw new IOException("Backup writer is closed");
            active=false;
            byte[] footer=ByteBuffer.allocate(FOOTER_BYTES).putLong(count).put(transcript.digest()).array();
            try { frame(FOOTER,footer); } finally { Arrays.fill(footer,(byte)0); }
        }

        private synchronized void deactivate() { active=false; }
    }

    static void validateEntryCount(long count) throws IOException {
        if(count<0||count>MAX_ENTRIES)throw new IOException("Too many backup entries");
    }

    private static void validateName(String name) throws IOException {
        if(name==null||name.isEmpty()||name.length()>MAX_NAME_BYTES)throw new IOException("Invalid backup name");
        int start=0;
        for(int i=0;i<=name.length();i++) {
            if(i==name.length()||name.charAt(i)=='/') {
                int length=i-start;
                if(length==0||(length==1&&name.charAt(start)=='.')||(length==2&&name.charAt(start)=='.'&&name.charAt(start+1)=='.'))
                    throw new IOException("Invalid backup name");
                start=i+1;
            } else {
                char c=name.charAt(i);
                if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-'))throw new IOException("Invalid backup name");
            }
        }
    }

    private static byte[] crypt(Cipher cipher,int mode,Key key,byte[] header,byte[] metadata,byte[] bytes) throws GeneralSecurityException {
        byte[] nonce=ByteBuffer.allocate(12).put(header,28,4).put(metadata,1,8).array();
        cipher.init(mode,key,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(header);cipher.updateAAD(metadata);
        return cipher.doFinal(bytes);
    }

    private static Key derive(char[] passphrase,byte[] header) throws IOException {
        if(passphrase==null||passphrase.length==0||passphrase.length>1024)throw new IOException("Invalid backup passphrase length");
        PBEKeySpec spec=new PBEKeySpec(passphrase,Arrays.copyOfRange(header,12,28),ITERATIONS,256);
        try { return new Key(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch(GeneralSecurityException e) { throw invalid(e); }
        finally { spec.clearPassword(); }
    }

    private static IOException invalid(Exception cause) { return new IOException("Invalid backup or passphrase",cause); }

    private static final class Key implements SecretKey {
        private static final long serialVersionUID=1L;
        private final byte[] bytes;
        private Key(byte[] bytes) { this.bytes=bytes; }
        @Override public String getAlgorithm() { return "AES"; }
        @Override public String getFormat() { return "RAW"; }
        @Override public byte[] getEncoded() { return bytes.clone(); }
        private void erase() { Arrays.fill(bytes,(byte)0); }
    }
}
