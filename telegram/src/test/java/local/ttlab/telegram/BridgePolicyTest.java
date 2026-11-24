package local.ttlab.telegram;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgePolicyTest {
    @Test public void basicGroupsHaveNoAccessHashButUsersAndChannelsRequireOne(){
        assertEquals("0",BridgePolicy.accessHash("group",""));assertEquals("0",BridgePolicy.accessHash("group",null));
        assertEquals("-9223372036854775808",BridgePolicy.accessHash("channel","-9223372036854775808"));
        for(String kind:new String[]{"channel","user"})try{BridgePolicy.accessHash(kind,"");fail(kind);}catch(IllegalArgumentException expected){}
    }
    @Test public void rangesNeverOverflowOrCrossFile() {
        assertTrue(BridgePolicy.range(0, 524288, 524288));
        assertTrue(BridgePolicy.range(524288, 1, 524289));
        assertFalse(BridgePolicy.range(-1, 1, 10));
        assertFalse(BridgePolicy.range(0, 0, 10));
        assertFalse(BridgePolicy.range(0, 524289, 600000));
        assertFalse(BridgePolicy.range(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertFalse(BridgePolicy.range(9, 2, 10));
    }
    @Test public void accountMustBeOpaqueAndExactlyPinned() {
        String account="a".repeat(64);
        assertTrue(BridgePolicy.account(account));
        assertFalse(BridgePolicy.account("123456"));
        assertFalse(BridgePolicy.account("A".repeat(64)));
        assertTrue(BridgePolicy.sameAccount(account,account));
        assertFalse(BridgePolicy.sameAccount(account,"b".repeat(64)));
        assertFalse(BridgePolicy.sameAccount(null,account));
    }
    @Test public void peerIdsStayDecimalStringsWithoutTruncation() {
        assertTrue(BridgePolicy.decimal("9223372036854775807",false));
        assertTrue(BridgePolicy.decimal("-9223372036854775808",true));
        assertFalse(BridgePolicy.decimal("9223372036854775808",false));
        assertFalse(BridgePolicy.decimal("../1",true));
        assertFalse(BridgePolicy.decimal("1e6",false));
        assertFalse(BridgePolicy.decimal("-1",false));
    }
    @Test public void errorsNeverEchoRemoteOrCredentials() {
        assertEquals("E_TELEGRAM_FLOOD_WAIT_60",BridgePolicy.error(new Exception("FLOOD_WAIT_60")));
        assertEquals("E_TELEGRAM_UNAVAILABLE",BridgePolicy.error(new Exception("secret 123 +1809")));
        assertEquals("E_MEDIA_CHANGED",BridgePolicy.error(new Exception("MEDIA_CHANGED")));
    }
}
