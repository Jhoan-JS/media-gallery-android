package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SourceAccountTest {
    private static final String A="a".repeat(64),B="b".repeat(64);
    @Test public void freshOwnerPinsAuthorizedOpaqueAccount(){assertEquals(A,SourceAccount.accept(null,A,true,false));}
    @Test public void sameAccountReopensExistingCatalogue(){assertEquals(A,SourceAccount.accept(A,A,true,true));}
    @Test public void differentAccountCannotReplaceExistingOwnership(){try{SourceAccount.accept(A,B,true,true);fail();}catch(SecurityException expected){}}
    @Test public void oldUnboundOwnerArchiveStaysLocal(){try{SourceAccount.accept(null,A,true,true);fail();}catch(SecurityException expected){}}
    @Test public void laboratoryLegacyCatalogueKeepsItsSeparateTestTesGuard(){assertEquals(A,SourceAccount.accept(null,A,false,true));}
    @Test public void malformedIdentityIsRejected(){try{SourceAccount.accept(null,"",true,false);fail();}catch(SecurityException expected){}}
}
