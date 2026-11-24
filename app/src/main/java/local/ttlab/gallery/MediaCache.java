package local.ttlab.gallery;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDataSource;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.BitSet;
import java.util.function.BooleanSupplier;

final class MediaCache {
    static final long VIDEO_PREVIEW_BYTES=8L*1024*1024,VIDEO_PREVIEW_MILLIS=5000;
    final Vault vault;final Source source;private final Context context;
    VisualIndex visual;
    private final StorageIndex storage;
    private final Library library;
    private final boolean memoryOnly;
    private final Object coverLock=new Object();
    private final LruCache<String,Boolean> checkedCovers=new LruCache<>(2048);
    private final LruCache<String,Bitmap> selectedCovers=new LruCache<String,Bitmap>(4*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final LruCache<String,Boolean> registered=new LruCache<>(2048);
    final LruCache<String,Bitmap> previews=new LruCache<String,Bitmap>(16*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
    private final LruCache<String,byte[]> decoded=new LruCache<String,byte[]>(4*1024*1024){@Override protected int sizeOf(String key,byte[] bytes){return bytes.length;}};
    private final Object[] chunkLocks=new Object[16];
    MediaCache(Context c,Vault v,Source s){this(c,v,s,null);}
    MediaCache(Context c,Vault v,Source s,Library library){this(c,v,s,library,false);}
    MediaCache(Context c,Vault v,Source s,Library library,boolean memoryOnly){
        context=c;vault=v;source=s;this.library=library;this.memoryOnly=memoryOnly;
        storage=library==null||memoryOnly?null:new StorageIndex(library,v);
        for(int n=0;n<chunkLocks.length;n++)chunkLocks[n]=new Object();
    }
    private void writeCache(String id,byte[] bytes)throws Exception{if(!memoryOnly)vault.put(id,bytes);}
    private byte[] readCache(String id)throws Exception{return memoryOnly?null:vault.get(id);}
    private void track(Item item,boolean thumb,int index){track(item,thumb,index,false);}
    private void track(Item item,boolean thumb,int index,boolean force){if(storage==null)return;String id=item.cacheKey()+":"+(thumb?"thumb":index);if(!force&&registered.get(id)!=null)return;try{storage.record(item,thumb,index);registered.put(id,true);}catch(RuntimeException unavailable){/* Derived ownership can be rebuilt; media reads remain usable. */}}
    byte[] chunk(Item item,int index)throws Exception{return chunk(item,index,()->false);}
    byte[] chunk(Item item,int index,BooleanSupplier cancelled)throws Exception{
        checkPreviewActive(cancelled);
        int expected=(int)Math.min(Source.CHUNK,item.size-(long)index*Source.CHUNK);if(index<0||expected<=0)throw new IOException("Rango inválido");
        String id="media/"+item.cacheKey()+"/"+index;
        synchronized(chunkLocks[(id.hashCode()&Integer.MAX_VALUE)%chunkLocks.length]){byte[] bytes=cachedChunk(item,index);if(bytes==null){bytes=source.chunk(item,index);checkPreviewActive(cancelled);if(bytes.length!=expected)throw new IOException("Bloque incompleto o de otra versión");writeCache(id,bytes);decoded.put(id,bytes);track(item,false,index,true);}track(item,false,index);return bytes;}
    }
    byte[] memoryChunk(Item item,int index){return decoded.get("media/"+item.cacheKey()+"/"+index);}
    byte[] cachedChunk(Item item,int index)throws IOException{
        int expected=(int)Math.min(Source.CHUNK,item.size-(long)index*Source.CHUNK);if(index<0||expected<=0)throw new IOException("Rango inválido");
        String id="media/"+item.cacheKey()+"/"+index;byte[] bytes=decoded.get(id);if(bytes!=null)return bytes;
        try{bytes=readCache(id);}catch(java.security.GeneralSecurityException corrupt){try{vault.remove(id);}catch(Exception error){throw new IOException(error);}return null;}catch(Exception error){throw new IOException(error);}
        if(bytes!=null){if(bytes.length!=expected)throw new IOException("Bloque incompleto o de otra versión");decoded.put(id,bytes);track(item,false,index);}return bytes;
    }
    boolean hasChunk(Item item,int index){
        String id="media/"+item.cacheKey()+"/"+index;
        // Presence is only a scheduling hint; cachedChunk still authenticates bytes before use.
        return decoded.get(id)!=null||(!memoryOnly&&vault.file(id).isFile());
    }
    void fillRange(Item item,int first,int count)throws Exception{fillRange(item,first,count,()->false);}
    void fillRange(Item item,int first,int count,BooleanSupplier cancelled)throws Exception{
        fillRange(item,first,count,cancelled,new android.os.CancellationSignal());
    }
    void fillRange(Item item,int first,int count,BooleanSupplier cancelled,android.os.CancellationSignal signal)throws Exception{
        fillRange(item,first,count,cancelled,signal,index->{});
    }
    void fillRange(Item item,int first,int count,BooleanSupplier cancelled,android.os.CancellationSignal signal,java.util.function.IntConsumer published)throws Exception{
        int end=Math.min(item.chunks(),first+count),cursor=first;
        while(cursor<end){checkPreviewActive(cancelled);if(cachedChunk(item,cursor)!=null){published.accept(cursor++);continue;}
            int maximum=Math.min(end-cursor,source.rangeSize()/Source.CHUNK),amount=1;while(amount<maximum&&!hasChunk(item,cursor+amount))amount++;
            final int begin=cursor,blocks=amount;final int[] next={cursor};
            source.rangeBlocks(item,cursor,amount,signal,(index,block)->{checkPreviewActive(cancelled);int expected=(int)Math.min(Source.CHUNK,item.size-(long)index*Source.CHUNK);if(index!=next[0]||index>=begin+blocks||block.length!=expected)throw new IOException("Rango incompleto");String id="media/"+item.cacheKey()+"/"+index;writeCache(id,block);decoded.put(id,block);track(item,false,index,true);next[0]++;published.accept(index);});
            if(next[0]!=cursor+amount)throw new IOException("Rango incompleto");cursor+=amount;
        }
    }
    void clearMemory(){decoded.evictAll();previews.evictAll();selectedCovers.evictAll();checkedCovers.evictAll();}
    Bitmap thumbnail(Item item)throws Exception{
        try{return thumbnailOnly(item);}catch(java.io.FileNotFoundException absent){
            if(item.video()||item.size>Source.CHUNK)throw absent;
            byte[] bytes=source.chunk(item,0);Bitmap result=decodePreview(item,bytes);writeCache("thumb/"+item.cacheKey(),bytes);track(item,true,0,true);return result;
        }
    }
    Bitmap thumbnailOnly(Item item)throws Exception{
        Bitmap found=previews.get(item.cacheKey());if(found!=null)return found;
        String id="thumb/"+item.cacheKey();byte[] bytes;
        try{bytes=readCache(id);}catch(java.security.GeneralSecurityException corrupt){vault.remove(id);bytes=null;}
        if(bytes!=null){try{return decodePreview(item,bytes);}catch(IOException invalid){vault.remove(id);}}
        try{
            bytes=source.thumbnail(item);checkPreviewActive(()->false);Bitmap result=decodePreview(item,bytes);writeCache(id,bytes);track(item,true,0,true);return result;
        }catch(IOException|IllegalStateException unavailable){
            if(item.video())return rememberVideoThumbnail(item,false,()->false);
            throw unavailable;
        }
    }
    Bitmap availableThumbnail(Item item)throws Exception{
        Bitmap found=previews.get(item.cacheKey());if(found!=null)return found;
        byte[] bytes=readCache("thumb/"+item.cacheKey());
        if(bytes==null)throw new IOException("Visita la imagen en Biblioteca para guardar su miniatura antes de analizarla");
        return decodePreview(item,bytes);
    }
    private Bitmap decodePreview(Item item,byte[] bytes)throws IOException{
        track(item,true,0);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
        options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>512||options.outHeight/options.inSampleSize>512)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(result==null)throw new IOException("Miniatura no disponible");previews.put(item.cacheKey(),result);return result;
    }
    void remember(Item item,Bitmap bitmap)throws Exception{remember(item,bitmap,()->false);}
    private void remember(Item item,Bitmap bitmap,BooleanSupplier cancelled)throws Exception{byte[] bytes=previewBytes(bitmap);checkPreviewActive(cancelled);writeCache("thumb/"+item.cacheKey(),bytes);track(item,true,0,true);Bitmap preview=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(preview!=null){previews.put(item.cacheKey(),preview);}}

    Bitmap memoryPreview(Item item) {
        if(!item.video()||library==null)return previews.get(item.cacheKey());
        String key=item.cacheKey();
        Bitmap chosen=selectedCovers.get(key);
        if(chosen!=null)return chosen;
        return Boolean.FALSE.equals(checkedCovers.get(key))?previews.get(key):null;
    }

    Bitmap displayThumbnail(Item item,boolean prefetched)throws Exception {
        Bitmap cover=chosenCover(item);
        if(cover!=null)return cover;
        Bitmap automatic=prefetched?thumbnailOnly(item):thumbnail(item);
        cover=selectedCovers.get(item.cacheKey());
        return cover==null?automatic:cover;
    }

    Bitmap availableDisplayThumbnail(Item item)throws Exception {
        Bitmap cover=chosenCover(item);
        return cover==null?availableThumbnail(item):cover;
    }

    private Bitmap chosenCover(Item item) {
        if(library==null||!item.video())return null;
        synchronized(coverLock){
            String key=item.cacheKey();
            Bitmap cached=selectedCovers.get(key);
            if(cached!=null||Boolean.FALSE.equals(checkedCovers.get(key)))return cached;
            byte[] encoded=library.cover(item);
            Bitmap chosen=encoded==null?null:BitmapFactory.decodeByteArray(encoded,0,encoded.length);
            if(chosen!=null)selectedCovers.put(key,chosen);
            checkedCovers.put(key,chosen!=null);
            return chosen;
        }
    }
    void saveCover(Item item,Bitmap frame,BooleanSupplier cancelled)throws Exception{
        byte[] bytes=previewBytes(frame);
        synchronized(coverLock){
            checkPreviewActive(cancelled);
            if(library==null||!library.saveCover(item,bytes))throw new IOException("El video cambió o se alcanzó el límite de portadas");
            Bitmap chosen=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(chosen!=null)selectedCovers.put(item.cacheKey(),chosen);
            checkedCovers.put(item.cacheKey(),chosen!=null);
        }
    }
    void resetCover(Item item,BooleanSupplier cancelled)throws Exception{
        synchronized(coverLock){
            checkPreviewActive(cancelled);
            if(library!=null)library.removeCover(item);
            selectedCovers.remove(item.cacheKey());
            checkedCovers.put(item.cacheKey(),false);
        }
    }
    Bitmap videoFrame(Item item,int positionMs,int edge,boolean exact,BooleanSupplier cancelled)throws IOException {
        if(!item.video()||positionMs<0||edge<1||edge>384)throw new IllegalArgumentException("Fotograma inválido");
        checkPreviewActive(cancelled);
        long budget=exact?32L*1024*1024:VIDEO_PREVIEW_BYTES;
        long deadline=android.os.SystemClock.elapsedRealtime()+VIDEO_PREVIEW_MILLIS;
        BooleanSupplier stopped=()->cancelled.getAsBoolean()||android.os.SystemClock.elapsedRealtime()>=deadline;
        // shortcut: native decoding can outlive the read deadline; isolate it from playback and reject stale callbacks.
        try(MediaDataSource data=video(item,false,budget,VIDEO_PREVIEW_MILLIS,stopped);
            android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever()){
            retriever.setDataSource(data);
            Bitmap frame=retriever.getScaledFrameAtTime(positionMs*1000L,
                exact?android.media.MediaMetadataRetriever.OPTION_CLOSEST:android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,edge,edge);
            if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted()){
                if(frame!=null)frame.recycle();
                throw new InterruptedIOException("Fotograma cancelado");
            }
            if(frame==null)throw new IOException("El fotograma no está disponible");
            return frame;
        }catch(RuntimeException unsupported){throw new IOException("No se pudo extraer el fotograma",unsupported);}
    }
    static final int PREVIEW_EDGE=384;
    private static byte[] previewBytes(Bitmap original)throws IOException{
        float scale=Math.min(1f,(float)PREVIEW_EDGE/Math.max(original.getWidth(),original.getHeight()));Bitmap small=Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*scale)),Math.max(1,(int)(original.getHeight()*scale)),true);
        ByteArrayOutputStream output=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,output);if(small!=original)small.recycle();if(output.size()>Source.CHUNK)throw new IOException("Miniatura demasiado grande");return output.toByteArray();
    }
    void keepThumbnail(Item item)throws Exception{
        if(memoryOnly)throw new IOException("La fuente no guarda archivos sin conexión");
        String id="thumb/"+item.cacheKey();byte[] bytes=vault.get(id);
        if(bytes!=null){try{decodePreview(item,bytes);}catch(IOException invalid){vault.remove(id);bytes=null;}}
        if(bytes==null){
            Bitmap bitmap=null;
            if(item.video()){
                try{rememberVideoThumbnail(item,false,()->false);bytes=vault.get(id);}catch(IOException unsupported){return;}
                if(bytes!=null){vault.keep(id,bytes);track(item,true,0,true);}return;
            }else{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream stream=input(item)){BitmapFactory.decodeStream(stream,null,options);}
                if(options.outWidth<=0||options.outHeight<=0)return;options.inSampleSize=1;while(options.outWidth/options.inSampleSize>384||options.outHeight/options.inSampleSize>384)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;try(InputStream stream=input(item)){bitmap=BitmapFactory.decodeStream(stream,null,options);}
            }
            if(bitmap==null)return;try{bytes=previewBytes(bitmap);}finally{bitmap.recycle();}
        }
        vault.keep(id,bytes);track(item,true,0,true);
    }
    Bitmap rememberVideoThumbnail(Item item,boolean allowRemote,BooleanSupplier cancelled)throws Exception{
        if(!item.video())throw new IllegalArgumentException("Se requiere un video");checkPreviewActive(cancelled);
        String id="thumb/"+item.cacheKey();
        try{byte[] bytes=readCache(id);if(bytes!=null)return decodePreview(item,bytes);}catch(IOException|java.security.GeneralSecurityException absentOrInvalid){vault.remove(id);}
        Bitmap found=previews.get(item.cacheKey());if(found!=null){remember(item,found,cancelled);return previews.get(item.cacheKey());}
        // shortcut: the 5 s deadline is checked between reads; the existing provider RPC can still block until its timeout.
        long deadline=android.os.SystemClock.elapsedRealtime()+VIDEO_PREVIEW_MILLIS;
        BooleanSupplier stopped=()->cancelled.getAsBoolean()||android.os.SystemClock.elapsedRealtime()>=deadline;
        Bitmap frame=null;
        try(MediaDataSource data=video(item,allowRemote,VIDEO_PREVIEW_BYTES,VIDEO_PREVIEW_MILLIS,stopped);android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever()){
            retriever.setDataSource(data);frame=retriever.getScaledFrameAtTime(0,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,384,384);
            checkPreviewActive(stopped);if(frame==null)throw new IOException("Miniatura de video no disponible");
            remember(item,frame,stopped);return previews.get(item.cacheKey());
        }catch(SecurityException denied){throw denied;}catch(RuntimeException unsupported){throw new IOException("No se pudo extraer la miniatura de video",unsupported);}
        finally{if(frame!=null)frame.recycle();}
    }
    private static void checkPreviewActive(BooleanSupplier cancelled)throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("Miniatura cancelada");}
    InputStream input(Item item){final BufferedMediaSource data=new BufferedMediaSource(this,item);return new InputStream(){
        long position;
        @Override public int read()throws IOException{byte[] single=new byte[1];return read(single,0,1)==-1?-1:single[0]&255;}
        @Override public int read(byte[] out,int offset,int length)throws IOException{
            if(length==0)return 0;if(position>=item.size)return -1;
            int count=data.readAt(position,out,offset,length);if(count>0)position+=count;return count;
        }
        @Override public void close(){data.close();}
    };}
    MediaDataSource video(Item item){return new BufferedMediaSource(this,item);}
    MediaDataSource video(Item item,boolean allowRemote,long maxBytes,long maxMillis,BooleanSupplier cancelled){
        if(maxBytes<=0||maxMillis<=0)throw new IllegalArgumentException("Límite de miniatura inválido");
        long deadline=maxMillis==Long.MAX_VALUE?Long.MAX_VALUE:android.os.SystemClock.elapsedRealtime()+maxMillis;
        return new MediaDataSource(){
        int cached=-1;byte[] bytes;volatile boolean closed;long readBytes;final BitSet visited=new BitSet();
        private void active()throws IOException{if(closed)throw new IOException("Visor cerrado");checkPreviewActive(cancelled);if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("Tiempo de miniatura agotado");}
        @Override public synchronized int readAt(long position,byte[] out,int offset,int size)throws IOException{
            active();if(size==0)return 0;if(position>=item.size)return -1;if(position<0)throw new IOException("Posición inválida");
            int total=0;
            try{while(total<size&&position<item.size){active();int index=(int)(position/Source.CHUNK);if(index!=cached){
                    int expected=(int)Math.min(Source.CHUNK,item.size-(long)index*Source.CHUNK);
                    if(!visited.get(index)){if(expected>maxBytes-readBytes)throw new IOException("Límite de bytes de miniatura agotado");visited.set(index);readBytes+=expected;}
                    bytes=allowRemote?chunk(item,index):cachedChunk(item,index);if(bytes==null)throw new IOException("Bloque no disponible sin conexión");cached=index;active();
                }
                int start=(int)(position%Source.CHUNK),count=Math.min(size-total,bytes.length-start);if(count<=0)throw new IOException("Bloque incompleto");System.arraycopy(bytes,start,out,offset+total,count);total+=count;position+=count;
            }return total;}catch(Exception error){throw new IOException("No se pudo cargar el video",error);}
        }
        @Override public long getSize(){return item.size;}
        @Override public void close(){closed=true;}
    };}
    void keepChunk(Item item,int index,byte[] bytes)throws Exception{if(memoryOnly)throw new IOException("La fuente no guarda archivos sin conexión");vault.keep("media/"+item.cacheKey()+"/"+index,bytes);track(item,false,index,true);}
    void removeDownload(Item item)throws Exception{if(memoryOnly)return;for(int i=0;i<item.chunks();i++)vault.unpin("media/"+item.cacheKey()+"/"+i);vault.unpin("thumb/"+item.cacheKey());}
    static byte[] read(InputStream input,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("Respuesta demasiado grande");out.write(buffer,0,n);}return out.toByteArray();}
}
