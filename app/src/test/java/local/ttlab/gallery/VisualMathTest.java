package local.ttlab.gallery;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisualMathTest {
    @Test public void quantizationNormalizesAndPreservesDirection() {
        byte[] vector=VisualMath.quantize(new float[]{3,4,0});
        assertArrayEquals(new byte[]{76,102,0},vector);
        assertEquals(1f,VisualMath.cosine(vector,new byte[]{76,102,0}),0.00001f);
        assertEquals(0f,VisualMath.cosine(new byte[]{100,0},new byte[]{0,100}),0.00001f);
        assertEquals(-1f,VisualMath.cosine(new byte[]{100,0},new byte[]{-100,0}),0.00001f);
    }

    @Test public void badModelVectorsNeverEnterIndex() {
        for(float[] bad:new float[][]{new float[0],new float[]{0,0},new float[]{Float.NaN},new float[]{Float.POSITIVE_INFINITY}}) {
            try { VisualMath.quantize(bad);fail("invalid vector accepted"); }
            catch(IllegalArgumentException expected) { }
        }
        try { VisualMath.cosine(new byte[]{1},new byte[]{1,2});fail("dimensions accepted"); }
        catch(IllegalArgumentException expected) { }
    }

    @Test public void lshKeepsNearbyVectorsAndMultiprobesOneBitNeighbors() {
        float[] base=new float[1024];java.util.Random random=new java.util.Random(47);
        for(int i=0;i<base.length;i++)base[i]=(float)random.nextGaussian();
        float[] near=base.clone();for(int i=0;i<near.length;i++)near[i]+=0.01f;
        int[] a=VisualMath.buckets(VisualMath.quantize(base));
        int[] b=VisualMath.buckets(VisualMath.quantize(near));
        int overlap=0;for(int i=0;i<a.length;i++)if(a[i]==b[i])overlap++;
        assertTrue("nearby vectors should share a retrieval bucket",overlap>=4);
        assertArrayEquals(a,VisualMath.buckets(VisualMath.quantize(base)));
        int[] probes=VisualMath.probes(5);assertEquals(13,probes.length);assertEquals(5,probes[0]);
        for(int i=1;i<probes.length;i++)assertEquals(1,Integer.bitCount(5^probes[i]));
        int[] opposite=VisualMath.buckets(VisualMath.quantize(negate(base)));
        assertFalse("opposite vectors must not share exact buckets",Arrays.equals(a,opposite));
    }

    private float[] negate(float[] input){float[] out=input.clone();for(int i=0;i<out.length;i++)out[i]=-out[i];return out;}
}
