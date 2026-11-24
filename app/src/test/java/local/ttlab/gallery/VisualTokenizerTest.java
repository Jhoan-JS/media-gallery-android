package local.ttlab.gallery;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisualTokenizerTest {
    @Test public void wordpieceUsesLongestMatchAndRejectsUnmatchedWholeWords() throws Exception {
        VisualTokenizer tokenizer=tiny();
        assertArrayEquals(new long[]{101,106,102},tokenizer.encode("ab"));
        assertArrayEquals(new long[]{101,106,105,102},tokenizer.encode("abb"));
        assertArrayEquals(new long[]{101,100,102},tokenizer.encode("abc"));
        assertArrayEquals(new long[]{101,100,102},tokenizer.encode("a".repeat(101)));
    }

    @Test public void normalizationPreservesCaseAndAccentsWhileSplittingBoundaries() throws Exception {
        VisualTokenizer tokenizer=tiny();
        assertArrayEquals(new long[]{101,110,111,108,109,107,102},tokenizer.encode("Un un café cafe!"));
        assertArrayEquals(new long[]{101,106,106,104,104,102},tokenizer.encode("a\u0000b a\u200db a\u2028a"));
        assertArrayEquals(new long[]{101,112,113,114,102},tokenizer.encode("中文\uD840\uDC00"));
        assertArrayEquals(new long[]{101,104,100,104,102},tokenizer.encode("a+a"));
    }

    @Test public void literalSpecialTokensAreProtectedBeforeNormalization() throws Exception {
        VisualTokenizer tokenizer=tiny();
        assertArrayEquals(new long[]{101,101,104,103,104,102,0,100,102},tokenizer.encode("[CLS]a[MASK]a[SEP][PAD][UNK]"));
        assertArrayEquals(new long[]{101,102},tokenizer.encode(" \t\n\uFFFD\u0000"));
    }

    @Test public void truncationAlwaysKeepsFinalSeparatorAndRejectsOversizedInput() throws Exception {
        VisualTokenizer tokenizer=tiny();
        long[] tokens=tokenizer.encode("a ".repeat(200));
        assertEquals(128,tokens.length);assertEquals(101,tokens[0]);assertEquals(102,tokens[127]);
        for(int i=1;i<127;i++)assertEquals(104,tokens[i]);
        assertArrayEquals(new long[]{101,100,102},tokenizer.encode("\uD83C\uDF05".repeat(2048)));
        for(String invalid:new String[]{null,"a".repeat(2049),"\uD83C\uDF05".repeat(2049)}) {
            try { tokenizer.encode(invalid);fail("Oversized or null text accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }

    @Test public void vocabularyCannotSilentlyChangeSpecialTokenIds() throws Exception {
        String[] vocab=tinyVocabulary();vocab[101]="wrong";
        try { new VisualTokenizer(new StringReader(String.join("\n",vocab)));fail("Wrong CLS ID accepted"); }
        catch(IOException expected) { }
        vocab=tinyVocabulary();vocab[115]="a";
        try { new VisualTokenizer(new StringReader(String.join("\n",vocab)));fail("Duplicate token accepted"); }
        catch(IOException expected) { }
    }

    @Test public void bundledMultilingualVocabularyMatchesReferenceSpanishTokenizer() throws Exception {
        Path vocab=Paths.get("src/main/assets/ml/semantic/wordpiece.txt");
        if(!Files.isRegularFile(vocab))vocab=Paths.get("app/src/main/assets/ml/semantic/wordpiece.txt");
        assertTrue("Bundled semantic WordPiece vocabulary is missing: "+vocab,Files.isRegularFile(vocab));
        VisualTokenizer tokenizer;
        try(InputStream input=Files.newInputStream(vocab)){tokenizer=new VisualTokenizer(input);}
        assertArrayEquals(new long[]{101,10153,16319,10110,10109,85462,102},tokenizer.encode("una persona en la playa"));
        assertArrayEquals(new long[]{101,11038,10178,10567,31481,29162,82790,102},tokenizer.encode("Un perro negro corriendo"));
        assertArrayEquals(new long[]{101,34551,117,55107,193,32676,102},tokenizer.encode("café, montaña y niños"));
        assertArrayEquals(new long[]{101,224,141,11482,10253,11559,10125,18257,10457,25911,136,102},tokenizer.encode("¿Dónde está el autobús?"));
        assertArrayEquals(new long[]{101,101,85462,102,102},tokenizer.encode("[CLS]playa[SEP]"));
        assertArrayEquals(new long[]{101,11357,11357,169,170,102},tokenizer.encode("a\u0085b a\u000Bb a\u2029b"));
    }

    private VisualTokenizer tiny() throws IOException {
        return new VisualTokenizer(new StringReader(String.join("\n",tinyVocabulary())));
    }

    private String[] tinyVocabulary() {
        String[] vocab=new String[116];
        for(int i=0;i<vocab.length;i++)vocab[i]="[unused"+i+"]";
        vocab[0]="[PAD]";vocab[100]="[UNK]";vocab[101]="[CLS]";vocab[102]="[SEP]";vocab[103]="[MASK]";
        String[] words={"a","##b","ab","!","café","cafe","Un","un","中","文","\uD840\uDC00","["};
        System.arraycopy(words,0,vocab,104,words.length);return vocab;
    }
}
