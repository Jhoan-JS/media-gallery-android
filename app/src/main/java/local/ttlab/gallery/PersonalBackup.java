package local.ttlab.gallery;

import android.database.Cursor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Portable organization only; undo snapshots and local compare-and-set generations stay local. */
final class PersonalBackup {
    private static final int MAX_RECORD=128*1024;
    private static final String PERSONAL="EXISTS(SELECT 1 FROM recent_media r WHERE r.channel=media.channel AND r.id=media.id) OR EXISTS(SELECT 1 FROM personal_members p WHERE p.channel=media.channel AND p.id=media.id) OR EXISTS(SELECT 1 FROM video_bookmarks b WHERE b.channel=media.channel AND b.id=media.id)";
    private final Library library;
    private final long[] expected,seen=new long[5];

    private PersonalBackup(Library library,long[] expected){this.library=library;this.expected=expected;}

    static long[] counts(Library library)throws IOException {
        validate(library);
        return new long[]{scalar(library,"SELECT count(*) FROM personal_groups"),scalar(library,"SELECT count(*) FROM media WHERE "+PERSONAL),scalar(library,"SELECT count(*) FROM personal_members"),scalar(library,"SELECT count(*) FROM recent_media"),scalar(library,"SELECT count(*) FROM video_bookmarks")};
    }
    static void manifest(DataOutputStream d,Library library,long[] counts)throws IOException {
        for(long count:counts)d.writeLong(count);String enabled=library.setting("history-enabled","0");
        if(!enabled.equals("0")&&!enabled.equals("1"))throw invalid();d.writeInt(Integer.parseInt(enabled));
    }
    static PersonalBackup manifest(Library library,DataInputStream d,long media)throws IOException {
        long[] counts=new long[5];for(int i=0;i<counts.length;i++)counts[i]=d.readLong();int enabled=d.readInt();
        if(counts[0]<0||counts[0]>1000||counts[1]<0||counts[1]>media||counts[2]<0||counts[2]>counts[1]*64||counts[3]<0||counts[3]>10000||counts[3]>counts[1]||counts[4]<0||counts[4]>counts[1]*200||(enabled!=0&&enabled!=1))throw invalid();
        library.setSetting("history-enabled",""+enabled);return new PersonalBackup(library,counts);
    }
    static void export(State state,BackupArchive.Writer writer,GalleryBackup.Progress progress)throws IOException {
        Library library=state.library;
        try(Cursor c=library.db.rawQuery("SELECT group_id,kind,name,updated FROM personal_groups ORDER BY group_id",(String[])null)) {
            while(c.moveToNext()) {
                active(state);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);
                d.writeLong(c.getLong(0));d.writeInt(c.getInt(1));string(d,c.getString(2));d.writeLong(c.getLong(3));writer.put("group/"+c.getLong(0),record(bytes));
                progress.update("Exportando álbumes y etiquetas",c.getPosition()+1);
            }
        }
        try(Cursor c=library.db.rawQuery("SELECT id,channel,revision FROM media WHERE "+PERSONAL+" ORDER BY channel,id",(String[])null)) {
            while(c.moveToNext()) {
                active(state);int id=c.getInt(0),channel=c.getInt(1);String[] args={""+channel,""+id};ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);
                d.writeInt(id);d.writeInt(channel);string(d,c.getString(2));
                try(Cursor recent=library.db.rawQuery("SELECT opened_at FROM recent_media WHERE channel=? AND id=?",args)){boolean found=recent.moveToFirst();d.writeInt(found?1:0);if(found)d.writeLong(recent.getLong(0));}
                try(Cursor members=library.db.rawQuery("SELECT group_id FROM personal_members WHERE channel=? AND id=? ORDER BY group_id",args)){d.writeInt(members.getCount());while(members.moveToNext())d.writeLong(members.getLong(0));}
                try(Cursor bookmarks=library.db.rawQuery("SELECT bookmark_id,position_ms,duration_ms,name,created FROM video_bookmarks WHERE channel=? AND id=? ORDER BY bookmark_id",args)){d.writeInt(bookmarks.getCount());while(bookmarks.moveToNext()){d.writeLong(bookmarks.getLong(0));d.writeInt(bookmarks.getInt(1));d.writeInt(bookmarks.getInt(2));string(d,bookmarks.getString(3));d.writeLong(bookmarks.getLong(4));}}
                writer.put("personal/"+channel+"/"+id,record(bytes));progress.update("Exportando historial, grupos y marcadores",c.getPosition()+1);
            }
        }
    }
    boolean accept(String name,byte[] bytes)throws IOException {
        if(!name.startsWith("group/")&&!name.startsWith("personal/"))return false;
        if(bytes.length>MAX_RECORD)throw invalid();DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes));
        if(name.startsWith("group/")) {
            long id=d.readLong();int kind=d.readInt();String title=name(string(d,240));long updated=d.readLong();end(d);
            if(id<=0||(kind!=0&&kind!=1)||updated<0||!name.equals("group/"+id))throw invalid();add(0,1);
            library.db.execSQL("INSERT INTO personal_groups(group_id,kind,name,name_key,updated) VALUES(?,?,?,?,?)",new Object[]{id,kind,title,title.toLowerCase(Locale.ROOT),updated});return true;
        }
        int id=d.readInt(),channel=d.readInt();String revision=string(d,16384);int recent=d.readInt();
        if(id<=0||channel<=0||!name.equals("personal/"+channel+"/"+id)||(recent!=0&&recent!=1))throw invalid();add(1,1);
        if(recent==1){long opened=d.readLong();if(opened<0)throw invalid();add(3,1);library.db.execSQL("INSERT INTO recent_media(channel,id,revision,opened_at) VALUES(?,?,?,?)",new Object[]{channel,id,revision,opened});}
        int members=d.readInt();if(members<0||members>64)throw invalid();add(2,members);
        for(int i=0;i<members;i++){long group=d.readLong();if(group<=0)throw invalid();library.db.execSQL("INSERT INTO personal_members(group_id,channel,id,revision) VALUES(?,?,?,?)",new Object[]{group,channel,id,revision});}
        int bookmarks=d.readInt();if(bookmarks<0||bookmarks>200||(recent==0&&members==0&&bookmarks==0))throw invalid();add(4,bookmarks);
        for(int i=0;i<bookmarks;i++) {
            long bookmark=d.readLong();int position=d.readInt(),duration=d.readInt();String title=name(string(d,240));long created=d.readLong();
            if(bookmark<=0||position<0||duration<=0||position>duration||created<0)throw invalid();
            library.db.execSQL("INSERT INTO video_bookmarks(bookmark_id,channel,id,revision,position_ms,duration_ms,name,created) VALUES(?,?,?,?,?,?,?,?)",new Object[]{bookmark,channel,id,revision,position,duration,title,created});
        }
        end(d);return true;
    }
    void finish()throws IOException {if(!Arrays.equals(expected,seen))throw new IOException("Organización del respaldo incompleta");validate(library);}
    static boolean empty(Library library){return scalar(library,"SELECT (SELECT count(*) FROM personal_groups)+(SELECT count(*) FROM personal_members)+(SELECT count(*) FROM recent_media)+(SELECT count(*) FROM video_bookmarks)")==0;}
    static void collections(Library library)throws IOException {
        for(SavedCollection collection:library.collections()) {
            if(collection.filter.albumId>0&&exists(library,"SELECT 1 FROM personal_groups WHERE group_id=? AND kind<>0",new String[]{""+collection.filter.albumId}))throw invalid();
            if(collection.filter.tagId>0&&exists(library,"SELECT 1 FROM personal_groups WHERE group_id=? AND kind<>1",new String[]{""+collection.filter.tagId}))throw invalid();
        }
    }
    private static void validate(Library library)throws IOException {
        Set<String> names=new HashSet<>();int[] groups=new int[2];
        try(Cursor c=library.db.rawQuery("SELECT group_id,kind,name,name_key,updated FROM personal_groups",(String[])null)){while(c.moveToNext()){int kind=c.getInt(1);String title=name(c.getString(2));if(c.getLong(0)<=0||kind<0||kind>1||++groups[kind]>500||c.getLong(4)<0||!title.equals(c.getString(2))||!title.toLowerCase(Locale.ROOT).equals(c.getString(3))||!names.add(kind+":"+title.toLowerCase(Locale.ROOT)))throw invalid();}}
        if(scalar(library,"SELECT count(*) FROM recent_media")>10000||exists(library,"SELECT 1 FROM recent_media r LEFT JOIN media m ON m.channel=r.channel AND m.id=r.id WHERE m.id IS NULL OR m.revision<>r.revision OR r.opened_at<0 LIMIT 1",null))throw invalid();
        if(exists(library,"SELECT 1 FROM personal_members p LEFT JOIN media m ON m.channel=p.channel AND m.id=p.id LEFT JOIN personal_groups g ON g.group_id=p.group_id WHERE m.id IS NULL OR m.revision<>p.revision OR g.group_id IS NULL LIMIT 1",null)||exists(library,"SELECT 1 FROM personal_members GROUP BY channel,id HAVING count(*)>64 LIMIT 1",null))throw invalid();
        if(exists(library,"SELECT 1 FROM video_bookmarks b LEFT JOIN media m ON m.channel=b.channel AND m.id=b.id WHERE m.id IS NULL OR m.revision<>b.revision OR m.mime NOT LIKE 'video/%' OR b.bookmark_id<=0 OR b.position_ms<0 OR b.duration_ms<=0 OR b.position_ms>b.duration_ms OR b.duration_ms>2147483647 OR b.created<0 LIMIT 1",null)||exists(library,"SELECT 1 FROM video_bookmarks GROUP BY channel,id HAVING count(*)>200 LIMIT 1",null))throw invalid();
        try(Cursor c=library.db.rawQuery("SELECT name FROM video_bookmarks",(String[])null)){while(c.moveToNext())if(!name(c.getString(0)).equals(c.getString(0)))throw invalid();}
        collections(library);
    }
    private void add(int type,long count)throws IOException {seen[type]+=count;if(seen[type]>expected[type])throw invalid();}
    private static String name(String value)throws IOException {if(value==null)throw invalid();String title=Normalizer.normalize(value,Normalizer.Form.NFKC).trim();if(title.isEmpty()||title.length()>60||title.chars().anyMatch(Character::isISOControl))throw invalid();return title;}
    private static byte[] record(ByteArrayOutputStream bytes)throws IOException {if(bytes.size()>MAX_RECORD)throw invalid();return bytes.toByteArray();}
    private static void string(DataOutputStream d,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw invalid();d.writeInt(bytes.length);d.write(bytes);}
    private static String string(DataInputStream d,int max)throws IOException {int length=d.readInt();if(length<0||length>max||length>d.available())throw invalid();byte[] bytes=new byte[length];d.readFully(bytes);String value=new String(bytes,StandardCharsets.UTF_8);if(value.indexOf('\0')>=0||!Arrays.equals(bytes,value.getBytes(StandardCharsets.UTF_8)))throw invalid();return value;}
    private static void end(DataInputStream d)throws IOException {if(d.read()!=-1)throw invalid();}
    private static void active(State state)throws IOException {if(!State.unlocked||Thread.currentThread().isInterrupted())throw new IOException("Desbloquea la biblioteca para exportar");}
    private static long scalar(Library library,String query){try(Cursor c=library.db.rawQuery(query,(String[])null)){if(!c.moveToFirst())throw new IllegalStateException("Consulta incompleta");return c.getLong(0);}}
    private static boolean exists(Library library,String query,String[] args){try(Cursor c=library.db.rawQuery(query,args)){return c.moveToFirst();}}
    private static IOException invalid(){return new IOException("Organización de respaldo inválida");}
}
