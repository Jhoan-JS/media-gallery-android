package local.ttlab.gallery;

final class VisualMath {
    static final int BANDS=8,BITS=12;

    static byte[] quantize(float[] values) {
        if(values.length==0||values.length>4096)throw new IllegalArgumentException("Dimensión visual inválida");
        double norm=0;for(float value:values){if(!Float.isFinite(value))throw new IllegalArgumentException("Vector visual inválido");norm+=(double)value*value;}
        if(norm==0)throw new IllegalArgumentException("Vector visual vacío");
        norm=Math.sqrt(norm);byte[] out=new byte[values.length];
        for(int i=0;i<out.length;i++)out[i]=(byte)Math.round(values[i]/norm*127);
        return out;
    }

    static float cosine(byte[] a,byte[] b) {
        if(a.length==0||a.length!=b.length||a.length>4096)throw new IllegalArgumentException("Dimensión visual inválida");
        long dot=0,aa=0,bb=0;
        for(int i=0;i<a.length;i++){dot+=(int)a[i]*b[i];aa+=(int)a[i]*a[i];bb+=(int)b[i]*b[i];}
        return aa==0||bb==0?0:(float)Math.max(-1,Math.min(1,dot/Math.sqrt((double)aa*bb)));
    }

    static int[] buckets(byte[] vector) {
        if(vector.length==0||vector.length>4096)throw new IllegalArgumentException("Dimensión visual inválida");
        int[] out=new int[BANDS];
        for(int band=0;band<BANDS;band++)for(int bit=0;bit<BITS;bit++){
            int sum=0;
            for(int i=0;i<vector.length;i++){
                int hash=(i+1)*0x9e3779b9^(band*BITS+bit+1)*0x85ebca6b;
                hash^=hash>>>16;hash*=0x7feb352d;hash^=hash>>>15;hash*=0x846ca68b;hash^=hash>>>16;
                sum+=(hash<0?-1:1)*vector[i];
            }
            if(sum>=0)out[band]|=1<<bit;
        }
        return out;
    }

    static int[] probes(int bucket){int[] out=new int[BITS+1];out[0]=bucket;for(int i=0;i<BITS;i++)out[i+1]=bucket^(1<<i);return out;}
}
