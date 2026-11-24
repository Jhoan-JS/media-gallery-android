package local.ttlab.gallery;

import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Folders and pins belong to an individual encrypted catalogue. */
final class ChatFolders {
    static final int MAX_FOLDERS=50,MAX_MEMBERS=5000,MAX_PINS=200;
    private final SQLiteDatabase db;

    ChatFolders(SQLiteDatabase db) {
        this.db = db;
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_folders(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,name_key TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_folder_members(folder INTEGER NOT NULL,channel INTEGER NOT NULL,PRIMARY KEY(folder,channel)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_folder_channel ON chat_folder_members(channel)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pinned_chats(channel INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chat_folder_delete AFTER DELETE ON chat_folders BEGIN DELETE FROM chat_folder_members WHERE folder=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chat_channel_delete AFTER DELETE ON channels BEGIN DELETE FROM chat_folder_members WHERE channel=old.id; DELETE FROM pinned_chats WHERE channel=old.id; END");
    }

    static final class Folder {
        final long id;
        final String name;
        final Set<Integer> channels = new HashSet<>();
        Folder(long id, String name) {this.id=id; this.name=name;}
    }

    static final class Snapshot {
        final Map<Long,Folder> folders = new LinkedHashMap<>();
        final Set<Integer> pins = new HashSet<>();

        List<Source.Channel> filter(List<Source.Channel> channels, String query, boolean includeAll, long folderId) {
            Folder folder = folders.get(folderId);
            String needle = query.trim().toLowerCase(Locale.ROOT);
            List<Source.Channel> rows = new ArrayList<>();
            for (Source.Channel channel : channels) {
                if (channel.id==0 && !includeAll) continue;
                if (folder!=null && !folder.channels.contains(channel.id)) continue;
                if (channel.title.toLowerCase(Locale.ROOT).contains(needle)) rows.add(channel);
            }
            Collections.sort(rows, (a,b) -> Integer.compare(rank(a.id), rank(b.id)));
            return rows;
        }

        private int rank(int channel) {return channel==0?0:pins.contains(channel)?1:2;}
    }

    Snapshot snapshot() {
        Snapshot result = new Snapshot();
        try (Cursor rows = db.rawQuery("SELECT id,name FROM chat_folders ORDER BY name_key,id", (String[])null)) {
            while (rows.moveToNext()) result.folders.put(rows.getLong(0), new Folder(rows.getLong(0),rows.getString(1)));
        }
        try (Cursor rows = db.rawQuery("SELECT folder,channel FROM chat_folder_members", (String[])null)) {
            while (rows.moveToNext()) {
                Folder folder = result.folders.get(rows.getLong(0));
                if (folder!=null) folder.channels.add(rows.getInt(1));
            }
        }
        try (Cursor rows = db.rawQuery("SELECT channel FROM pinned_chats", (String[])null)) {
            while (rows.moveToNext()) result.pins.add(rows.getInt(0));
        }
        return result;
    }

    static String checkedName(String proposed){
        if(proposed==null)throw new IllegalArgumentException("Nombre de carpeta inválido");
        String name = SavedCollection.checkedName(java.text.Normalizer.normalize(proposed,java.text.Normalizer.Form.NFKC));
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals("todos") || key.equals("crear carpeta") || key.equals("renombrar carpeta") || key.equals("eliminar carpeta"))
            throw new IllegalArgumentException("Elige otro nombre para la carpeta");
        return name;
    }

    long save(long id, String proposed) {
        if(id<0)throw new IllegalArgumentException("Nombre de carpeta inválido");
        String name=checkedName(proposed),key=name.toLowerCase(Locale.ROOT);
        db.beginTransaction();
        try {
            if (value("SELECT count(*) FROM chat_folders WHERE name_key=? AND id<>?",key,""+id)>0)
                throw new IllegalArgumentException("Ya existe una carpeta con ese nombre");
            if (id==0) {
                if (value("SELECT count(*) FROM chat_folders")>=MAX_FOLDERS) throw new IllegalArgumentException("Puedes guardar hasta "+MAX_FOLDERS+" carpetas");
                db.execSQL("INSERT INTO chat_folders(name,name_key) VALUES(?,?)",new Object[]{name,key});
                id=value("SELECT last_insert_rowid()");
            } else {
                if (value("SELECT count(*) FROM chat_folders WHERE id=?",""+id)==0) throw new IllegalArgumentException("La carpeta ya no existe");
                db.execSQL("UPDATE chat_folders SET name=?,name_key=? WHERE id=?",new Object[]{name,key,id});
            }
            db.setTransactionSuccessful();
            return id;
        } finally {db.endTransaction();}
    }

    void delete(long folder) {db.execSQL("DELETE FROM chat_folders WHERE id=?",new Object[]{folder});}

    void member(long folder,int channel,boolean include) {
        if (!include) {
            db.execSQL("DELETE FROM chat_folder_members WHERE folder=? AND channel=?",new Object[]{folder,channel});
            return;
        }
        db.beginTransaction();
        try {
            if (value("SELECT count(*) FROM chat_folder_members WHERE folder=? AND channel=?",""+folder,""+channel)==0) {
                if (value("SELECT count(*) FROM chat_folder_members")>=MAX_MEMBERS) throw new IllegalArgumentException("Se alcanzó el límite de asignaciones a carpetas");
                db.execSQL("INSERT INTO chat_folder_members(folder,channel) SELECT f.id,c.id FROM chat_folders f,channels c WHERE f.id=? AND c.id=? AND c.id>0",new Object[]{folder,channel});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    void pin(int channel,boolean pinned) {
        if (!pinned) {
            db.execSQL("DELETE FROM pinned_chats WHERE channel=?",new Object[]{channel});
            return;
        }
        db.beginTransaction();
        try {
            if (value("SELECT count(*) FROM pinned_chats WHERE channel=?",""+channel)==0) {
                if (value("SELECT count(*) FROM pinned_chats")>=MAX_PINS) throw new IllegalArgumentException("Puedes fijar hasta "+MAX_PINS+" chats");
                db.execSQL("INSERT INTO pinned_chats(channel) SELECT id FROM channels WHERE id=? AND id>0",new Object[]{channel});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    private long value(String sql,String...args) {
        try(Cursor row=db.rawQuery(sql,args)){return row.moveToFirst()?row.getLong(0):0;}
    }
}
