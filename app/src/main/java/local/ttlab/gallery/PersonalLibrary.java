package local.ttlab.gallery;

import android.database.Cursor;
import android.os.Looper;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Private organization belongs to this Library's encrypted source, without connector access. */
final class PersonalLibrary {
    static final int ALBUM=0,TAG=1;
    private final Library library;
    private final SQLiteDatabase db;

    PersonalLibrary(Library library){
        this.library=library;db=library.db;
        db.beginTransaction();try{
            db.execSQL("CREATE TABLE IF NOT EXISTS recent_media(channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,opened_at INTEGER NOT NULL CHECK(opened_at>=0),PRIMARY KEY(channel,id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS recent_media_order ON recent_media(opened_at DESC,channel,id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS personal_groups(group_id INTEGER PRIMARY KEY AUTOINCREMENT,kind INTEGER NOT NULL CHECK(kind IN(0,1)),name TEXT NOT NULL,name_key TEXT NOT NULL,updated INTEGER NOT NULL,UNIQUE(kind,name_key))");
            db.execSQL("CREATE TABLE IF NOT EXISTS personal_members(group_id INTEGER NOT NULL,channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,PRIMARY KEY(group_id,channel,id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS personal_members_media ON personal_members(channel,id,revision,group_id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS video_bookmarks(bookmark_id INTEGER PRIMARY KEY AUTOINCREMENT,channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,position_ms INTEGER NOT NULL CHECK(position_ms>=0),duration_ms INTEGER NOT NULL CHECK(duration_ms>0 AND position_ms<=duration_ms),name TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(channel,id,revision,position_ms))");
            // Derived mutation counters are not undo snapshots and are intentionally absent from logical backups.
            db.execSQL("CREATE TABLE IF NOT EXISTS personal_mark_versions(generation INTEGER PRIMARY KEY AUTOINCREMENT,channel INTEGER NOT NULL,id INTEGER NOT NULL,revision TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,later INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,viewed INTEGER NOT NULL DEFAULT 0,UNIQUE(channel,id))");
            db.execSQL("INSERT OR IGNORE INTO local_settings(name,value) VALUES('history-enabled','0')");
            String cleanup="DELETE FROM recent_media WHERE channel=old.channel AND id=old.id; DELETE FROM personal_members WHERE channel=old.channel AND id=old.id; DELETE FROM video_bookmarks WHERE channel=old.channel AND id=old.id; DELETE FROM personal_mark_versions WHERE channel=old.channel AND id=old.id;";
            db.execSQL("CREATE TRIGGER IF NOT EXISTS personal_media_ad AFTER DELETE ON media BEGIN "+cleanup+" END");
            db.execSQL("CREATE TRIGGER IF NOT EXISTS personal_media_au AFTER UPDATE OF revision ON media WHEN old.revision<>new.revision BEGIN "+cleanup+" END");
            db.execSQL("CREATE TRIGGER IF NOT EXISTS personal_group_ad AFTER DELETE ON personal_groups BEGIN DELETE FROM personal_members WHERE group_id=old.group_id; END");
            versionTrigger("personal_state_ai","INSERT","media_state","new","favorite","later","archived");
            versionTrigger("personal_state_ad","DELETE","media_state","old","favorite","later","archived");
            for(String property:Arrays.asList("favorite","later","archived"))versionTrigger("personal_state_au_"+property,"UPDATE OF "+property,"media_state","new",property);
            versionTrigger("personal_viewed_ai","INSERT","viewed_media","new","viewed");
            versionTrigger("personal_viewed_au","UPDATE","viewed_media","new","viewed");
            versionTrigger("personal_viewed_ad","DELETE","viewed_media","old","viewed");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private void versionTrigger(String name,String event,String table,String row,String...properties){
        StringBuilder columns=new StringBuilder(),values=new StringBuilder(),updates=new StringBuilder();
        for(String property:properties){columns.append(',').append(property);values.append(",1");if(updates.length()>0)updates.append(',');updates.append(property).append('=').append(property).append("+1");}
        // The current-revision predicate prevents delete/revision triggers from recreating orphan counters.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS "+name+" AFTER "+event+" ON "+table+" BEGIN INSERT INTO personal_mark_versions(channel,id,revision"+columns+") SELECT "+row+".channel,"+row+".id,"+row+".revision"+values+" WHERE EXISTS(SELECT 1 FROM media WHERE channel="+row+".channel AND id="+row+".id AND revision="+row+".revision) ON CONFLICT(channel,id) DO UPDATE SET "+updates+"; END");
    }

    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("La biblioteca se consulta fuera del hilo de interfaz");}
    private static void kind(int kind){if(kind!=ALBUM&&kind!=TAG)throw new IllegalArgumentException("Tipo de grupo inválido");}
    private static void identity(int channel,int id,String revision){if(channel<=0||id<=0||revision==null)throw new IllegalArgumentException("Archivo inválido");}
    private static void item(Item item){if(item==null)throw new IllegalArgumentException("Archivo inválido");identity(item.channel,item.id,item.revision);}
    private static String name(String proposed){if(proposed==null)throw new IllegalArgumentException("Escribe un nombre");return SavedCollection.checkedName(Normalizer.normalize(proposed,Normalizer.Form.NFKC));}
    private static List<Item> selection(List<Item> proposed){
        if(proposed==null||proposed.isEmpty()||proposed.size()>1000)throw new IllegalArgumentException("Selecciona entre 1 y 1.000 archivos");
        List<Item> result=new ArrayList<>(proposed);Set<String> keys=new HashSet<>();for(Item item:result){item(item);if(!keys.add(item.key()))throw new IllegalArgumentException("Selección duplicada");}return result;
    }
    private Item current(Item selected){Item current=library.get(selected.channel,selected.id);return current!=null&&selected.revision.equals(current.revision)?current:null;}
    private long scalar(String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;}}
    private long inserted(){return scalar("SELECT last_insert_rowid()");}

