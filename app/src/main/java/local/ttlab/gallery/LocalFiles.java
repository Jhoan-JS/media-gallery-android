package local.ttlab.gallery;

import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

final class LocalFiles {
    static final long MAX_BYTES=4L*1024*1024*1024;
    private static final int CHANNEL=1, RESERVE=64, MAX_BLOCKS=(int)(MAX_BYTES/Source.CHUNK);
    private static final long HEADROOM=4L*1024*1024;

    static void prepare(State local){
        checkSource(local);
        local.library.db.execSQL("CREATE TABLE IF NOT EXISTS local_copy_pending(id INTEGER PRIMARY KEY,revision TEXT NOT NULL,blocks INTEGER NOT NULL)");
    }
    static synchronized void cleanup(State local)throws Exception{
        checkSource(local);
        while(true){
            int id;String revision;int blocks;
            try(Cursor cursor=local.library.db.rawQuery("SELECT id,revision,blocks FROM local_copy_pending LIMIT 1",(String[])null)){
                if(!cursor.moveToFirst())return;
                id=cursor.getInt(0);revision=cursor.getString(1);blocks=cursor.getInt(2);
            }
            erase(local,id,revision,blocks);
        }
    }
    static synchronized Item add(State local,InputStream input,String name,String mime,long expected,BooleanSupplier cancelled,LongConsumer progress)throws Exception{
        checkSource(local);active(cancelled);
        if(mime==null||!(mime.startsWith("image/")||mime.startsWith("video/")))throw new IOException("Elige una foto o un video.");
        if(expected>MAX_BYTES)throw new IOException("El archivo supera 4 GiB.");
        cleanup(local);
        active(cancelled);
        long date=System.currentTimeMillis()/1000;
        String revision=UUID.randomUUID().toString();
        int id=reserve(local,revision);
        String prefix="media/"+CHANNEL+"/"+id+"/"+revision+"/";
        byte[] buffer=new byte[Source.CHUNK];
        long total=0;
        int blocks=0,reserved=0;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try{
            long available=local.vault.file(prefix+"0").getParentFile().getUsableSpace();
            if(available<HEADROOM||expected>0&&expected+HEADROOM+((expected+Source.CHUNK-1)/Source.CHUNK)*28>available)
                throw new IOException("No hay espacio suficiente para la copia cifrada.");
            for(;;){
                int length=readBlock(input,buffer,cancelled);
                if(length==0)break;
                if(total+length>MAX_BYTES)throw new IOException("El archivo supera 4 GiB.");
                if(blocks==reserved){
                    reserved=Math.min(MAX_BLOCKS,blocks+RESERVE);
                    if(local.vault.file(prefix+"0").getParentFile().getUsableSpace()<length+HEADROOM)
                        throw new IOException("No hay espacio suficiente para la copia cifrada.");
                    local.library.db.execSQL("UPDATE local_copy_pending SET blocks=? WHERE id=?",new Object[]{reserved,id});
                }
                digest.update(buffer,0,length);
                byte[] data=length==buffer.length?buffer:Arrays.copyOf(buffer,length);
                try{local.vault.keep(prefix+blocks,data);}
                finally{if(data!=buffer)Arrays.fill(data,(byte)0);}
                blocks++;total+=length;progress.accept(total);
            }
            active(cancelled);
            if(total==0||expected>=0&&expected!=total)throw new IOException("El archivo está vacío o la lectura quedó incompleta.");
            String hash=hex(digest.digest());
            try(Cursor c=local.library.db.rawQuery("SELECT 1 FROM media WHERE content_key=? AND content_key<>'' LIMIT 1",new String[]{hash})){
                if(c.moveToFirst()){
                    erase(local,id,revision,reserved);
                    return null;
                }
            }
            Item draft=new Item(id,CHANNEL,cleanName(name),mime,total,date,0,0,0,revision,hash);
            Item item=probe(local,draft,cancelled);
            try{local.media.keepThumbnail(item);}catch(IOException unavailable){/* The original is still usable if a decoder cannot produce a thumbnail. */}
            local.vault.syncImportedBlocks();
            active(cancelled);
            local.library.db.beginTransaction();
            try{
                active(cancelled);
                local.library.db.execSQL("INSERT OR IGNORE INTO channels(id,title) VALUES(?,?)",new Object[]{CHANNEL,"Archivos propios"});
                local.library.put(Collections.singletonList(item));
                local.library.offline(item,true);
                local.library.db.execSQL("DELETE FROM local_copy_pending WHERE id=?",new Object[]{id});
                local.library.db.setTransactionSuccessful();
            }finally{local.library.db.endTransaction();}
            return item;
        }catch(Exception failure){
            try{erase(local,id,revision,reserved);}catch(Exception pending){failure.addSuppressed(pending);}
            throw failure;
        }finally{Arrays.fill(buffer,(byte)0);}
    }
    static synchronized void remove(State local,Item item)throws Exception{
        checkSource(local);active(()->false);
        local.library.db.beginTransaction();
        try{
            Item current=local.library.get(item.channel,item.id);
            if(item.channel!=CHANNEL||current==null||!current.revision.equals(item.revision))throw new IOException("La copia cambió. Vuelve a seleccionarla.");
            local.library.db.execSQL("INSERT INTO local_copy_pending(id,revision,blocks) VALUES(?,?,?)",new Object[]{item.id,item.revision,current.chunks()});
            local.library.db.execSQL("DELETE FROM media WHERE channel=? AND id=?",new Object[]{CHANNEL,item.id});
            local.library.db.execSQL("DELETE FROM channels WHERE id=? AND NOT EXISTS(SELECT 1 FROM media WHERE channel=?)",new Object[]{CHANNEL,CHANNEL});
            local.library.db.setTransactionSuccessful();
        }finally{local.library.db.endTransaction();}
        erase(local,item.id,item.revision,item.chunks());
        local.media.clearMemory();
    }
    private static int reserve(State local,String revision)throws IOException{
        local.library.db.beginTransaction();
        try{
            long last;
            try(Cursor c=local.library.db.rawQuery("SELECT MAX(COALESCE((SELECT MAX(id) FROM media),0),COALESCE((SELECT MAX(id) FROM local_copy_pending),0),COALESCE((SELECT CAST(value AS INTEGER) FROM local_settings WHERE name='local-last-id'),0))",(String[])null)){
                c.moveToFirst();last=c.getLong(0);
            }
            if(last>=Integer.MAX_VALUE)throw new IOException("La biblioteca alcanzó su límite de identificadores.");
            int id=(int)last+1;
            local.library.setSetting("local-last-id",""+id);
            local.library.db.execSQL("INSERT INTO local_copy_pending(id,revision,blocks) VALUES(?,?,0)",new Object[]{id,revision});
            local.library.db.setTransactionSuccessful();
            return id;
        }finally{local.library.db.endTransaction();}
    }
    private static void erase(State local,int id,String revision,int blocks)throws Exception{
        if(id<=0||revision==null||revision.isEmpty()||blocks<0||blocks>MAX_BLOCKS)throw new IOException("Registro de copia inválido.");
        Item published=local.library.get(CHANNEL,id);
        if(published==null||!published.revision.equals(revision)){
            String key=CHANNEL+"/"+id+"/"+revision;
            local.vault.removeImportBlock("thumb/"+key);
            for(int i=0;i<blocks;i++)local.vault.removeImportBlock("media/"+key+"/"+i);
            local.vault.syncImportedBlocks();
        }
        local.library.db.execSQL("DELETE FROM local_copy_pending WHERE id=? AND revision=?",new Object[]{id,revision});
    }
    private static Item probe(State local,Item draft,BooleanSupplier stopped)throws Exception{
        String mime;
        int width,height,duration=0;
        if(draft.video()){
            try(MediaDataSource data=local.media.video(draft,false,16L*1024*1024,8000,stopped);
                MediaMetadataRetriever video=new MediaMetadataRetriever()){
                video.setDataSource(data);
                mime=video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                width=Integer.parseInt(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                height=Integer.parseInt(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                long milliseconds=Long.parseLong(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if(mime==null||!mime.startsWith("video/")||milliseconds<=0||milliseconds>Integer.MAX_VALUE)
                    throw new IOException("Formato de video no compatible.");
                duration=(int)((milliseconds+999)/1000);
            }catch(RuntimeException unsupported){throw new IOException("No se pudo leer un video compatible.",unsupported);}
        }else{
            BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;
            try(InputStream bytes=local.media.input(draft)){BitmapFactory.decodeStream(bytes,null,info);}
            mime=info.outMimeType;width=info.outWidth;height=info.outHeight;
            if(mime==null||!mime.startsWith("image/"))throw new IOException("Formato de imagen no compatible.");
        }
        if(width<=0||height<=0||width>100000||height>100000)throw new IOException("Dimensiones de archivo no válidas.");
        active(stopped);
        return new Item(draft.id,draft.channel,draft.name,mime,draft.size,draft.date,width,height,duration,draft.revision,draft.contentKey);
    }
    private static int readBlock(InputStream source,byte[] buffer,BooleanSupplier cancelled)throws IOException{
        int length=0;
        while(length<buffer.length){
            active(cancelled);
            int next=source.read(buffer,length,buffer.length-length);
            if(next<0)break;
            if(next==0){int one=source.read();if(one<0)break;buffer[length++]=(byte)one;}
            else length+=next;
        }
        return length;
    }
    private static void checkSource(State local){
        if(!SourceId.local(local.key))throw new IllegalArgumentException("La operación requiere Archivos propios.");
    }
    private static void active(BooleanSupplier stopped)throws InterruptedIOException{
        if(!State.unlocked||State.maintenance||Thread.currentThread().isInterrupted()||stopped.getAsBoolean())throw new InterruptedIOException("Importación interrumpida.");
    }
    private static String cleanName(String name){
        String sanitized=name==null?"":name.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").trim();
        return sanitized.length()>512?sanitized.substring(0,512):sanitized;
    }
    private static String hex(byte[] bytes){
        char[] digits="0123456789abcdef".toCharArray(),value=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){value[i*2]=digits[(bytes[i]&255)>>>4];value[i*2+1]=digits[bytes[i]&15];}
        return new String(value);
    }
}
