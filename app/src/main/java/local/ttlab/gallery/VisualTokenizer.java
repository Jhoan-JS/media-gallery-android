package local.ttlab.gallery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class VisualTokenizer {
    private static final String[] SPECIAL={"[PAD]","[UNK]","[CLS]","[SEP]","[MASK]"};
    private static final int[] SPECIAL_IDS={0,100,101,102,103};
    private final Map<String,Integer> vocabulary=new HashMap<>();

    VisualTokenizer(InputStream input) throws IOException {
        this(new InputStreamReader(input,StandardCharsets.UTF_8));
    }

    VisualTokenizer(Reader input) throws IOException {
        BufferedReader reader=new BufferedReader(input);
        String token;int id=0;
        while((token=reader.readLine())!=null) {
            if(id>=200000||token.isEmpty()||vocabulary.put(token,id++)!=null)
                throw new IOException("Vocabulario visual inválido");
        }
        for(int i=0;i<SPECIAL.length;i++)
            if(!Integer.valueOf(SPECIAL_IDS[i]).equals(vocabulary.get(SPECIAL[i])))
                throw new IOException("Tokens especiales visuales inválidos");
    }

    long[] encode(String text) {
        if(text==null||text.length()>4096||text.codePointCount(0,text.length())>2048)
            throw new IllegalArgumentException("La descripción admite hasta 2048 caracteres");
        long[] tokens=new long[128];tokens[0]=101;
        int count=1,start=0;
        for(int offset=0;offset<text.length()&&count<127;) {
            int special=-1;
            if(text.charAt(offset)=='[')
                for(int i=0;i<SPECIAL.length;i++)if(text.startsWith(SPECIAL[i],offset)){special=i;break;}
            if(special<0){offset+=Character.charCount(text.codePointAt(offset));continue;}
            count=basic(text.substring(start,offset),tokens,count);
            if(count<127)tokens[count++]=SPECIAL_IDS[special];
            offset+=SPECIAL[special].length();start=offset;
        }
        if(count<127)count=basic(text.substring(start),tokens,count);
        tokens[count++]=102;
        return Arrays.copyOf(tokens,count);
    }

    private int basic(String text,long[] tokens,int count) {
        StringBuilder word=new StringBuilder();
        for(int offset=0;offset<text.length()&&count<127;) {
            int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
            int type=Character.getType(cp);
            if(cp==0||cp==0xfffd||((type==Character.CONTROL||type==Character.FORMAT)&&cp!='\t'&&cp!='\n'&&cp!='\r'))continue;
            boolean space=Character.isWhitespace(cp)||Character.isSpaceChar(cp);
            boolean separate=punctuation(cp,type)||chinese(cp);
            if(space||separate) {
                count=wordpiece(word.toString(),tokens,count);word.setLength(0);
                if(separate&&count<127)count=wordpiece(new String(Character.toChars(cp)),tokens,count);
            } else word.appendCodePoint(cp);
        }
        return wordpiece(word.toString(),tokens,count);
    }

    private int wordpiece(String word,long[] tokens,int count) {
        if(word.isEmpty()||count>=127)return count;
        if(word.codePointCount(0,word.length())>100){tokens[count++]=100;return count;}
        int[] pieces=new int[100];int size=0,start=0;
        while(start<word.length()) {
            int end=word.length();Integer id=null;
            while(end>start) {
                id=vocabulary.get((start==0?"":"##")+word.substring(start,end));
                if(id!=null)break;
                end=word.offsetByCodePoints(end,-1);
            }
            if(id==null){tokens[count++]=100;return count;}
            pieces[size++]=id;start=end;
        }
        for(int i=0;i<size&&count<127;i++)tokens[count++]=pieces[i];
        return count;
    }

    private static boolean punctuation(int cp,int type) {
        return cp>=33&&cp<=47||cp>=58&&cp<=64||cp>=91&&cp<=96||cp>=123&&cp<=126
                ||type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION
                ||type==Character.START_PUNCTUATION||type==Character.END_PUNCTUATION
                ||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION
                ||type==Character.OTHER_PUNCTUATION;
    }

    private static boolean chinese(int cp) {
        return cp>=0x4e00&&cp<=0x9fff||cp>=0x3400&&cp<=0x4dbf||cp>=0x20000&&cp<=0x2a6df
                ||cp>=0x2a700&&cp<=0x2b73f||cp>=0x2b740&&cp<=0x2b81f||cp>=0x2b820&&cp<=0x2ceaf
                ||cp>=0xf900&&cp<=0xfaff||cp>=0x2f800&&cp<=0x2fa1f;
    }
}