    boolean historyEnabled(){worker();return "1".equals(library.setting("history-enabled","0"));}
    void setHistoryEnabled(boolean enabled){worker();library.setSetting("history-enabled",enabled?"1":"0");}
    void clearHistory(){worker();db.execSQL("DELETE FROM recent_media");}
    void recordOpened(Item item,long openedAt){worker();recordVisits(Collections.singletonList(new Visit(item,openedAt)));}
    void recordVisits(List<Visit> visits){
        worker();if(visits==null)throw new IllegalArgumentException("Historial inválido");
        List<Visit> snapshot=new ArrayList<>(visits);for(Visit visit:snapshot)if(visit==null)throw new IllegalArgumentException("Apertura inválida");
        db.beginTransaction();try{
            if(historyEnabled()){
                for(Visit visit:snapshot)db.execSQL("INSERT INTO recent_media(channel,id,revision,opened_at) SELECT channel,id,revision,? FROM media WHERE channel=? AND id=? AND revision=? ON CONFLICT(channel,id) DO UPDATE SET revision=excluded.revision,opened_at=MAX(recent_media.opened_at,excluded.opened_at)",new Object[]{visit.openedAt,visit.channel,visit.id,visit.revision});
                db.execSQL("DELETE FROM recent_media WHERE (channel,id) NOT IN(SELECT channel,id FROM recent_media ORDER BY opened_at DESC,channel,id LIMIT 10000)");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    long lastOpened(Item item){worker();item(item);return scalar("SELECT r.opened_at FROM recent_media r JOIN media m ON m.channel=r.channel AND m.id=r.id AND m.revision=r.revision WHERE r.channel=? AND r.id=? AND r.revision=?",""+item.channel,""+item.id,item.revision);}
    static final class Visit {
        final int channel,id;final String revision;final long openedAt;
        Visit(Item item,long openedAt){this(checkedChannel(item),item.id,item.revision,openedAt);}
        Visit(int channel,int id,String revision,long openedAt){identity(channel,id,revision);if(openedAt<0)throw new IllegalArgumentException("Fecha de apertura inválida");this.channel=channel;this.id=id;this.revision=revision;this.openedAt=openedAt;}
        private static int checkedChannel(Item item){item(item);return item.channel;}
    }

    List<Group> groups(int kind){
        worker();kind(kind);List<Group> result=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT g.group_id,g.kind,g.name,(SELECT count(*) FROM personal_members p JOIN media m ON m.channel=p.channel AND m.id=p.id AND m.revision=p.revision WHERE p.group_id=g.group_id) FROM personal_groups g WHERE g.kind=? ORDER BY g.name_key,g.group_id",new String[]{""+kind})){while(c.moveToNext())result.add(group(c));}return result;
    }
    Group group(int kind,long id){
        worker();kind(kind);if(id<=0)throw new IllegalArgumentException("Grupo inválido");
        try(Cursor c=db.rawQuery("SELECT g.group_id,g.kind,g.name,(SELECT count(*) FROM personal_members p JOIN media m ON m.channel=p.channel AND m.id=p.id AND m.revision=p.revision WHERE p.group_id=g.group_id) FROM personal_groups g WHERE g.kind=? AND g.group_id=?",new String[]{""+kind,""+id})){return c.moveToFirst()?group(c):null;}
    }
    private static Group group(Cursor c){return new Group(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3));}
    long saveGroup(int kind,long id,String proposed){
        worker();kind(kind);if(id<0)throw new IllegalArgumentException("Grupo inválido");String name=name(proposed),key=name.toLowerCase(Locale.ROOT);
        db.beginTransaction();try{
            if(scalar("SELECT count(*) FROM personal_groups WHERE kind=? AND name_key=? AND group_id<>?",""+kind,key,""+id)>0)throw new IllegalStateException("Ya existe un grupo con ese nombre");
            if(id==0){
                if(scalar("SELECT count(*) FROM personal_groups WHERE kind=?",""+kind)>=500)throw new IllegalStateException("Puedes guardar hasta 500 grupos por tipo");
                db.execSQL("INSERT INTO personal_groups(kind,name,name_key,updated) VALUES(?,?,?,?)",new Object[]{kind,name,key,System.currentTimeMillis()});id=inserted();
            }else{
                requireGroup(kind,id);db.execSQL("UPDATE personal_groups SET name=?,name_key=?,updated=? WHERE group_id=? AND kind=?",new Object[]{name,key,System.currentTimeMillis(),id,kind});
            }
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    void deleteGroup(int kind,long id){worker();kind(kind);if(id<=0)throw new IllegalArgumentException("Grupo inválido");db.execSQL("DELETE FROM personal_groups WHERE group_id=? AND kind=?",new Object[]{id,kind});}
    private void requireGroup(int kind,long id){
        if(id<=0)throw new IllegalArgumentException("Grupo inválido");
        try(Cursor c=db.rawQuery("SELECT kind FROM personal_groups WHERE group_id=?",new String[]{""+id})){if(!c.moveToFirst())throw new IllegalStateException("El grupo ya no existe");if(c.getInt(0)!=kind)throw new IllegalArgumentException("Tipo de grupo incorrecto");}
    }
    Set<Long> groupIds(Item item,int kind){
        worker();item(item);kind(kind);Set<Long> result=new LinkedHashSet<>();
        try(Cursor c=db.rawQuery("SELECT p.group_id FROM personal_members p JOIN personal_groups g ON g.group_id=p.group_id JOIN media m ON m.channel=p.channel AND m.id=p.id AND m.revision=p.revision WHERE p.channel=? AND p.id=? AND p.revision=? AND g.kind=? ORDER BY p.group_id",new String[]{""+item.channel,""+item.id,item.revision,""+kind})){while(c.moveToNext())result.add(c.getLong(0));}return result;
    }
    int setMembers(int kind,long groupId,List<Item> items,boolean value){
        worker();kind(kind);List<Item> selected=selection(items);int applied=0;
        db.beginTransaction();try{
            requireGroup(kind,groupId);
            for(Item item:selected){
                if(current(item)==null)continue;
                if(value){
                    if(scalar("SELECT count(*) FROM personal_members WHERE group_id=? AND channel=? AND id=? AND revision=?",""+groupId,""+item.channel,""+item.id,item.revision)==0&&scalar("SELECT count(*) FROM personal_members WHERE channel=? AND id=? AND revision=?",""+item.channel,""+item.id,item.revision)>=64)throw new IllegalStateException("Un archivo admite hasta 64 álbumes y etiquetas");
                    db.execSQL("INSERT INTO personal_members(group_id,channel,id,revision) VALUES(?,?,?,?) ON CONFLICT(group_id,channel,id) DO UPDATE SET revision=excluded.revision",new Object[]{groupId,item.channel,item.id,item.revision});
                }else db.execSQL("DELETE FROM personal_members WHERE group_id=? AND channel=? AND id=? AND revision=?",new Object[]{groupId,item.channel,item.id,item.revision});
                applied++;
            }
            db.setTransactionSuccessful();return applied;
        }finally{db.endTransaction();}
    }
    static final class Group {
        final long id,count;final int kind;final String name;
        Group(long id,int kind,String name,long count){this.id=id;this.kind=kind;this.name=name;this.count=count;}
    }

    List<Bookmark> bookmarks(Item item){
        worker();item(item);List<Bookmark> result=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT b.bookmark_id,b.position_ms,b.duration_ms,b.name,b.created FROM video_bookmarks b JOIN media m ON m.channel=b.channel AND m.id=b.id AND m.revision=b.revision WHERE b.channel=? AND b.id=? AND b.revision=? AND m.mime LIKE 'video/%' ORDER BY b.position_ms,b.bookmark_id",new String[]{""+item.channel,""+item.id,item.revision})){while(c.moveToNext())result.add(new Bookmark(c.getLong(0),c.getInt(1),c.getInt(2),c.getString(3),c.getLong(4)));}return result;
    }
    long saveBookmark(Item item,int position,int duration,String proposed){
        worker();item(item);String name=name(proposed);if(position<0||duration<=0||position>duration)throw new IllegalArgumentException("Momento de vídeo inválido");
        db.beginTransaction();try{
            Item current=current(item);if(current==null)throw new IllegalStateException("El archivo ha cambiado");if(!current.video())throw new IllegalArgumentException("Los marcadores son sólo para vídeos");
            long id=scalar("SELECT bookmark_id FROM video_bookmarks WHERE channel=? AND id=? AND revision=? AND position_ms=?",""+item.channel,""+item.id,item.revision,""+position);
            if(id==0){
                if(scalar("SELECT count(*) FROM video_bookmarks WHERE channel=? AND id=? AND revision=?",""+item.channel,""+item.id,item.revision)>=200)throw new IllegalStateException("Puedes guardar hasta 200 momentos por vídeo");
                db.execSQL("INSERT INTO video_bookmarks(channel,id,revision,position_ms,duration_ms,name,created) VALUES(?,?,?,?,?,?,?)",new Object[]{item.channel,item.id,item.revision,position,duration,name,System.currentTimeMillis()});id=inserted();
            }else db.execSQL("UPDATE video_bookmarks SET name=?,duration_ms=? WHERE bookmark_id=?",new Object[]{name,duration,id});
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    void deleteBookmark(Item item,long bookmarkId){worker();item(item);if(bookmarkId<=0)throw new IllegalArgumentException("Marcador inválido");db.execSQL("DELETE FROM video_bookmarks WHERE bookmark_id=? AND channel=? AND id=? AND revision=?",new Object[]{bookmarkId,item.channel,item.id,item.revision});}
    static final class Bookmark {
        final long id,created;final int position,duration;final String name;
        Bookmark(long id,int position,int duration,String name,long created){this.id=id;this.position=position;this.duration=duration;this.name=name;this.created=created;}
    }

    Change change(List<Item> items,String action){
        worker();List<Item> selected=selection(items);String property;
        switch(action==null?"":action){case "favorite":case "unfavorite":property="favorite";break;case "later":case "unlater":property="later";break;case "archive":case "unarchive":property="archived";break;case "viewed":case "unviewed":property="viewed";break;default:throw new IllegalArgumentException("Acción inválida");}
        List<Snapshot> snapshots=new ArrayList<>();
        db.beginTransaction();try{
            for(Item item:selected){Item current=current(item);if(current==null||("viewed".equals(property)&&!current.video()))continue;snapshots.add(new Snapshot(current,value(current,property)));}
            int applied=library.applySelection(selected,action);
            for(Snapshot snapshot:snapshots){snapshot.expected=value(snapshot,property);snapshot.version=version(snapshot,property);snapshot.generation=generation(snapshot);}
            db.setTransactionSuccessful();return new Change(applied,property,snapshots);
        }finally{db.endTransaction();}
    }
    private long value(Item item,String property){return value(item.channel,item.id,item.revision,property);}
    private long value(Snapshot snapshot,String property){return value(snapshot.channel,snapshot.id,snapshot.revision,property);}
    private long value(int channel,int id,String revision,String property){
        if("viewed".equals(property))try(Cursor c=db.rawQuery("SELECT viewed_at FROM viewed_media WHERE channel=? AND id=? AND revision=?",new String[]{""+channel,""+id,revision})){return c.moveToFirst()?c.getLong(0):-1;}
        return scalar("SELECT "+property+" FROM media_state WHERE channel=? AND id=? AND revision=?",""+channel,""+id,revision);
    }
    private long version(Snapshot snapshot,String property){return scalar("SELECT "+property+" FROM personal_mark_versions WHERE channel=? AND id=? AND revision=?",""+snapshot.channel,""+snapshot.id,snapshot.revision);}
    private long generation(Snapshot snapshot){return scalar("SELECT generation FROM personal_mark_versions WHERE channel=? AND id=? AND revision=?",""+snapshot.channel,""+snapshot.id,snapshot.revision);}
    private static final class Snapshot {
        final int channel,id;final String revision;final long before;long expected,version,generation;
        Snapshot(Item item,long before){channel=item.channel;id=item.id;revision=item.revision;this.before=before;}
    }
    final class Change {
        final int applied;private final String property;private List<Snapshot> snapshots;
        Change(int applied,String property,List<Snapshot> snapshots){this.applied=applied;this.property=property;this.snapshots=snapshots;}
        synchronized int undo(){
            worker();if(snapshots==null)return 0;List<Snapshot> pending=snapshots;snapshots=null;int restored=0;
            db.beginTransaction();try{
                for(Snapshot snapshot:pending){
                    if(scalar("SELECT count(*) FROM media WHERE channel=? AND id=? AND revision=?",""+snapshot.channel,""+snapshot.id,snapshot.revision)==0||generation(snapshot)!=snapshot.generation||version(snapshot,property)!=snapshot.version||value(snapshot,property)!=snapshot.expected)continue;
                    if("viewed".equals(property)){
                        if(snapshot.before<0)db.execSQL("DELETE FROM viewed_media WHERE channel=? AND id=? AND revision=?",new Object[]{snapshot.channel,snapshot.id,snapshot.revision});
                        else db.execSQL("INSERT INTO viewed_media(channel,id,revision,viewed_at) VALUES(?,?,?,?) ON CONFLICT(channel,id) DO UPDATE SET viewed_at=excluded.viewed_at",new Object[]{snapshot.channel,snapshot.id,snapshot.revision,snapshot.before});
                    }else db.execSQL("UPDATE media_state SET "+property+"=?,updated=? WHERE channel=? AND id=? AND revision=?",new Object[]{snapshot.before,System.currentTimeMillis(),snapshot.channel,snapshot.id,snapshot.revision});
                    restored++;
                }
                db.setTransactionSuccessful();return restored;
            }finally{db.endTransaction();}
        }
    }
}
