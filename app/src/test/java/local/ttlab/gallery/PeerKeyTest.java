package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PeerKeyTest {
    private static String validate(String key)throws Exception{
        java.lang.reflect.Method method;
        try{method=Library.class.getDeclaredMethod("checkedPeerKey",String.class);method.setAccessible(true);}catch(NoSuchMethodException missing){throw new AssertionError("Full Telegram peer identity validation is missing",missing);}
        try{return (String)method.invoke(null,key);}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException)throw (IllegalArgumentException)e.getCause();throw e;}
    }
    @Test public void preservesKindAndFull64BitIdentity()throws Exception{for(String key:new String[]{"user:1","group:2147483648","channel:9223372036854775807"})assertEquals(key,validate(key));}
    @Test public void rejectsTruncatedOrNoncanonicalPeerIdentity()throws Exception{for(String key:new String[]{null,"","channel:0","channel:-1","user:01","group:1.0","chat:1","channel:9223372036854775808","channel:1:2","channel: 1","user:18446744073709551615"})try{validate(key);fail("accepted "+key);}catch(IllegalArgumentException expected){}}
}
