package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;
public class DateSectionsTest {
 @Test public void sectionsMapTheirActualCountsRatherThanAssumingEqualMonths(){DateSections s=new DateSections(new String[]{"2026-11","2026-10","0000-00"},new int[]{2,100,1});assertEquals(103,s.total);assertEquals(0,s.position(0));assertEquals(2,s.position(1));assertEquals(102,s.position(2));assertEquals(0,s.section(1));assertEquals(1,s.section(2));assertEquals(1,s.section(101));assertEquals(2,s.section(102));assertEquals(2,s.section(900));assertEquals(0,s.section(-1));assertTrue(s.labels[0].contains("2026"));assertEquals("Sin fecha",s.labels[2]);}
 @Test public void emptyAndBoundaryRequestsAreSafe(){DateSections s=DateSections.EMPTY;assertEquals(0,s.position(42));assertEquals(0,s.section(42));assertEquals(0,s.labels.length);}
 @Test public void rejectsMalformedCountsAndOverflow(){reject(new String[]{"2026-01"},new int[]{});reject(new String[]{"2026-01"},new int[]{0});reject(new String[]{"2026-01","2026-02"},new int[]{Integer.MAX_VALUE,1});}
 private static void reject(String[] keys,int[] counts){try{new DateSections(keys,counts);fail("invalid index accepted");}catch(IllegalArgumentException expected){}}
}
