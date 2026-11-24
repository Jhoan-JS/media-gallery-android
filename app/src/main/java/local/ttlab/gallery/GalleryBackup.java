package local.ttlab.gallery;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Logical portable records only; all staging stays inside encrypted app-private storage. */
final class GalleryBackup {
    interface Progress {void update(String message,long count)throws IOException;}
    static final int LOGICAL_VERSION=8;
    private static final String CHANNELS="id,title,cursor,complete,newest,scan_cursor,scan_floor,scan_top,scanning";
    private static final byte[] READY="TTLAB-RESTORE-READY-2".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_MEDIA=2000000,MAX_CHANNELS=100000,MAX_MEDIA_BYTES=Integer.MAX_VALUE;

    static void export(State state,OutputStream output,char[] passphrase,Progress progress)throws IOException {
        enter(state,false);
        try {
            byte[] pin=state.securityVault.secret("pin");if(pin==null||pin.length!=48)throw new IOException("La biblioteca necesita su PIN original");
            state.library.db.beginTransaction();
            try {
                long channels=count(state.library,"channels"),media=count(state.library,"media"),collections=count(state.library,"saved_collections");limits(channels,media);if(collections>100)throw new IOException("Demasiadas colecciones");long[] personal=PersonalBackup.counts(state.library);
                int[] presentation=PresentationBackup.counts(state.library,media,channels);
                long[] chunks={0},incomplete={0},missingThumb={0};
                BackupArchive.write(output,passphrase,writer->{
                    writer.put("manifest",encode(d->{
                        d.writeInt(LOGICAL_VERSION);string(d,state.key);d.writeLong(channels);d.writeLong(media);
                        d.writeInt(Source.CHUNK);d.writeLong(collections);PersonalBackup.manifest(d,state.library,personal);
                        for(int value:presentation)d.writeInt(value);
                    }));
                    if("tato".equals(state.key))writer.put("pin",pin);
                    try{byte[] account=state.vault.secret("source-account");if(account!=null)writer.put("source",account);}catch(Exception e){throw new IOException("No se pudo conservar la cuenta de origen",e);}
                    for(SavedCollection collection:state.library.collections()){checkExport(state);writer.put("collection/"+collection.id,encode(d->{d.writeInt((int)collection.id);string(d,collection.name);string(d,SavedCollection.encode(collection.filter));d.writeLong(collection.updated);}));}
                    try(Cursor c=state.library.db.rawQuery("SELECT "+CHANNELS+",remote_key FROM channels ORDER BY id",(String[])null)) {
                        while(c.moveToNext()) {checkExport(state);writer.put("channel/"+c.getInt(0),encode(d->{d.writeInt(c.getInt(0));string(d,c.getString(1));if("telegram".equals(state.key)){String remote=c.getString(9);if(!remote.isEmpty())Library.checkedPeerKey(remote);string(d,remote);}for(int j=2;j<9;j++)d.writeInt(c.getInt(j));}));progress.update("Exportando canales",c.getPosition()+1);}
                    }
                    try(Cursor c=state.library.db.rawQuery("SELECT "+Library.COLUMNS+",offline FROM media ORDER BY channel,id",(String[])null)) {
                        while(c.moveToNext()) {
                            checkExport(state);Item item=Item.cursor(c);checkMediaSize(state.key,item.size);boolean offline=c.getInt(11)!=0;
                            if(SourceId.local(state.key)&&!offline)throw new IOException("Falta una copia importada completa.");
                            writer.put("item/"+item.key(),encode(d->{d.writeInt(item.id);d.writeInt(item.channel);string(d,item.name);string(d,item.mime);d.writeLong(item.size);d.writeLong(item.date);d.writeInt(item.width);d.writeInt(item.height);d.writeInt(item.duration);string(d,item.revision);string(d,item.contentKey);d.writeInt(offline?1:0);}));
                            byte[] thumb=owned(state.vault,"thumb/"+item.cacheKey());
                            if(thumb!=null)try{writer.put("thumb/"+item.key(),thumb);}finally{Arrays.fill(thumb,(byte)0);}
                            else if(offline)missingThumb[0]++;
                            if(offline) {
                                boolean complete=true;
                                for(int index=0;index<item.chunks();index++) {
                                    checkExport(state);byte[] bytes=owned(state.vault,"media/"+item.cacheKey()+"/"+index);
                                    if(bytes==null){complete=false;continue;}
                                    try {if(bytes.length!=expected(item,index))throw new IOException("Una descarga guardada tiene un bloque incompleto");writer.put("chunk/"+item.key()+"/"+index,bytes);chunks[0]++;}
                                    finally {Arrays.fill(bytes,(byte)0);}
                                    progress.update("Exportando bloques guardados",chunks[0]);
                                }
                                if(!complete){
                                    if(SourceId.local(state.key))throw new IOException("Faltan bloques de una copia importada.");
                                    incomplete[0]++;
                                }
                            }
                            progress.update("Exportando catálogo",c.getPosition()+1);
                        }
                    }
                    try(Cursor c=state.library.db.rawQuery("SELECT s.id,s.channel,s.revision,s.favorite,s.later,s.position_ms,s.duration_ms,s.completed,s.updated,m.mime,s.archived FROM media_state s JOIN media m ON m.channel=s.channel AND m.id=s.id AND m.revision=s.revision ORDER BY s.channel,s.id",(String[])null)) {
                        while(c.moveToNext()) {
                            checkExport(state);int id=c.getInt(0),channel=c.getInt(1);String revision=c.getString(2);
                            long favorite=c.getLong(3),later=c.getLong(4),position=c.getLong(5),duration=c.getLong(6),completed=c.getLong(7),updated=c.getLong(8),archived=c.getLong(10);
                            checkState(id,channel,revision,favorite,later,archived,position,duration,completed,updated);
                            if(!c.getString(9).startsWith("video/")&&(position!=0||duration!=0||completed!=0))throw new IOException("Una imagen contiene progreso de video");
                            writer.put("state/"+channel+"/"+id,encode(d->{d.writeInt(id);d.writeInt(channel);string(d,revision);d.writeInt((int)favorite);d.writeInt((int)later);d.writeInt((int)archived);d.writeLong(position);d.writeLong(duration);d.writeInt((int)completed);d.writeLong(updated);}));
                            progress.update("Exportando marcas y progreso local",c.getPosition()+1);
                        }
                    }
                    PersonalBackup.export(state,writer,progress);
                    PresentationBackup.export(state,writer,progress);checkExport(state);
                    progress.update("Catálogo de "+SourceId.label(state.key)+" conservado; "+chunks[0]+" bloques. Descargas incompletas: "+incomplete[0]+"; sin miniatura guardada: "+missingThumb[0]+". Caché temporal de originales no incluida."+("tato".equals(state.key)?" PIN original incluido.":" El PIN común no se exporta."),media);
                });
            } finally {state.library.db.endTransaction();Arrays.fill(pin,(byte)0);}
        } catch(IOException e){throw e;}catch(Exception e){throw new IOException("No se pudo crear el respaldo cifrado",e);}
        finally {state.maintenance=false;}
    }

