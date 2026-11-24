package local.ttlab.gallery;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaFilterTest {
    @Test public void collectionsHaveIndependentQueryKeysAndValidateTheirRange(){MediaFilter original=new MediaFilter();String all=original.key();original.collection=1;assertNotEquals(all,original.key());assertEquals(1,original.copy().collection);assertEquals(1,original.active());original.collection=4;reject(original);}
    @Test public void snapshotsKeepAllOptionsAndQueryBoundaries(){MediaFilter f=new MediaFilter();f.query="cat\n1:dog";f.channel=22;f.watched=1;f.availability=2;f.sort=3;f.minBytes=1024;MediaFilter copy=f.copy();assertEquals(f.key(),copy.key());copy.query="cat\n1:dogs";assertNotEquals(f.key(),copy.key());assertEquals(2,copy.availability);assertEquals(1,copy.watched);}
    @Test public void everySortIsAnAllowlistedStableOrder(){String[] primary={"date","date","size","size","duration","name COLLATE NOCASE"};for(int n=0;n<6;n++){MediaFilter f=new MediaFilter();f.sort=n;f.check();assertEquals(primary[n],f.primary());assertTrue(f.order().endsWith("channel ASC"));assertEquals(n==1||n==3||n==5,f.ascending());if(n==1)assertEquals("date ASC,id ASC,channel ASC",f.order());}}
    @Test public void rejectsInvalidRangesAndChoices(){MediaFilter f=new MediaFilter();f.sort=7;reject(f);f.sort=0;f.minBytes=100;f.maxBytes=20;reject(f);f.minBytes=0;f.maxBytes=0;f.minSeconds=-1;reject(f);f.minSeconds=0;f.format=8;reject(f);}
    @Test public void formatAndActiveCountAreActualRestrictions(){MediaFilter f=new MediaFilter();f.format=5;f.watched=1;f.sort=1;f.check();assertEquals("video/mp4",f.mime());assertEquals(2,f.active());}
    private static void reject(MediaFilter f){try{f.check();fail("invalid filter accepted");}catch(IllegalArgumentException expected){}}
}
