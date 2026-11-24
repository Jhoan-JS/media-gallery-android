package local.ttlab.gallery;
import android.graphics.Bitmap;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
/** One grid owns its bounded work; leaving it cancels only that grid's requests. */
final class PreviewRequests implements AutoCloseable {
 private final MediaCache media;private final BooleanSupplier allowed;private final Consumer<Runnable> ui;
 private final ThreadPoolExecutor pool;private final boolean localOnly;
 private final Map<String,Work> jobs=new HashMap<>();private boolean closed;
 PreviewRequests(MediaCache media,ThreadPoolExecutor pool,BooleanSupplier allowed,Consumer<Runnable> ui){this(media,pool,allowed,ui,false);}
 PreviewRequests(MediaCache media,ThreadPoolExecutor pool,BooleanSupplier allowed,Consumer<Runnable> ui,boolean localOnly){this.media=media;this.pool=pool;this.allowed=allowed;this.ui=ui;this.localOnly=localOnly;}
 void request(Item item,Object owner,Consumer<Bitmap> ready){if(closed||!allowed.getAsBoolean())return;String key=item.cacheKey();Work work=jobs.get(key);if(work==null){if(jobs.size()>=128)return;work=new Work(item,owner==null);jobs.put(key,work);try{pool.execute(work);}catch(RejectedExecutionException busy){jobs.remove(key);return;}}if(owner!=null&&ready!=null)work.listeners.put(owner,ready);}
 void retain(Set<String> keys){List<Work> obsolete=new ArrayList<>();for(Iterator<Map.Entry<String,Work>> it=jobs.entrySet().iterator();it.hasNext();){Map.Entry<String,Work> entry=it.next();if(!keys.contains(entry.getKey())){it.remove();pool.remove(entry.getValue());obsolete.add(entry.getValue());}}for(Work work:obsolete)work.cancel(true);}
 void cancel(){List<Work> cancelled=new ArrayList<>(jobs.values());jobs.clear();for(Work work:cancelled)pool.remove(work);for(Work work:cancelled)work.cancel(true);}
 @Override public void close(){closed=true;cancel();}
 private final class Work extends FutureTask<Bitmap>{final String key;final Item item;final boolean prefetched;final Map<Object,Consumer<Bitmap>> listeners=new HashMap<>();Work(Item item,boolean prefetched){super(()->{if(!allowed.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException();return localOnly?media.availableDisplayThumbnail(item):media.displayThumbnail(item,prefetched);});this.item=item;this.prefetched=prefetched;key=item.cacheKey();}
  @Override protected void done(){ui.accept(()->{if(jobs.get(key)!=this)return;jobs.remove(key);if(closed||isCancelled()||!allowed.getAsBoolean())return;Bitmap bitmap=null;try{bitmap=get();}catch(Exception unavailable){}if(bitmap==null&&prefetched&&!listeners.isEmpty()){for(Map.Entry<Object,Consumer<Bitmap>> entry:listeners.entrySet())request(item,entry.getKey(),entry.getValue());return;}for(Consumer<Bitmap> listener:listeners.values())listener.accept(bitmap);});}
 }
}