    static void stage(Context context,State state,InputStream input,char[] passphrase,Progress progress)throws IOException {
        enter(state,true);
        String directory=stageDirectory(state.key);File stage=new File(context.getNoBackupFilesDir(),directory);Library library=null;boolean ready=false,ownsStage=false,pendingReady=false;
        try {
            if(hasReady(context)){pendingReady=true;throw new IOException("Hay una restauración lista. Cierra y vuelve a abrir la aplicación");}
            erase(stage);if(!stage.mkdirs())throw new IOException("No se pudo crear el área cifrada de restauración");
            ownsStage=true;
            Vault vault=new Vault(context,directory+"/vault");byte[] key=vault.databaseKey();
            try {library=new Library(new File(stage,"database.db"),key);}finally{Arrays.fill(key,(byte)0);}
            Library imported=library;
            imported.db.execSQL("CREATE TABLE restore_entries(name TEXT PRIMARY KEY)");
            imported.db.execSQL("CREATE TABLE restore_media(channel INTEGER,id INTEGER,expected INTEGER,received INTEGER NOT NULL DEFAULT 0,wanted INTEGER,thumb INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(channel,id))");
            long[] expected={-1,-1,0},seen={0,0,0,0,0};int[] logicalVersion={0};boolean[] pin={false},account={false};PersonalBackup[] personal={null};
            PresentationBackup[] presentation={null};
            imported.db.beginTransaction();
            try {
                BackupArchive.read(input,passphrase,(name,bytes)->{
                    try {
                        if(Thread.currentThread().isInterrupted())throw new IOException("Restauración cancelada");
                        progress.update("Verificando y guardando registros cifrados",++seen[2]);
                        imported.db.execSQL("INSERT INTO restore_entries(name) VALUES(?)",new Object[]{name});
                        if(name.equals("manifest")) {
                            if(seen[2]!=1)throw new IOException("El manifiesto debe ser el primer registro");
                            DataInputStream d=data(bytes);logicalVersion[0]=d.readInt();String source;
                            if(logicalVersion[0]==2)source="tato";
                            else if(logicalVersion[0]>=3&&logicalVersion[0]<=LOGICAL_VERSION)source=string(d,16);
                            else throw new IOException("Versión lógica desconocida");
                            if(!state.key.equals(source))throw new IOException("Este respaldo pertenece a otra fuente. Cambia a "+SourceId.label(source)+" antes de restaurarlo");
                            expected[0]=d.readLong();expected[1]=d.readLong();limits(expected[0],expected[1]);
                            if(d.readInt()!=Source.CHUNK)throw new IOException("Tamaño de bloque incompatible");
                            if(logicalVersion[0]>=6){expected[2]=d.readLong();if(expected[2]<0||expected[2]>100)throw new IOException("Recuento de colecciones inválido");}
                            if(logicalVersion[0]>=7)personal[0]=PersonalBackup.manifest(imported,d,expected[1]);
                            if(logicalVersion[0]>=8)presentation[0]=PresentationBackup.readManifest(imported,d,expected[1],expected[0]);
                            end(d);return;
                        }
                        if(expected[0]<0)throw new IOException("Falta el manifiesto");
                        if(name.equals("pin")) {if(!"tato".equals(state.key))throw new IOException("Un respaldo de Telegram no puede cambiar el PIN común");if(bytes.length!=48)throw new IOException("Verificador de PIN inválido");vault.secret("pin",bytes);pin[0]=true;return;}
                        if(name.equals("source")){if(SourceId.local(state.key)||bytes.length!=64||!new String(bytes,StandardCharsets.US_ASCII).matches("[0-9a-f]{64}"))throw new IOException("Identidad de origen inválida");byte[] pinned=state.vault.secret("source-account");if(pinned!=null&&!Arrays.equals(pinned,bytes))throw new IOException("El respaldo pertenece a otra cuenta de esta fuente");vault.secret("source-account",bytes);account[0]=true;return;}
                        String[] parts=name.split("/");
                        if(personal[0]!=null&&personal[0].accept(name,bytes))return;
                        if(presentation[0]!=null&&presentation[0].accept(name,bytes))return;
                        if(parts[0].equals("collection")&&parts.length==2&&logicalVersion[0]>=6){DataInputStream d=data(bytes);int id=d.readInt();String title=string(d,240),query=string(d,16384);long updated=d.readLong();end(d);if(id<=0||!name.equals("collection/"+id)||updated<0||++seen[4]>expected[2])throw new IOException("Colección inválida");SavedCollection.checkedName(title);SavedCollection.decode(query);imported.db.execSQL("INSERT INTO saved_collections(id,name,filter,updated) VALUES(?,?,?,?)",new Object[]{id,title,query,updated});return;}
                        if(parts[0].equals("channel")&&parts.length==2) {
                            DataInputStream d=data(bytes);int id=d.readInt();String title=string(d,16384),remote="";if("telegram".equals(state.key)&&d.available()!=28){remote=string(d,64);if(!remote.isEmpty())Library.checkedPeerKey(remote);}int[] values=new int[7];for(int j=0;j<7;j++)values[j]=d.readInt();end(d);
                            if(id<=0||!name.equals("channel/"+id)||values[0]<0||!flag(values[1])||values[2]<0||values[3]<0||values[4]<0||values[5]<0||!flag(values[6]))throw new IOException("Canal inválido");
                            if(++seen[0]>expected[0])throw new IOException("Demasiados canales");
                            if(!remote.isEmpty()&&exists(imported,"SELECT 1 FROM channels WHERE remote_key=?",new String[]{remote}))throw new IOException("Identidad remota de canal duplicada");
                            imported.db.execSQL("INSERT INTO channels("+CHANNELS+",remote_key) VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{id,title,values[0],values[1],values[2],values[3],values[4],values[5],values[6],remote});return;
                        }
                        if(parts[0].equals("item")&&parts.length==3) {
                            DataInputStream d=data(bytes);int id=d.readInt(),channel=d.readInt();String title=string(d,65536),mime=string(d,256);long size=d.readLong(),date=d.readLong();int width=d.readInt(),height=d.readInt(),duration=d.readInt();String revision=string(d,16384),content=string(d,1024);int wanted=d.readInt();end(d);
                            checkMediaSize(state.key,size);
                            if(SourceId.local(state.key)&&(channel!=1||wanted!=1||revision.isEmpty()||!content.matches("[0-9a-f]{64}")))throw new IOException("Registro de archivo propio inválido");
                            if(id<=0||channel<=0||!name.equals("item/"+channel+"/"+id)||date<0||width<0||height<0||width>100000||height>100000||duration<0||!flag(wanted)||!(mime.startsWith("image/")||mime.startsWith("video/")))throw new IOException("Archivo de catálogo inválido");
                            if(++seen[1]>expected[1]||!exists(imported,"SELECT 1 FROM channels WHERE id=?",new String[]{""+channel}))throw new IOException("Referencia de canal inválida");
                            imported.put(java.util.Collections.singletonList(new Item(id,channel,title,mime,size,date,width,height,duration,revision,content)));
                            imported.db.execSQL("INSERT INTO restore_media(channel,id,expected,wanted) VALUES(?,?,?,?)",new Object[]{channel,id,(size+Source.CHUNK-1)/Source.CHUNK,wanted});return;
                        }
                        if(parts[0].equals("state")&&parts.length==3&&logicalVersion[0]>=4) {
                            DataInputStream d=data(bytes);int id=d.readInt(),channel=d.readInt();String revision=string(d,16384);
                            int favorite=d.readInt(),later=d.readInt(),archived=logicalVersion[0]>=5?d.readInt():0;long position=d.readLong(),duration=d.readLong();int completed=d.readInt();long updated=d.readLong();end(d);
                            checkState(id,channel,revision,favorite,later,archived,position,duration,completed,updated);
                            if(!name.equals("state/"+channel+"/"+id)||++seen[3]>expected[1])throw new IOException("Referencia de estado inválida");
                            imported.db.execSQL("INSERT INTO media_state(channel,id,revision,favorite,later,position_ms,duration_ms,completed,updated,archived) VALUES(?,?,?,?,?,?,?,?,?,?)",new Object[]{channel,id,revision,favorite,later,position,duration,completed,updated,archived});return;
                        }
                        boolean chunk=parts[0].equals("chunk")&&parts.length==4,thumb=parts[0].equals("thumb")&&parts.length==3;
                        if(!chunk&&!thumb)throw new IOException("Registro lógico desconocido");
                        int channel=number(parts[1]),id=number(parts[2]);Item item=imported.get(channel,id);if(item==null)throw new IOException("Bloque sin registro de catálogo");
                        if(stage.getUsableSpace()<bytes.length+1048576L)throw new IOException("Espacio insuficiente para restaurar");
                        if(chunk) {
                            int index=number(parts[3]);if(bytes.length!=expected(item,index))throw new IOException("Longitud de bloque inválida");
                            vault.keep("media/"+item.cacheKey()+"/"+index,bytes);
                            imported.db.execSQL("UPDATE restore_media SET received=received+1 WHERE channel=? AND id=?",new Object[]{channel,id});
                        } else {
                            if(bytes.length<1||bytes.length>Source.CHUNK)throw new IOException("Miniatura inválida");
                            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                            if(options.outWidth<1||options.outHeight<1||options.outWidth>16384||options.outHeight>16384||(long)options.outWidth*options.outHeight>64000000)throw new IOException("Miniatura no válida");
                            if(exists(imported,"SELECT 1 FROM restore_media WHERE channel=? AND id=? AND wanted=1",new String[]{""+channel,""+id}))vault.keep("thumb/"+item.cacheKey(),bytes);
                            else vault.put("thumb/"+item.cacheKey(),bytes);
                            imported.db.execSQL("UPDATE restore_media SET thumb=1 WHERE channel=? AND id=?",new Object[]{channel,id});
                        }
                    }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Registro de respaldo inválido",e);}
                    finally {Arrays.fill(bytes,(byte)0);}
                });
                if(("tato".equals(state.key)&&!pin[0])||expected[0]!=seen[0]||expected[1]!=seen[1]||expected[2]!=seen[4])throw new IOException("Respaldo lógico incompleto");
                if("telegram".equals(state.key)&&(expected[0]>0||expected[1]>0)&&!account[0])throw new IOException("Falta la cuenta autorizada del respaldo Telegram");
                if(exists(imported,"SELECT 1 FROM media_state s LEFT JOIN media m ON m.channel=s.channel AND m.id=s.id WHERE m.id IS NULL OR m.revision<>s.revision OR (m.mime NOT LIKE 'video/%' AND (s.position_ms<>0 OR s.duration_ms<>0 OR s.completed<>0)) LIMIT 1",(String[])null))throw new IOException("Estado sin archivo o revisión correspondiente");
                if(personal[0]!=null)personal[0].finish();else PersonalBackup.collections(imported);
                if(presentation[0]!=null)presentation[0].finish();
                long incomplete=scalar(imported,"SELECT count(*) FROM restore_media WHERE wanted=1 AND received<>expected");
                if(SourceId.local(state.key)&&incomplete!=0)throw new IOException("Faltan bloques de una copia importada.");
                long withoutThumb=scalar(imported,"SELECT count(*) FROM restore_media WHERE wanted=1 AND thumb<>1");
                imported.db.execSQL("UPDATE media SET offline=CASE WHEN EXISTS(SELECT 1 FROM restore_media r WHERE r.channel=media.channel AND r.id=media.id AND r.wanted=1 AND r.received=r.expected) THEN 1 ELSE 0 END");
                imported.db.execSQL("DROP TABLE restore_entries");imported.db.execSQL("DROP TABLE restore_media");
                progress.update("Verificación completa de "+SourceId.label(state.key)+". Archivos incompletos: "+incomplete+"; sin miniatura: "+withoutThumb+". Los originales completos siguen sin conexión. "+("tato".equals(state.key)?"Se recuperará el PIN original.":"El PIN común actual se conserva."),seen[1]);
                imported.db.setTransactionSuccessful();
            } finally {imported.db.endTransaction();}
            checkpoint(imported);imported.db.close();library=null;
            syncFile(new File(stage,"database.db"));syncDirectory(new File(stage,"vault/cache"));syncDirectory(new File(stage,"vault/offline"));syncDirectory(new File(stage,"vault"));syncDirectory(stage);
            assertEmpty(state);vault.secret("restore-ready",readyMarker(state.key));syncDirectory(new File(stage,"vault"));syncDirectory(stage);syncDirectory(context.getNoBackupFilesDir());ready=true;
        } catch(IOException e){throw e;}catch(Exception e){throw new IOException("No se pudo restaurar el respaldo",e);}
        finally {
            if(library!=null&&library.db.isOpen())library.db.close();
            if(!ready){state.maintenance=pendingReady;if(ownsStage)try{erase(stage);}catch(IOException cleanup){throw new IOException("La restauración falló; no se pudo limpiar el área cifrada temporal",cleanup);}}
        }
    }

    /** Runs before State opens live files. Every rename can be resumed after process death. */
    static synchronized void finishRestore(Context context)throws IOException {
        finishRestore(context,"tato");finishRestore(context,"telegram");finishRestore(context,"local");
    }

    private static void finishRestore(Context context,String source)throws IOException {
        String directory=stageDirectory(source),vaultDirectory=SourceId.vault(source),suffix=SourceId.suffix(source);
        File stage=new File(context.getNoBackupFilesDir(),directory);if(!stage.exists())return;
        File stagedVault=new File(stage,"vault"),liveVault=new File(context.getNoBackupFilesDir(),vaultDirectory),oldVault=new File(context.getNoBackupFilesDir(),"restore-old-vault"+suffix);
        File stagedDb=new File(stage,"database.db"),liveDb=context.getDatabasePath(SourceId.database(source)),oldDb=context.getDatabasePath("restore-old"+suffix+".db");
        try {
            File marker=new File(stagedVault,"restore-ready.secret");boolean markerLive=false;
            if(!marker.exists()){marker=new File(liveVault,"restore-ready.secret");markerLive=true;}
            if(!marker.exists())return;
            Vault authorized=new Vault(context,markerLive?vaultDirectory:directory+"/vault");
            byte[] markerValue=authorized.secret("restore-ready");
            if(!Arrays.equals(readyMarker(source),markerValue)&&!("tato".equals(source)&&Arrays.equals(READY,markerValue)))throw new IOException("Marcador de restauración inválido");
            if(stagedDb.exists()&&stagedVault.exists()&&!oldDb.exists()&&!oldVault.exists())assertEmpty(context,source);
            if(stagedDb.exists()) {
                if(liveDb.exists()) {if(oldDb.exists())throw new IOException("Conflicto de restauración: se conservan ambos almacenes");move(liveDb,oldDb);}
                for(String sidecarSuffix:new String[]{"-wal","-shm","-journal"}) {File sidecar=new File(liveDb.getPath()+sidecarSuffix);if(sidecar.exists())move(sidecar,new File(oldDb.getPath()+sidecarSuffix));}
            }
            if(stagedVault.exists()&&liveVault.exists()) {if(oldVault.exists())throw new IOException("Conflicto de almacenes cifrados");move(liveVault,oldVault);}
            if(stagedDb.exists())move(stagedDb,liveDb);
            if(stagedVault.exists())move(stagedVault,liveVault);
            if(!liveDb.isFile()||!new File(liveVault,"database.key").isFile())throw new IOException("Publicación de restauración incompleta");
            syncDirectory(liveDb.getParentFile());syncDirectory(context.getNoBackupFilesDir());
            File completed=new File(liveVault,"restore-ready.secret");if(completed.exists()&&!completed.delete())throw new IOException("No se pudo finalizar el marcador de restauración");
            syncDirectory(liveVault);erase(stage);syncDirectory(context.getNoBackupFilesDir());State.maintenance=false;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("No se pudo completar la restauración; los archivos se conservan",e);}
    }

    private static void enter(State state,boolean restore)throws IOException {
        synchronized(State.class){if(state.maintenance)throw new IOException("Ya hay una operación de mantenimiento");state.maintenance=true;}
        try {if(SyncService.running||DownloadService.running||count(state.library,"downloads")!=0)throw new IOException("Termina o cancela las descargas y pausa la sincronización antes de continuar");if(restore)assertEmpty(state);else checkExport(state);}
        catch(Exception e){state.maintenance=false;if(e instanceof IOException)throw (IOException)e;throw new IOException("No se pudo preparar la operación",e);}
    }
    static void checkExport(State state)throws IOException {if(!state.unlocked||Thread.currentThread().isInterrupted())throw new IOException("Desbloquea la biblioteca para exportar");}
    private static void assertEmpty(State state)throws Exception {
        if("tato".equals(state.key)?state.securityVault.secret("pin")!=null:!state.unlocked||state.securityVault.secret("pin")==null)throw new IOException("tato".equals(state.key)?"Solo se puede restaurar Tato en una instalación vacía sin PIN":"Crea o desbloquea el PIN común antes de restaurar "+SourceId.label(state.key));
        if(!empty(state.library))throw new IOException("La fuente de destino contiene datos; se conserva");
    }
    private static void assertEmpty(Context context,String source)throws Exception {
        Vault security=new Vault(context),vault="tato".equals(source)?security:new Vault(context,SourceId.vault(source));
        if("tato".equals(source)?security.secret("pin")!=null:security.secret("pin")==null)throw new IOException("tato".equals(source)?"El destino ya tiene PIN; se conserva":"Falta el PIN común para publicar "+SourceId.label(source));
        File database=context.getDatabasePath(SourceId.database(source));if(!database.exists())return;
        byte[] key=vault.databaseKey();Library library;try{library=new Library(database,key);}finally{Arrays.fill(key,(byte)0);}
        try {if(!empty(library))throw new IOException("El destino contiene datos; se conserva");checkpoint(library);}finally{library.db.close();}
    }
    static boolean empty(Library library){
        return count(library,"media")==0&&count(library,"channels")==0&&count(library,"downloads")==0
            &&count(library,"saved_collections")==0&&PersonalBackup.empty(library)&&PresentationBackup.empty(library);
    }
    static boolean hasReady(Context c){for(String source:new String[]{"tato","telegram","local"})if(new File(c.getNoBackupFilesDir(),stageDirectory(source)+"/vault/restore-ready.secret").exists()||new File(c.getNoBackupFilesDir(),SourceId.vault(source)+"/restore-ready.secret").exists())return true;return false;}
    private static String stageDirectory(String source){return "restore-stage"+SourceId.suffix(source);}
    private static byte[] readyMarker(String source){return ("TTLAB-RESTORE-READY-3/"+SourceId.require(source)).getBytes(StandardCharsets.US_ASCII);}
    private static byte[] owned(Vault vault,String id)throws IOException {try{return vault.file(id).isFile()?vault.get(id):null;}catch(Exception e){throw new IOException("No se pudo verificar un archivo cifrado guardado",e);}}
    private static int expected(Item item,int index)throws IOException {if(index<0||(long)index*Source.CHUNK>=item.size)throw new IOException("Índice de bloque inválido");return (int)Math.min(Source.CHUNK,item.size-(long)index*Source.CHUNK);}
    private static int number(String text)throws IOException {try{int value=Integer.parseInt(text);if(value<0||!Integer.toString(value).equals(text))throw new NumberFormatException();return value;}catch(NumberFormatException e){throw new IOException("Identidad de registro inválida",e);}}
    private static void limits(long channels,long media)throws IOException {if(channels<0||channels>MAX_CHANNELS||media<0||media>MAX_MEDIA)throw new IOException("El catálogo supera los límites del respaldo");}
    static void checkMediaSize(String source,long size)throws IOException {long maximum="tato".equals(SourceId.require(source))?MAX_MEDIA_BYTES:4L*1024*1024*1024;if(size<1||size>maximum)throw new IOException("Tamaño de archivo de respaldo inválido");}
    private static void checkState(int id,int channel,String revision,long favorite,long later,long archived,long position,long duration,long completed,long updated)throws IOException {
        if(id<=0||channel<=0||revision==null||revision.indexOf('\0')>=0||revision.getBytes(StandardCharsets.UTF_8).length>16384||(favorite!=0&&favorite!=1)||(later!=0&&later!=1)||(archived!=0&&archived!=1)||(completed!=0&&completed!=1)||position<0||duration<0||position>duration||duration>Integer.MAX_VALUE||updated<0)throw new IOException("Marcas o progreso de respaldo inválidos");
    }
    private static boolean flag(int value){return value==0||value==1;}
    private static long count(Library library,String table){return scalar(library,"SELECT count(*) FROM "+table);}
    private static long scalar(Library library,String sql){try(Cursor c=library.db.rawQuery(sql,(String[])null)){if(!c.moveToFirst())throw new IllegalStateException("Consulta incompleta");return c.getLong(0);}}
    static boolean exists(Library library,String sql,String[] args){try(Cursor c=library.db.rawQuery(sql,args)){return c.moveToFirst();}}
    private static void checkpoint(Library library)throws IOException {try(Cursor c=library.db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",(String[])null)){if(!c.moveToFirst()||c.getInt(0)!=0)throw new IOException("No se pudo cerrar el registro de la base cifrada");}}
    interface Encoding {void write(DataOutputStream d)throws IOException;}
    static byte[] encode(Encoding encoding)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();encoding.write(new DataOutputStream(out));if(out.size()>BackupArchive.MAX_ENTRY_BYTES)throw new IOException("Registro demasiado grande");return out.toByteArray();}
    static void string(DataOutputStream d,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IOException("Texto de catálogo demasiado largo");d.writeInt(bytes.length);d.write(bytes);}
    static String string(DataInputStream d,int max)throws IOException {int length=d.readInt();if(length<0||length>max||length>d.available())throw new IOException("Texto de respaldo inválido");byte[] bytes=new byte[length];d.readFully(bytes);String text=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8))||text.indexOf('\0')>=0)throw new IOException("Codificación de texto inválida");return text;}
    static DataInputStream data(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    static void end(DataInputStream d)throws IOException {if(d.read()!=-1)throw new IOException("Datos sobrantes en el registro");}
    private static void move(File from,File to)throws IOException {if(to.exists())throw new IOException("El destino de restauración ya existe");to.getParentFile().mkdirs();Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory(from.getParentFile());if(!from.getParentFile().equals(to.getParentFile()))syncDirectory(to.getParentFile());}
    private static void syncFile(File file)throws IOException {try(FileOutputStream out=new FileOutputStream(file,true)){out.getFD().sync();}}
    static void syncDirectory(File directory)throws IOException {if(!directory.isDirectory())throw new IOException("Directorio privado ausente");try{java.io.FileDescriptor fd=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);try{if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))throw new IOException("Ruta privada no es directorio");Os.fsync(fd);}finally{Os.close(fd);}}catch(android.system.ErrnoException e){throw new IOException("No se pudo confirmar el almacenamiento cifrado",e);}}
    private static void erase(File file)throws IOException {if(!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)erase(child);if(!file.delete())throw new IOException("No se pudo limpiar el área privada de restauración");}
}
