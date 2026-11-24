package local.ttlab.gallery;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class BackupArchiveTest {
    private static final char[] PASSWORD="portable backup passphrase".toCharArray();

    @Test public void roundTripPreservesOrderedNamesAndMaximumEntry() throws Exception {
        byte[] chunk=new byte[262144];new Random(42).nextBytes(chunk);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        BackupArchive.write(out,PASSWORD,writer->{
            writer.put("manifest",new byte[]{1,2,3});
            writer.put("items/42",new byte[0]);
            writer.put("chunks/42/0",chunk);
        });
        List<String> names=new ArrayList<>();List<byte[]> entries=new ArrayList<>();
        BackupArchive.read(new ByteArrayInputStream(out.toByteArray()),PASSWORD,(name,bytes)->{names.add(name);entries.add(bytes);});
        assertEquals(Arrays.asList("manifest","items/42","chunks/42/0"),names);
        assertArrayEquals(new byte[]{1,2,3},entries.get(0));
        assertEquals(0,entries.get(1).length);assertArrayEquals(chunk,entries.get(2));
    }

    @Test public void exportsUseFreshSaltAndHideNamesAndData() throws Exception {
        byte[] first=archive(),second=archive();
        assertFalse(Arrays.equals(first,second));
        assertFalse(Arrays.equals(Arrays.copyOfRange(first,12,28),Arrays.copyOfRange(second,12,28)));
        assertFalse(new String(first,StandardCharsets.ISO_8859_1).contains("private-name"));
        assertFalse(new String(first,StandardCharsets.ISO_8859_1).contains("private-data"));
        assertArrayEquals("portable backup passphrase".toCharArray(),PASSWORD);
    }

    @Test public void rejectsWrongPasswordAndTamperingInHeaderEntryAndFooter() throws Exception {
        byte[] original=archive();
        reject(()->BackupArchive.read(new ByteArrayInputStream(original),"wrong password".toCharArray(),(name,bytes)->fail("unauthenticated entry")));
        for(int index:new int[]{0,8,12,28,32,33,41,45,frameEnd(original,32)-1,original.length-1}) {
            byte[] changed=original.clone();changed[index]^=1;
            reject(()->read(changed));
        }
    }

    @Test public void rejectsTruncatedHeaderFramesAndMissingFooterEvenAfterAnEntry() throws Exception {
        byte[] original=archive();int footer=frameEnd(original,32);
        for(int length:new int[]{0,1,7,8,11,12,27,28,31,32,33,40,44,45,footer-1,footer,footer+1,original.length-1}) {
            reject(()->read(Arrays.copyOf(original,length)));
        }
        int[] staged={0};
        reject(()->BackupArchive.read(new ByteArrayInputStream(Arrays.copyOf(original,footer)),PASSWORD,(name,bytes)->staged[0]++));
        assertEquals("consumer must stage until the footer verifies",1,staged[0]);
    }

    @Test public void rejectsTrailingBytesIncludingAnotherValidArchive() throws Exception {
        byte[] original=archive();
        reject(()->read(concat(original,new byte[]{0})));
        reject(()->read(concat(original,original)));
    }

    @Test public void rejectsReorderingDuplicationRemovalAndSequenceOverflow() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        BackupArchive.write(out,PASSWORD,w->{w.put("a",new byte[]{1});w.put("b",new byte[]{2});});
        byte[] original=out.toByteArray();int second=frameEnd(original,32),footer=frameEnd(original,second);
        byte[] header=Arrays.copyOf(original,32),a=Arrays.copyOfRange(original,32,second),b=Arrays.copyOfRange(original,second,footer),end=Arrays.copyOfRange(original,footer,original.length);
        reject(()->read(concat(header,b,a,end)));
        reject(()->read(concat(header,a,a,b,end)));
        reject(()->read(concat(header,a,end)));
        byte[] changed=original.clone();ByteBuffer.wrap(changed,33,8).putLong(BackupArchive.MAX_ENTRIES+1);
        reject(()->read(changed));
        byte[] negative=original.clone();ByteBuffer.wrap(negative,33,8).putLong(-1);
        reject(()->read(negative));
    }

    @Test public void rejectsImpossibleLengthsAndTypesBeforeAllocation() throws Exception {
        byte[] original=archive();
        for(int length:new int[]{-1,0,18,262323,Integer.MAX_VALUE}) {
            byte[] changed=Arrays.copyOf(original,45);ByteBuffer.wrap(changed,41,4).putInt(length);
            reject(()->read(changed));
        }
        byte[] changed=original.clone();changed[32]=3;reject(()->read(changed));
        byte[] footerLength=original.clone();ByteBuffer.wrap(footerLength,frameEnd(original,32)+9,4).putInt(55);
        reject(()->read(footerLength));
    }

    @Test public void validatesNamesOnWriterAndAuthenticatedReader() throws Exception {
        for(String name:new String[]{"",".","..","../escape","a/../b","a/./b","/absolute","a/","a//b","a\\b","C:/x","a\u0000b","caf\u00e9",repeat('a',161)}) {
            reject(()->BackupArchive.write(new ByteArrayOutputStream(),PASSWORD,w->w.put(name,new byte[0])));
            Fixture bad=new Fixture();bad.entry(name,new byte[0]);byte[] archive=bad.finish();reject(()->read(archive));
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        BackupArchive.write(out,PASSWORD,w->{w.put(repeat('a',160),new byte[0]);w.put("a..b/.hidden/a-b_0.jpg",new byte[0]);});
        int[] accepted={0};BackupArchive.read(new ByteArrayInputStream(out.toByteArray()),PASSWORD,(name,bytes)->accepted[0]++);
        assertEquals(2,accepted[0]);
    }

    @Test public void rejectsOversizedPayloadWithOtherwiseValidAuthenticatedFrame() throws Exception {
        reject(()->BackupArchive.write(new ByteArrayOutputStream(),PASSWORD,w->w.put("x",new byte[BackupArchive.MAX_ENTRY_BYTES+1])));
        Fixture bad=new Fixture();bad.entry("x",new byte[BackupArchive.MAX_ENTRY_BYTES+1]);byte[] oversized=bad.finish();reject(()->read(oversized));
        Fixture malformed=new Fixture();malformed.frame(1,0,new byte[]{0,5,65});byte[] malformedArchive=malformed.finish();reject(()->read(malformedArchive));
    }

    @Test public void checksAuthenticatedFooterCountAndTranscript() throws Exception {
        Fixture count=new Fixture();count.entry("x",new byte[]{1});count.footer(2,count.digest());reject(()->read(count.bytes()));
        Fixture digest=new Fixture();digest.entry("x",new byte[]{1});digest.footer(1,new byte[32]);reject(()->read(digest.bytes()));
        Fixture sequence=new Fixture();sequence.entry("x",new byte[]{1});sequence.frame(2,0,ByteBuffer.allocate(40).putLong(1).put(sequence.digest()).array());reject(()->read(sequence.bytes()));
        Fixture empty=new Fixture();read(empty.finish());
    }

    @Test public void enforcesTwentyMillionEntryLimitWithoutCounterOverflow() throws Exception {
        assertEquals(20000000L,BackupArchive.MAX_ENTRIES);
        BackupArchive.validateEntryCount(0);BackupArchive.validateEntryCount(BackupArchive.MAX_ENTRIES);
        reject(()->BackupArchive.validateEntryCount(-1));
        reject(()->BackupArchive.validateEntryCount(BackupArchive.MAX_ENTRIES+1));
        reject(()->BackupArchive.validateEntryCount(Long.MAX_VALUE));
    }

    @Test public void doesNotCloseCallerStreamsAndDoesNotAcceptRetainedWriter() throws Exception {
        boolean[] closed={false,false};BackupArchive.Writer[] retained={null};
        ByteArrayOutputStream out=new ByteArrayOutputStream(){@Override public void close(){closed[0]=true;}};
        BackupArchive.write(out,PASSWORD,w->{retained[0]=w;w.put("x",new byte[]{1});});
        reject(()->retained[0].put("late",new byte[0]));
        ByteArrayInputStream in=new ByteArrayInputStream(out.toByteArray()){@Override public void close(){closed[1]=true;}};
        BackupArchive.read(in,PASSWORD,(name,bytes)->{});
        assertFalse(closed[0]);assertFalse(closed[1]);
    }

    @Test public void propagatesConsumerFailureAndAbortedWriterNeverFinishes() throws Exception {
        IOException expected=new IOException("staging failed");
        try { BackupArchive.read(new ByteArrayInputStream(archive()),PASSWORD,(name,bytes)->{throw expected;});fail("consumer failure hidden"); }
        catch(IOException actual) { assertSame(expected,actual); }
        ByteArrayOutputStream out=new ByteArrayOutputStream();BackupArchive.Writer[] retained={null};
        try { BackupArchive.write(out,PASSWORD,w->{retained[0]=w;w.put("x",new byte[0]);throw expected;});fail("producer failure hidden"); }
        catch(IOException actual) { assertSame(expected,actual); }
        reject(()->retained[0].put("late",new byte[0]));reject(()->read(out.toByteArray()));
    }

    @Test public void outputFailurePoisonsWriterAndCannotReuseNonce() throws Exception {
        OutputStream failed=new OutputStream(){int remaining=46;@Override public void write(int b) throws IOException {if(--remaining<0)throw new IOException("disk full");}};
        reject(()->BackupArchive.write(failed,PASSWORD,w->{
            reject(()->w.put("x",new byte[0]));
            reject(()->w.put("retry",new byte[0]));
        }));
    }

    @Test public void uncheckedOutputFailureAlsoClosesWriterBeforeAnyRetry() throws Exception {
        OutputStream failed=new OutputStream(){int remaining=46;@Override public void write(int b){if(--remaining<0)throw new IllegalStateException("provider disconnected");}};
        reject(()->BackupArchive.write(failed,PASSWORD,w->{
            try{w.put("x",new byte[0]);fail("provider error hidden");}catch(IllegalStateException expected){}
            try{w.put("retry",new byte[0]);fail("failed writer reused");}catch(IOException expected){assertEquals("Backup writer is closed",expected.getMessage());}
        }));
    }

    @Test public void acceptsUnicodePassphraseWithoutNormalizingOrModifyingIt() throws Exception {
        char[] password="contrase\u00f1a \u03a9 \ud83d\udd10".toCharArray(),copy=password.clone();
        ByteArrayOutputStream out=new ByteArrayOutputStream();BackupArchive.write(out,password,w->w.put("x",new byte[]{9}));
        BackupArchive.read(new ByteArrayInputStream(out.toByteArray()),password,(name,bytes)->assertArrayEquals(new byte[]{9},bytes));
        assertArrayEquals(copy,password);
        reject(()->BackupArchive.write(new ByteArrayOutputStream(),new char[0],w->{}));
        reject(()->BackupArchive.write(new ByteArrayOutputStream(),new char[1025],w->{}));
    }

    private static byte[] archive() throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        BackupArchive.write(out,PASSWORD,w->w.put("private-name","private-data".getBytes(StandardCharsets.US_ASCII)));
        return out.toByteArray();
    }
    private static void read(byte[] bytes) throws IOException { BackupArchive.read(new ByteArrayInputStream(bytes),PASSWORD,(name,data)->{}); }
    private static int frameEnd(byte[] bytes,int start) { return start+13+ByteBuffer.wrap(bytes,start+9,4).getInt(); }
    private static byte[] concat(byte[]... pieces) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] piece:pieces)out.write(piece);return out.toByteArray(); }
    private static String repeat(char c,int count) { char[] chars=new char[count];Arrays.fill(chars,c);return new String(chars); }
    private interface IoAction { void run() throws IOException; }
    private static void reject(IoAction action) throws IOException { try{action.run();fail("invalid archive accepted");}catch(IOException expected){} }

    // Independently encoded authenticated hostile inputs exercise parser validation after GCM succeeds.
    private static final class Fixture {
        final byte[] header=ByteBuffer.allocate(32).put("TTLABBK2".getBytes(StandardCharsets.US_ASCII)).putInt(210000).array();
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        final MessageDigest transcript=MessageDigest.getInstance("SHA-256");
        final SecretKeySpec key;
        long count;
        Fixture() throws Exception {
            PBEKeySpec spec=new PBEKeySpec(PASSWORD,new byte[16],210000,256);
            try{key=new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}
            out.write(header);transcript.update(header);
        }
        void entry(String name,byte[] bytes) throws Exception {
            byte[] encoded=name.getBytes(StandardCharsets.UTF_8);
            frame(1,count,ByteBuffer.allocate(2+encoded.length+bytes.length).putShort((short)encoded.length).put(encoded).put(bytes).array());count++;
        }
        void frame(int type,long sequence,byte[] plain) throws Exception {
            byte[] metadata=ByteBuffer.allocate(13).put((byte)type).putLong(sequence).putInt(plain.length+16).array();
            byte[] nonce=ByteBuffer.allocate(12).put(header,28,4).putLong(sequence).array();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(header);cipher.updateAAD(metadata);byte[] encrypted=cipher.doFinal(plain);
            out.write(metadata);out.write(encrypted);if(type==1){transcript.update(metadata);transcript.update(encrypted);}
        }
        byte[] digest(){return transcript.digest();}
        void footer(long declared,byte[] digest) throws Exception {frame(2,count,ByteBuffer.allocate(40).putLong(declared).put(digest).array());}
        byte[] finish() throws Exception {footer(count,digest());return bytes();}
        byte[] bytes(){return out.toByteArray();}
    }
}
