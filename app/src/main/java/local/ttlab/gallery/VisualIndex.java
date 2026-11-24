package local.ttlab.gallery;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.io.DataInputStream;

/** Encrypted derived index; explicit catalogue scans fetch thumbnails only. */
final class VisualIndex implements AutoCloseable {
    static final class Match {
        final Item item;final float score;
        Match(Item item,float score){this.item=item;this.score=score;}
    }
    static final class Group {
        final String id;final Item representative;final int count;
        Group(String id,Item representative,int count){this.id=id;this.representative=representative;this.count=count;}
    }
    static final class Topic {
        final String id,title;final Item representative;final int count;
        Topic(String id,String title,Item item,int count){this.id=id;this.title=title;representative=item;this.count=count;}
    }
    private static final class Label {String id,title;byte[] vector;}
    private static final String MODEL=VisualEmbedding.MODEL;
    private static final String ITEM="m.id,m.channel,m.name,m.mime,m.size,m.date,m.width,m.height,m.duration,m.revision,m.content_key";
    private static final String VALID=" JOIN media m ON m.channel=e.channel AND m.id=e.id AND m.revision=e.revision ";
    private static final float GROUP_THRESHOLD=.88f;
    private final Context context;
    private final Library library;
    private final Vault vault;
    private final ThreadPoolExecutor worker;
    private final ExecutorService catalogueWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);r.run();},"gallery-thumbnail-scan");return t;});
    private final List<Label> labels=new ArrayList<>();
    private volatile int catalogueEpoch;
    private final Set<String> pending=new HashSet<>();
    private final LinkedHashMap<String,Boolean> recent=new LinkedHashMap<String,Boolean>(256,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>1024;}
    };
    private VisualEmbedding embedding;
    private volatile boolean releaseRequested;
    private final Set<CompletableFuture<byte[]>> textRequests=new HashSet<>();
    private volatile boolean closed;
    private volatile String error="";
    private volatile boolean scanning;
    private volatile boolean catalogueMode;
    private boolean scanQueued;
    private volatile boolean scanFinished;
    private long scanChannel=Long.MIN_VALUE,scanId=Long.MIN_VALUE;
    private volatile int scanVisited;
    private volatile int scanMissing;
    private volatile Thread workerThread;

    VisualIndex(Context context,Library library,Vault vault) {
        if(vault==null)throw new IllegalArgumentException("Falta el almacén cifrado");
        this.context=context.getApplicationContext();this.library=library;this.vault=vault;
        library.db.beginTransaction();
        try {
            library.db.execSQL("CREATE TABLE IF NOT EXISTS visual_features(channel INTEGER NOT NULL,id INTEGER NOT NULL,model TEXT NOT NULL,topic TEXT NOT NULL,phash INTEGER NOT NULL,tone INTEGER NOT NULL,near_group TEXT NOT NULL,anchor_hash INTEGER NOT NULL,anchor_tone INTEGER NOT NULL,PRIMARY KEY(channel,id))");
            library.db.execSQL("CREATE TABLE IF NOT EXISTS visual_theme_groups(group_id TEXT PRIMARY KEY,model TEXT NOT NULL,title TEXT NOT NULL,vector BLOB NOT NULL)");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_theme_model ON visual_theme_groups(model)");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_feature_topic ON visual_features(model,topic,channel,id)");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_feature_near ON visual_features(model,near_group,channel,id)");
            library.db.execSQL("CREATE TABLE IF NOT EXISTS visual_groups(group_id TEXT PRIMARY KEY,model TEXT NOT NULL,vector BLOB NOT NULL)");
            library.db.execSQL("CREATE TABLE IF NOT EXISTS visual_embeddings(channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,model TEXT NOT NULL,vector BLOB NOT NULL,group_id TEXT NOT NULL,PRIMARY KEY(channel,id))");
            library.db.execSQL("CREATE TRIGGER IF NOT EXISTS visual_feature_ad AFTER DELETE ON visual_embeddings BEGIN DELETE FROM visual_features WHERE channel=old.channel AND id=old.id; END");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_group_members ON visual_embeddings(model,group_id,channel,id)");
            library.db.execSQL("CREATE TABLE IF NOT EXISTS visual_buckets(model TEXT NOT NULL,band INTEGER NOT NULL,bucket INTEGER NOT NULL,channel INTEGER NOT NULL,id INTEGER NOT NULL,PRIMARY KEY(model,band,bucket,channel,id)) WITHOUT ROWID");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_bucket_item ON visual_buckets(channel,id)");
            library.db.execSQL("CREATE INDEX IF NOT EXISTS visual_member_group ON visual_embeddings(group_id)");
            library.db.execSQL("CREATE TRIGGER IF NOT EXISTS visual_embedding_ad AFTER DELETE ON visual_embeddings BEGIN DELETE FROM visual_buckets WHERE channel=old.channel AND id=old.id; DELETE FROM visual_groups WHERE group_id=old.group_id AND NOT EXISTS(SELECT 1 FROM visual_embeddings WHERE group_id=old.group_id); END");
            library.db.execSQL("CREATE TRIGGER IF NOT EXISTS visual_media_ad AFTER DELETE ON media BEGIN DELETE FROM visual_embeddings WHERE channel=old.channel AND id=old.id; END");
            library.db.execSQL("CREATE TRIGGER IF NOT EXISTS visual_media_au AFTER UPDATE OF revision ON media WHEN old.revision<>new.revision BEGIN DELETE FROM visual_embeddings WHERE channel=old.channel AND id=old.id; END");
            library.db.setTransactionSuccessful();
        } finally { library.db.endTransaction(); }
        try(DataInputStream in=new DataInputStream(context.getAssets().open("ml/semantic/topics.bin"))){int count=in.readInt();if(count<1||count>32)throw new IllegalStateException("Temas locales no válidos");for(int n=0;n<count;n++){Label label=new Label();label.id=in.readUTF();label.title=in.readUTF();float[] values=new float[512];for(int d=0;d<values.length;d++)values[d]=in.readFloat();label.vector=VisualMath.quantize(values);labels.add(label);}}catch(Exception failure){throw new IllegalStateException("No se pudieron cargar los temas locales",failure);}
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),task->{
            Thread thread=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"gallery-visual");
            thread.setPriority(Thread.MIN_PRIORITY);workerThread=thread;return thread;
        });
    }

    /** Copies a small supplied thumbnail; model loading, inference and SQL run on the worker. */
    synchronized boolean offer(Item item,Bitmap bitmap) {
        if(closed||!eligible(item,bitmap)||pending.contains(item.cacheKey())||recent.containsKey(item.cacheKey())||worker.getQueue().remainingCapacity()==0)return false;
        Bitmap copy=snapshot(bitmap);String key=item.cacheKey();pending.add(key);
        try { worker.execute(new Job(item,copy));return true; }
        catch(java.util.concurrent.RejectedExecutionException stopped){pending.remove(key);copy.recycle();return false;}
    }

    /** Explicit synchronous path for background callers; never call from the main thread. */
    void index(Item item,Bitmap bitmap)throws Exception {
        index(item,bitmap,()->true);
    }
    void index(Item item,Bitmap bitmap,BooleanSupplier allowed)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("El análisis visual requiere un hilo de trabajo");
        if(!eligible(item,bitmap))throw new IllegalArgumentException("Se requiere una miniatura de imagen de hasta 1024 px");
        Bitmap copy=snapshot(bitmap);CompletableFuture<Void> future=new CompletableFuture<>();
        synchronized(this) {
            if(closed){copy.recycle();throw new IllegalStateException("Índice visual cerrado");}
            try { worker.execute(new Job(item,copy,future,allowed)); }
            catch(java.util.concurrent.RejectedExecutionException full){copy.recycle();throw full;}
        }
        try { future.get(); }
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(cause);}
    }

    private static boolean eligible(Item item,Bitmap bitmap) {
        return item!=null&&(item.mime.startsWith("image/")||item.video())&&bitmap!=null&&!bitmap.isRecycled()&&bitmap.getWidth()>0&&bitmap.getHeight()>0&&bitmap.getWidth()<=1024&&bitmap.getHeight()<=1024;
    }
    private static Bitmap snapshot(Bitmap source) {
        float scale=Math.min(1,384f/Math.max(source.getWidth(),source.getHeight()));Bitmap copy=Bitmap.createBitmap(Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),Bitmap.Config.ARGB_8888);
        try {new Canvas(copy).drawBitmap(source,null,new Rect(0,0,copy.getWidth(),copy.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));return copy;}
        catch(RuntimeException failure){copy.recycle();throw failure;}
    }
    private final class Job implements Runnable {
        final Item item;final Bitmap bitmap;final CompletableFuture<Void> completion;final BooleanSupplier allowed;
        Job(Item item,Bitmap bitmap){this(item,bitmap,null);}
        Job(Item item,Bitmap bitmap,CompletableFuture<Void> completion){this(item,bitmap,completion,()->true);}
        Job(Item item,Bitmap bitmap,CompletableFuture<Void> completion,BooleanSupplier allowed){this.item=item;this.bitmap=bitmap;this.completion=completion;this.allowed=allowed;}
        @Override public void run() {
            try {if(!allowed.getAsBoolean())throw new java.util.concurrent.CancellationException();store(item,bitmap,allowed);if(completion!=null)completion.complete(null);}
            catch(java.util.concurrent.CancellationException cancelled){if(completion!=null)completion.completeExceptionally(cancelled);}
            catch(Exception|LinkageError failure){error="No se pudo analizar la miniatura local";if(completion!=null)completion.completeExceptionally(failure);}
            finally {release();}
        }
        void release(){bitmap.recycle();synchronized(VisualIndex.this){pending.remove(item.cacheKey());scheduleScan();}releaseModelIfRequested();}
    }

    synchronized void startAvailable() {
        if(closed||scanning)return;
        catalogueMode=false;releaseRequested=false;
        if(scanFinished){scanChannel=Long.MIN_VALUE;scanId=Long.MIN_VALUE;scanVisited=0;scanMissing=0;scanFinished=false;}
        error="";scanning=true;scheduleScan();
    }
    synchronized void pauseAvailable(){scanning=false;catalogueEpoch++;releaseRequested=true;if(!closed&&!worker.isShutdown())try{worker.execute(this::releaseModelIfRequested);}catch(java.util.concurrent.RejectedExecutionException busy){}}
    private void releaseModelIfRequested(){if(Thread.currentThread()==workerThread&&releaseRequested&&!scanning){if(embedding!=null)embedding.close();embedding=null;releaseRequested=false;}}
    boolean scanning(){return scanning;}
    String status(){if(!error.isEmpty())return error;return scanning?"Analizando miniaturas · "+scanVisited+" revisadas"+(scanMissing>0?" · "+scanMissing+" sin miniatura":""):scanFinished?"Análisis completado"+(scanMissing>0?" · "+scanMissing+" sin miniatura":""):"Análisis local en pausa";}
    synchronized void startCatalogue(MediaCache media,BooleanSupplier allowed){
        if(closed||scanning)return;final int epoch=++catalogueEpoch;catalogueMode=true;releaseRequested=false;scanning=true;scanFinished=false;scanVisited=0;scanMissing=0;error="";
        catalogueWorker.execute(()->{long channel=Long.MIN_VALUE,id=Long.MIN_VALUE;
            try{
                while(!closed&&scanning&&epoch==catalogueEpoch&&allowed.getAsBoolean()){
                    List<Item> page=new ArrayList<>();try(Cursor cursor=library.db.rawQuery("SELECT "+ITEM+" FROM media m WHERE (m.channel,m.id)>(?,?) AND (m.mime LIKE 'image/%' OR m.mime LIKE 'video/%') AND NOT EXISTS(SELECT 1 FROM visual_embeddings e WHERE e.channel=m.channel AND e.id=m.id AND e.revision=m.revision AND e.model=?) ORDER BY m.channel,m.id LIMIT 20",new String[]{""+channel,""+id,MODEL})){while(cursor.moveToNext())page.add(Item.cursor(cursor));}
                    if(page.isEmpty()){scanFinished=true;break;}
                    for(Item item:page){if(closed||!scanning||epoch!=catalogueEpoch||!allowed.getAsBoolean())return;channel=item.channel;id=item.id;scanVisited++;
                        try{Bitmap bitmap=media.thumbnailOnly(item);if(closed||!scanning||epoch!=catalogueEpoch||!allowed.getAsBoolean())return;index(item,bitmap,()->!closed&&scanning&&epoch==catalogueEpoch&&allowed.getAsBoolean());}
                        catch(SecurityException denied){error="Desbloquea la galería y comprueba la autorización del conector.";return;}
                        catch(Exception unavailable){scanMissing++;}
                    }
                }
            }catch(Exception failure){error="No se completó el análisis. Puedes reanudarlo sin repetir los elementos ya analizados.";}
            finally{if(epoch==catalogueEpoch){scanning=false;releaseRequested=true;try{worker.execute(this::releaseModelIfRequested);}catch(java.util.concurrent.RejectedExecutionException stopped){}}}
        });
    }
    private synchronized void scheduleScan() {
        if(closed||!scanning||catalogueMode||scanQueued||worker.getQueue().remainingCapacity()==0)return;
        scanQueued=true;worker.execute(new ScanJob());
    }
    private final class ScanJob implements Runnable {
        @Override public void run() {
            try { scanBatch(); }
            catch(Exception|LinkageError failure){error="No se pudieron analizar las miniaturas guardadas";scanning=false;}
            finally {synchronized(VisualIndex.this){scanQueued=false;scheduleScan();}releaseModelIfRequested();}
        }
    }
    private void scanBatch()throws Exception {
        if(closed||!scanning||catalogueMode)return;List<Item> page=new ArrayList<>();
        String sql="SELECT "+ITEM+" FROM media m WHERE (m.channel,m.id)>(?,?) AND (m.mime LIKE 'image/%' OR m.mime LIKE 'video/%') AND NOT EXISTS(SELECT 1 FROM visual_embeddings e WHERE e.channel=m.channel AND e.id=m.id AND e.revision=m.revision AND e.model=?) ORDER BY m.channel,m.id LIMIT 4";
        try(Cursor cursor=library.db.rawQuery(sql,new String[]{""+scanChannel,""+scanId,MODEL})) {while(cursor.moveToNext())page.add(Item.cursor(cursor));}
        if(page.isEmpty()){synchronized(this){scanFinished=true;scanning=false;}if(embedding!=null)embedding.close();embedding=null;return;}
        for(Item item:page) {
            if(closed||!scanning)return;scanChannel=item.channel;scanId=item.id;scanVisited++;
            byte[] bytes;
            try {bytes=vault.get("thumb/"+item.cacheKey());}
            catch(java.io.IOException|java.security.GeneralSecurityException corrupt){error="Algunas miniaturas guardadas no se pudieron leer";continue;}
            if(bytes==null){scanMissing++;continue;}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(options.outWidth<=0||options.outHeight<=0)continue;
            options.inSampleSize=1;while(options.outWidth/options.inSampleSize>512||options.outHeight/options.inSampleSize>512)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(decoded==null)continue;
            Bitmap copy=null;try {copy=snapshot(decoded);store(item,copy,()->scanning&&!catalogueMode);}finally{if(copy!=null)copy.recycle();decoded.recycle();}
        }
    }

    private void store(Item item,Bitmap bitmap,BooleanSupplier allowed)throws Exception {
        if(closed||!allowed.getAsBoolean())return;
        Item current=library.get(item.channel,item.id);
        if(current==null||!current.revision.equals(item.revision))return;
        if(vector(item)!=null){remember(item);return;}
        if(!allowed.getAsBoolean())return;if(embedding==null)embedding=new VisualEmbedding(context);
        byte[] vector=embedding.embed(bitmap);int[] buckets=VisualMath.buckets(vector);long fingerprint=fingerprint(bitmap);int tone=tone(bitmap);
        String suggested="";float topicScore=-1,secondScore=-1;for(Label label:labels){float score=VisualMath.cosine(vector,label.vector);if(score>topicScore){secondScore=topicScore;suggested=label.title;topicScore=score;}else secondScore=Math.max(secondScore,score);}
        if(closed||!allowed.getAsBoolean())return;
        library.db.beginTransaction();
        try {
            current=library.get(item.channel,item.id);
            if(closed||current==null||!current.revision.equals(item.revision))return;
            String group=groupId(item),nearGroup=groupId(item),topic=groupId(item);byte[] topicAnchor=vector;long anchorHash=fingerprint;int anchorTone=tone;float nearBest=.94f,topicBest=.68f;float best=GROUP_THRESHOLD;Set<String> checkedGroups=new HashSet<>();
            for(Candidate candidate:candidates(vector,item)) {
                float themeScore=VisualMath.cosine(vector,candidate.topicAnchor);if(themeScore>=topicBest){topicBest=themeScore;topic=candidate.topicGroup;topicAnchor=candidate.topicAnchor;}
                float nearScore=VisualMath.cosine(vector,candidate.vector);if(nearScore>=nearBest&&near(fingerprint,tone,candidate.anchorHash,candidate.anchorTone)){nearBest=nearScore;nearGroup=candidate.nearGroup;anchorHash=candidate.anchorHash;anchorTone=candidate.anchorTone;}
                if(!checkedGroups.add(candidate.group))continue;
                float score=VisualMath.cosine(vector,candidate.anchor);
                if(score>=best){group=candidate.group;best=score;}
            }
            library.db.execSQL("DELETE FROM visual_embeddings WHERE channel=? AND id=?",new Object[]{item.channel,item.id});
            library.db.execSQL("INSERT OR IGNORE INTO visual_groups(group_id,model,vector) VALUES(?,?,?)",new Object[]{group,MODEL,vector});
            library.db.execSQL("INSERT INTO visual_embeddings(channel,id,revision,model,vector,group_id) VALUES(?,?,?,?,?,?)",new Object[]{item.channel,item.id,item.revision,MODEL,vector,group});
            long ordinal;try(Cursor count=library.db.rawQuery("SELECT count(*) FROM visual_theme_groups WHERE model=?",new String[]{MODEL})){count.moveToFirst();ordinal=count.getLong(0)+1;}
            String themeTitle=topicScore>=.28f&&topicScore-secondScore>=.04f?suggested:"Grupo visual "+ordinal;
            library.db.execSQL("INSERT OR IGNORE INTO visual_theme_groups(group_id,model,title,vector) VALUES(?,?,?,?)",new Object[]{topic,MODEL,themeTitle,topicAnchor});
            library.db.execSQL("INSERT OR REPLACE INTO visual_features(channel,id,model,topic,phash,tone,near_group,anchor_hash,anchor_tone) VALUES(?,?,?,?,?,?,?,?,?)",new Object[]{item.channel,item.id,MODEL,topic,fingerprint,tone,nearGroup,anchorHash,anchorTone});
            for(int band=0;band<buckets.length;band++)library.db.execSQL("INSERT INTO visual_buckets(model,band,bucket,channel,id) VALUES(?,?,?,?,?)",new Object[]{MODEL,band,buckets[band],item.channel,item.id});
            library.db.setTransactionSuccessful();
        } finally { library.db.endTransaction(); }
        error="";remember(item);
    }
    private synchronized void remember(Item item){recent.put(item.cacheKey(),true);}
    private static String groupId(Item item)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((MODEL+"/"+item.cacheKey()).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }

    private byte[] vector(Item item) {
        try(Cursor cursor=library.db.rawQuery("SELECT e.vector FROM visual_embeddings e"+VALID+" WHERE e.channel=? AND e.id=? AND e.revision=? AND e.model=?",new String[]{""+item.channel,""+item.id,item.revision,MODEL})) {
            return cursor.moveToFirst()?cursor.getBlob(0):null;
        }
    }
    private static final class Candidate {
        final Item item;final byte[] vector,anchor,topicAnchor;final String group,nearGroup,topicGroup;final long anchorHash;final int anchorTone;
        Candidate(Cursor cursor){item=Item.cursor(cursor);vector=cursor.getBlob(11);group=cursor.getString(12);anchor=cursor.getBlob(13);nearGroup=cursor.getString(14);anchorHash=cursor.getLong(15);anchorTone=cursor.getInt(16);topicGroup=cursor.getString(17);topicAnchor=cursor.getBlob(18);}
    }
    private List<Candidate> candidates(byte[] vector,Item source) {
        Map<String,Candidate> found=new LinkedHashMap<>();int[] buckets=VisualMath.buckets(vector);
        for(int band=0;band<buckets.length;band++) {
            retrieve(found,source,band,new int[]{buckets[band]},96);
            int[] probes=VisualMath.probes(buckets[band]);int[] neighbors=Arrays.copyOfRange(probes,1,probes.length);
            retrieve(found,source,band,neighbors,64);
        }
        return new ArrayList<>(found.values());
    }
    private void retrieve(Map<String,Candidate> found,Item source,int band,int[] buckets,int limit) {
        List<String> args=new ArrayList<>();args.add(MODEL);args.add(""+band);StringBuilder marks=new StringBuilder();
        for(int bucket:buckets){if(marks.length()>0)marks.append(',');marks.append('?');args.add(""+bucket);}
        args.add(""+limit);
        // shortcut: bucket caps bound work but can miss neighbors in dense collections; use an ANN index if measured recall is insufficient.
        String sql="SELECT "+ITEM+",e.vector,e.group_id,g.vector,f.near_group,f.anchor_hash,f.anchor_tone,f.topic,t.vector FROM visual_buckets b JOIN visual_embeddings e ON e.channel=b.channel AND e.id=b.id AND e.model=b.model"+VALID+" JOIN visual_groups g ON g.group_id=e.group_id AND g.model=e.model JOIN visual_features f ON f.channel=e.channel AND f.id=e.id AND f.model=e.model JOIN visual_theme_groups t ON t.group_id=f.topic AND t.model=f.model WHERE b.model=? AND b.band=? AND b.bucket IN ("+marks+") LIMIT ?";
        try(Cursor cursor=library.db.rawQuery(sql,args.toArray(new String[0]))) {
            while(cursor.moveToNext()) {
                String key=cursor.getInt(1)+"/"+cursor.getInt(0);
                if(!key.equals(source.key())&&!found.containsKey(key))found.put(key,new Candidate(cursor));
            }
        }
    }

    List<Match> similar(Item item,int limit) {
        checkLimit(limit);byte[] vector=vector(item);if(vector==null)return Collections.emptyList();
        List<Match> matches=new ArrayList<>();for(Candidate candidate:candidates(vector,item))matches.add(new Match(candidate.item,VisualMath.cosine(vector,candidate.vector)));
        matches.sort((a,b)->{int score=Float.compare(b.score,a.score);return score!=0?score:a.item.key().compareTo(b.item.key());});
        return new ArrayList<>(matches.subList(0,Math.min(limit,matches.size())));
    }
    List<Group> groups(int limit) {
        checkLimit(limit);List<Group> out=new ArrayList<>();
        try(Cursor cursor=library.db.rawQuery("SELECT "+ITEM+",e.group_id,count(*) FROM visual_embeddings e"+VALID+" WHERE e.model=? GROUP BY e.group_id HAVING count(*)>1 ORDER BY count(*) DESC,e.group_id LIMIT ?",new String[]{MODEL,""+limit})) {
            while(cursor.moveToNext())out.add(new Group(cursor.getString(11),Item.cursor(cursor),cursor.getInt(12)));
        }
        return out;
    }
    List<Item> groupItems(String group,int limit) {
        checkLimit(limit);if(group==null||!group.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Grupo visual inválido");
        List<Item> out=new ArrayList<>();
        try(Cursor cursor=library.db.rawQuery("SELECT "+ITEM+" FROM visual_embeddings e"+VALID+" WHERE e.model=? AND e.group_id=? ORDER BY m.date DESC,m.id DESC LIMIT ?",new String[]{MODEL,group,""+limit})) {
            while(cursor.moveToNext())out.add(Item.cursor(cursor));
        }
        return out;
    }
    List<Topic> topics(int limit){
        checkLimit(limit);List<Topic> out=new ArrayList<>();
        try(Cursor c=library.db.rawQuery("SELECT "+ITEM+",f.topic,count(*),t.title FROM visual_features f JOIN visual_embeddings e ON e.channel=f.channel AND e.id=f.id AND e.model=f.model"+VALID+" JOIN visual_theme_groups t ON t.group_id=f.topic AND t.model=f.model WHERE f.model=? GROUP BY f.topic ORDER BY count(*) DESC LIMIT ?",new String[]{MODEL,""+limit})){
            while(c.moveToNext())out.add(new Topic(c.getString(11),c.getString(13),Item.cursor(c),c.getInt(12)));
        }return out;
    }
    List<Item> topicItems(String id,int limit){
        checkLimit(limit);if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Tema no válido");return featureItems("topic",id,limit);
    }
    List<Group> nearDuplicateGroups(int limit){
        checkLimit(limit);List<Group> out=new ArrayList<>();try(Cursor c=library.db.rawQuery("SELECT "+ITEM+",f.near_group,count(*) FROM visual_features f JOIN visual_embeddings e ON e.channel=f.channel AND e.id=f.id AND e.model=f.model"+VALID+" WHERE f.model=? GROUP BY f.near_group HAVING count(*)>1 ORDER BY count(*) DESC LIMIT ?",new String[]{MODEL,""+limit})){while(c.moveToNext())out.add(new Group(c.getString(11),Item.cursor(c),c.getInt(12)));}return out;
    }
    List<Item> duplicateItems(String id,int limit){checkLimit(limit);if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Grupo no válido");return featureItems("near_group",id,limit);}
    private List<Item> featureItems(String column,String id,int limit){
        List<Item> out=new ArrayList<>();try(Cursor c=library.db.rawQuery("SELECT "+ITEM+" FROM visual_features f JOIN visual_embeddings e ON e.channel=f.channel AND e.id=f.id AND e.model=f.model"+VALID+" WHERE f.model=? AND f."+column+"=? ORDER BY m.date DESC,m.id DESC LIMIT ?",new String[]{MODEL,id,""+limit})){while(c.moveToNext())out.add(Item.cursor(c));}return out;
    }
    List<Match> search(String query,int limit)throws Exception{
        return search(query,limit,()->true);
    }
    List<Match> search(String query,int limit,BooleanSupplier allowed)throws Exception{
        checkLimit(limit);if(query==null||query.trim().isEmpty())return Collections.emptyList();if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("La búsqueda requiere un hilo de trabajo");
        CompletableFuture<byte[]> future=new CompletableFuture<>();synchronized(this){if(closed)throw new IllegalStateException("Índice cerrado");textRequests.add(future);}
        try{worker.execute(()->{try{if(closed||!allowed.getAsBoolean())throw new java.util.concurrent.CancellationException();if(embedding==null)embedding=new VisualEmbedding(context);future.complete(embedding.text(query.trim()));}catch(Exception|LinkageError error){future.completeExceptionally(error);}finally{synchronized(VisualIndex.this){textRequests.remove(future);}releaseModelIfRequested();}});}catch(java.util.concurrent.RejectedExecutionException busy){synchronized(this){textRequests.remove(future);}throw busy;}
        byte[] vector=future.get();PriorityQueue<Match> best=new PriorityQueue<>(limit,(a,b)->Float.compare(a.score,b.score));
        // Exact streaming rank avoids poor cross-modal recall from image-only LSH buckets.
        try(Cursor c=library.db.rawQuery("SELECT "+ITEM+",e.vector FROM visual_embeddings e"+VALID+" WHERE e.model=?",new String[]{MODEL})){
            while(c.moveToNext()){if(closed||!allowed.getAsBoolean()||Thread.currentThread().isInterrupted())break;float score=VisualMath.cosine(vector,c.getBlob(11));if(best.size()<limit||score>best.peek().score){if(best.size()==limit)best.poll();best.add(new Match(Item.cursor(c),score));}}
        }
        List<Match> out=new ArrayList<>(best);out.sort((a,b)->Float.compare(b.score,a.score));
        // shortcut: this conservative relevance cutoff is heuristic; calibrate with an authorised evaluation set before claiming precision.
        if(!out.isEmpty()){float cutoff=Math.max(.18f,out.get(0).score-.06f);out.removeIf(match->match.score<cutoff);}return out;
    }
    static long fingerprint(Bitmap bitmap){Bitmap small=Bitmap.createScaledBitmap(bitmap,9,8,true);int[] pixels=new int[72];small.getPixels(pixels,0,9,0,0,9,8);long hash=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){int a=pixels[y*9+x],b=pixels[y*9+x+1];if(luma(a)>luma(b))hash|=1L<<(y*8+x);}if(small!=bitmap)small.recycle();return hash;}
    private static int luma(int rgb){return ((rgb>>16)&255)*299+((rgb>>8)&255)*587+(rgb&255)*114;}
    private static int tone(Bitmap bitmap){Bitmap small=Bitmap.createScaledBitmap(bitmap,8,8,true);int[] pixels=new int[64];small.getPixels(pixels,0,8,0,0,8,8);int r=0,g=0,b=0;for(int rgb:pixels){r+=(rgb>>16)&255;g+=(rgb>>8)&255;b+=rgb&255;}if(small!=bitmap)small.recycle();return (r/64<<16)|(g/64<<8)|(b/64);}
    static boolean near(long a,int colorA,long b,int colorB){return Long.bitCount(a^b)<=6&&Math.abs((colorA>>16&255)-(colorB>>16&255))+Math.abs((colorA>>8&255)-(colorB>>8&255))+Math.abs((colorA&255)-(colorB&255))<=90;}
    int totalCount(){return library.count(0,"",0,false);}
    int indexedCount() {
        try(Cursor cursor=library.db.rawQuery("SELECT count(*) FROM visual_embeddings e"+VALID+" WHERE e.model=?",new String[]{MODEL})){cursor.moveToFirst();return cursor.getInt(0);}
    }
    String error(){return error;}
    private static void checkLimit(int limit){if(limit<1||limit>96)throw new IllegalArgumentException("Límite visual de 1 a 96");}

    @Override public void close() {
        synchronized(this) {
            if(!closed) {
                closed=true;scanning=false;catalogueEpoch++;catalogueWorker.shutdownNow();for(CompletableFuture<byte[]> request:textRequests)request.completeExceptionally(new IllegalStateException("Índice cerrado"));textRequests.clear();List<Runnable> queued=new ArrayList<>();worker.getQueue().drainTo(queued);
                for(Runnable task:queued)if(task instanceof Job){Job job=(Job)task;if(job.completion!=null)job.completion.completeExceptionally(new IllegalStateException("Índice visual cerrado"));job.release();}
                worker.execute(()->{if(embedding!=null){embedding.close();embedding=null;}});worker.shutdown();
            }
        }
        if(Thread.currentThread()!=workerThread&&Looper.myLooper()!=Looper.getMainLooper()) {
            try {if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("El análisis local aún se está deteniendo");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Se interrumpió el cierre visual",interrupted);}
        }
    }
}
