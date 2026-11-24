package local.ttlab.gallery;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;

/** Pinned multilingual CLIP pair. One CPU session at a time; no network or telemetry. */
final class VisualEmbedding implements AutoCloseable {
    static final String ASSET="ml/semantic/vision.onnx";
    static final String SHA256="583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299";
    static final String MODEL="clip-b32-multilingual-int8-v1/512/crop-v1";
    private static final String TEXT_SHA="3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7";
    private final Context context;
    private final OrtEnvironment environment;
    private OrtSession session;
    private String kind="";
    private MappedByteBuffer mapped;
    private VisualTokenizer tokenizer;
    private float[] projection;
    // Installed APK assets cannot change inside this process; pausing need not rehash 227 MB.
    private static final Set<String> verified=Collections.synchronizedSet(new HashSet<>());
    VisualEmbedding(Context c)throws Exception{context=c.getApplicationContext();android.system.Os.setenv("ORT_DISABLE_TELEMETRY","1",true);environment=OrtEnvironment.getEnvironment();environment.setTelemetry(false);}
    private OrtSession session(String name)throws Exception{
        if(session!=null&&kind.equals(name))return session;
        release();String asset="ml/semantic/"+name+".onnx";
        if(!verified.contains(name)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=context.getAssets().open(asset)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!(name.equals("vision")?SHA256:TEXT_SHA).contentEquals(hash))throw new SecurityException("Modelo local no válido");verified.add(name);
        }
        try(AssetFileDescriptor fd=context.getAssets().openFd(asset);FileInputStream input=new FileInputStream(fd.getFileDescriptor())){mapped=input.getChannel().map(FileChannel.MapMode.READ_ONLY,fd.getStartOffset(),fd.getLength());}
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false);
            session=environment.createSession(mapped,options);kind=name;return session;
        }
    }
    byte[] embed(Bitmap bitmap)throws Exception{
        float[] pixels=preprocess(bitmap);OrtSession active=session("vision");
        try(OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(pixels),new long[]{1,3,224,224});OrtSession.Result result=active.run(Collections.singletonMap("pixel_values",tensor))){
            float[] vector=((float[][])result.get("image_embeds").get().getValue())[0];if(vector.length!=512)throw new IllegalStateException("Dimensión visual no válida");return VisualMath.quantize(vector);
        }
    }
    byte[] text(String value)throws Exception{
        if(tokenizer==null){try(InputStream in=context.getAssets().open("ml/semantic/wordpiece.txt")){tokenizer=new VisualTokenizer(in);}}
        if(projection==null){byte[] bytes;try(InputStream in=context.getAssets().open("ml/semantic/projection.f32")){bytes=MediaCache.read(in,512*768*4);}if(bytes.length!=512*768*4)throw new IOException("Proyección local no válida");projection=new float[512*768];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(projection);}
        long[] ids=tokenizer.encode(value),mask=new long[ids.length];Arrays.fill(mask,1);OrtSession active=session("text");
        try(OnnxTensor input=OnnxTensor.createTensor(environment,new long[][]{ids});OnnxTensor attention=OnnxTensor.createTensor(environment,new long[][]{mask})){
            Map<String,OnnxTensor> inputs=new HashMap<>();inputs.put("input_ids",input);inputs.put("attention_mask",attention);
            try(OrtSession.Result result=active.run(inputs)){
                float[][] hidden=((float[][][])result.get("last_hidden_state").get().getValue())[0];float[] mean=new float[768],vector=new float[512];
                for(float[] token:hidden)for(int d=0;d<768;d++)mean[d]+=token[d]/hidden.length;
                for(int row=0;row<512;row++)for(int d=0;d<768;d++)vector[row]+=mean[d]*projection[row*768+d];return VisualMath.quantize(vector);
            }
        }
    }
    // Center crop + bicubic RGB sampling preserves the paired CLIP input's aspect ratio.
    private static float[] preprocess(Bitmap bitmap){
        int width=bitmap.getWidth(),height=bitmap.getHeight();int[] source=new int[width*height];bitmap.getPixels(source,0,width,0,0,width,height);
        float[] out=new float[3*224*224];float scale=Math.min(width,height)/224f,left=(width-224*scale)/2,top=(height-224*scale)/2;
        float[] mean={.48145466f,.4578275f,.40821073f},std={.26862954f,.26130258f,.27577711f};
        for(int y=0;y<224;y++)for(int x=0;x<224;x++){
            float sx=left+(x+.5f)*scale-.5f,sy=top+(y+.5f)*scale-.5f;int ix=(int)Math.floor(sx),iy=(int)Math.floor(sy);float r=0,g=0,b=0,total=0;
            for(int yy=iy-1;yy<=iy+2;yy++)for(int xx=ix-1;xx<=ix+2;xx++){
                float weight=cubic(sx-xx)*cubic(sy-yy);int rgb=source[Math.max(0,Math.min(height-1,yy))*width+Math.max(0,Math.min(width-1,xx))];total+=weight;r+=((rgb>>16)&255)*weight;g+=((rgb>>8)&255)*weight;b+=(rgb&255)*weight;
            }
            int pos=y*224+x;out[pos]=(Math.max(0,Math.min(255,r/total))/255f-mean[0])/std[0];out[224*224+pos]=(Math.max(0,Math.min(255,g/total))/255f-mean[1])/std[1];out[2*224*224+pos]=(Math.max(0,Math.min(255,b/total))/255f-mean[2])/std[2];
        }return out;
    }
    private static float cubic(float value){float x=Math.abs(value);return x<=1?1.5f*x*x*x-2.5f*x*x+1:x<2?-.5f*x*x*x+2.5f*x*x-4*x+2:0;}
    void release()throws OrtException{if(session!=null)session.close();session=null;kind="";mapped=null;}
    @Override public void close(){try{release();}catch(OrtException ignored){}projection=null;tokenizer=null;}
}
