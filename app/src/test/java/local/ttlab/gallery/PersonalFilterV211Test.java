package local.ttlab.gallery;

import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class PersonalFilterV211Test {
    private static Field field(String name)throws Exception{return MediaFilter.class.getDeclaredField(name);}
    @Test public void organizationReferencesSurviveCopyAndSeparateQueryKeys()throws Exception{
        MediaFilter f=new MediaFilter();String empty=f.key();field("albumId").setLong(f,17);field("tagId").setLong(f,29);field("recent").setBoolean(f,true);f.sort=6;f.check();
        MediaFilter copy=f.copy();assertEquals(17,field("albumId").getLong(copy));assertEquals(29,field("tagId").getLong(copy));assertTrue(field("recent").getBoolean(copy));assertEquals(f.key(),copy.key());assertNotEquals(empty,f.key());assertEquals(3,f.active());assertFalse(f.ascending());assertTrue(f.primary().contains("opened_at"));
        field("albumId").setLong(copy,18);assertNotEquals(f.key(),copy.key());
    }
    @Test public void rejectsNegativeReferencesAndUnknownSort()throws Exception{
        MediaFilter f=new MediaFilter();field("albumId").setLong(f,-1);reject(f);field("albumId").setLong(f,0);field("tagId").setLong(f,-1);reject(f);field("tagId").setLong(f,0);f.sort=7;reject(f);
    }
    private static void reject(MediaFilter f){try{f.check();fail("invalid filter accepted");}catch(IllegalArgumentException expected){}}
}
