package local.ttlab.gallery;

import android.media.MediaDataSource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Exact startup/seeks and bounded read ahead; Tato also primes its slow extractor. */
final class BufferedMediaSource extends MediaDataSource {
    private final MediaCache media;private final Item item;private final int batchSize,readers;
    private final ExecutorService demand=Executors.newSingleThreadExecutor(),network;
    private static final class Read {
        final int first,count;final boolean ahead;final android.os.CancellationSignal signal=new android.os.CancellationSignal();FutureTask<Void> work;
        volatile int next;
        Read(int first,int count,boolean ahead){this.first=first;this.count=count;this.ahead=ahead;next=first;}
        synchronized void publish(int index){next=Math.max(next,index+1);notifyAll();}
        boolean contains(int n){return n>=first&&n<first+count;}
    }
    private final List<Read> pending=new ArrayList<>();
    private volatile boolean closed,readAhead;private volatile int prefetchedIndex=-1;
    private volatile Throwable failure;
    BufferedMediaSource(MediaCache media,Item item){this.media=media;this.item=item;batchSize=Math.max(4,Math.min(8,media.source.playbackBatch()));readers=Math.max(1,Math.min(4,media.source.playbackReaders()));network=Executors.newFixedThreadPool(readers);}
    void setReadAhead(boolean enabled){prefetchedIndex=-1;readAhead=enabled;}
    void beginPreparation(){if(batchSize==8)readAhead=true;}
    private synchronized Read request(int first,int count,boolean ahead)throws IOException {
        active();pending.removeIf(read->read.work.isDone());for(Read read:pending)if(read.contains(first)&&read.next<=first)return read;
        if(ahead&&pending.stream().filter(read->read.ahead).count()>=readers)return null;
        Read read=new Read(first,Math.min(count,item.chunks()-first),ahead);
        read.work=new FutureTask<Void>(()->{media.fillRange(item,read.first,read.count,()->closed,read.signal,read::publish);return null;}){
            @Override protected void done(){synchronized(read){read.notifyAll();}}
        };
        pending.add(read);(ahead?network:demand).execute(read.work);return read;
    }
    private synchronized Read inFlight(int index){for(Read read:pending)if(!read.work.isDone()&&read.contains(index)&&read.next<=index)return read;return null;}
    private byte[] await(int index)throws IOException {
        active();byte[] bytes=media.memoryChunk(item,index);if(bytes!=null)return bytes;
        Read read=inFlight(index);
        if(read==null){bytes=media.cachedChunk(item,index);if(bytes!=null)return bytes;read=request(index,1,false);}
        try {
            // A producer publishes authenticated bytes after its atomic write; don't decrypt that same write again.
            while(true){active();bytes=media.memoryChunk(item,index);if(bytes!=null)return bytes;
                if(read.next>index){bytes=media.cachedChunk(item,index);if(bytes!=null)return bytes;read=request(index,1,false);continue;}
                if(read.work.isDone()){read.work.get();bytes=media.cachedChunk(item,index);if(bytes==null)throw new IOException("Bloque incompleto");return bytes;}
                synchronized(read){if(read.next<=index&&!read.work.isDone())read.wait(250);}
            }
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Lectura cancelada");}
        catch(ExecutionException|CancellationException error){failure=error.getCause()==null?error:error.getCause();throw new IOException("No se pudo preparar el archivo",failure);}
        finally{if(Thread.currentThread().isInterrupted()&&!read.ahead)cancelDemand(read);}
    }
    private void cancelDemand(Read read){synchronized(this){if(!pending.remove(read))return;read.work.cancel(true);}cancelPipes(Collections.singletonList(read));}
    private void prefetch(int index)throws IOException {
        if(!readAhead||prefetchedIndex==index)return;int end=Math.min(item.chunks(),(index/batchSize+readers)*batchSize),cursor=index+1;boolean scheduled=false;
        while(cursor<end){active();if(media.hasChunk(item,cursor)){cursor++;continue;}if(!scheduled&&cursor>index+Math.max(1,readers-1)*batchSize)return;int count=Math.min(batchSize-cursor%batchSize,end-cursor);Read read=request(cursor,count,true);if(read==null)return;scheduled=true;cursor=read.first+read.count;}
        prefetchedIndex=index;
    }
    void prepare()throws IOException {
        active();await(0);active();
    }
    private void active()throws IOException{if(closed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Visor cerrado");}
    @Override public int readAt(long position,byte[] out,int offset,int size)throws IOException {
        active();if(position<0||offset<0||size<0||offset>out.length-size)throw new IOException("Lectura inválida");if(size==0)return 0;if(position>=item.size)return -1;
        // Distant reads can be concurrent audio/video tracks, rather than a user seek.
        int total=0;
        while(total<size&&position<item.size){active();int index=(int)(position/Source.CHUNK);byte[] bytes=await(index);active();
            int start=(int)(position%Source.CHUNK),count=Math.min(size-total,bytes.length-start);if(count<=0)throw new IOException("Bloque incompleto");System.arraycopy(bytes,start,out,offset+total,count);total+=count;position+=count;prefetch(index);
        }return total;
    }
    @Override public long getSize(){return item.size;}
    Throwable failure(){return failure;}
    private static void cancelPipes(List<Read> reads){if(!reads.isEmpty())new Thread(()->{for(Read read:reads)read.signal.cancel();},"gallery-cancel-media").start();}
    @Override public synchronized void close(){if(closed)return;closed=true;for(Read read:pending)read.work.cancel(true);cancelPipes(new ArrayList<>(pending));pending.clear();demand.shutdownNow();network.shutdownNow();}
}
