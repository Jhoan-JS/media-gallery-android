package local.ttlab.gallery;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/** Source-local presentation records stored in the same authenticated archive as the catalogue. */
final class PresentationBackup {
    private static final int COVER=0,FOLDER=1,MEMBER=2,PIN=3;
    private static final String READ_COUNTS="SELECT (SELECT count(*) FROM video_covers),(SELECT count(*) FROM chat_folders),"
        +"(SELECT count(*) FROM chat_folder_members),(SELECT count(*) FROM pinned_chats)";
    private final Library library;
    private final int[] expected,seen=new int[4];

    private PresentationBackup(Library library,int[] expected){this.library=library;this.expected=expected;}

    static int[] counts(Library library,long media,long channels)throws IOException{
        int[] counts=new int[4];
        try(Cursor c=library.db.rawQuery(READ_COUNTS,(String[])null)){
            if(!c.moveToFirst())throw invalid();
            for(int i=0;i<counts.length;i++){
                long value=c.getLong(i);
                if(value<0||value>Integer.MAX_VALUE)throw invalid();
                counts[i]=(int)value;
            }
        }
        checkCounts(counts,media,channels);
        if(GalleryBackup.exists(library,"SELECT 1 FROM chat_folder_members m LEFT JOIN chat_folders f ON f.id=m.folder "
            +"LEFT JOIN channels c ON c.id=m.channel WHERE f.id IS NULL OR c.id IS NULL OR c.id<=0 LIMIT 1",null)
            ||GalleryBackup.exists(library,"SELECT 1 FROM pinned_chats p LEFT JOIN channels c ON c.id=p.channel WHERE c.id IS NULL OR c.id<=0 LIMIT 1",null))
            throw invalid();
        return counts;
    }
    static boolean empty(Library library){
        return !GalleryBackup.exists(library,"SELECT 1 WHERE EXISTS(SELECT 1 FROM video_covers) OR EXISTS(SELECT 1 FROM chat_folders) "
            +"OR EXISTS(SELECT 1 FROM chat_folder_members) OR EXISTS(SELECT 1 FROM pinned_chats)",null);
    }
    static PresentationBackup readManifest(Library library,DataInputStream in,long media,long channels)throws IOException{
        int[] counts=new int[4];for(int i=0;i<counts.length;i++)counts[i]=in.readInt();
        checkCounts(counts,media,channels);
        return new PresentationBackup(library,counts);
    }
    private static void checkCounts(int[] counts,long media,long channels)throws IOException{
        if(counts[COVER]<0||counts[COVER]>Math.min(media,Library.MAX_VIDEO_COVERS)
            ||counts[FOLDER]<0||counts[FOLDER]>ChatFolders.MAX_FOLDERS
            ||counts[MEMBER]<0||counts[MEMBER]>Math.min(channels*counts[FOLDER],ChatFolders.MAX_MEMBERS)
            ||counts[PIN]<0||counts[PIN]>Math.min(channels,ChatFolders.MAX_PINS))throw invalid();
    }

    static void export(State state,BackupArchive.Writer writer,GalleryBackup.Progress progress)throws IOException{
        Library library=state.library;
        try(Cursor rows=library.db.rawQuery("SELECT v.channel,v.id,v.revision,v.jpeg,m.revision,m.mime FROM video_covers v "
            +"LEFT JOIN media m ON m.channel=v.channel AND m.id=v.id ORDER BY v.channel,v.id",(String[])null)){
            while(rows.moveToNext()){
                GalleryBackup.checkExport(state);
                int channel=rows.getInt(0),id=rows.getInt(1);
                String revision=rows.getString(2),mime=rows.getString(5);
                if(channel<=0||id<=0||revision==null||!revision.equals(rows.getString(4))||mime==null||!mime.startsWith("video/"))throw invalid();
                byte[] jpeg=rows.getBlob(3);
                try{
                    validateJpeg(jpeg);
                    byte[] record=GalleryBackup.encode(out->{
                        out.writeInt(channel);out.writeInt(id);GalleryBackup.string(out,revision);
                        out.writeInt(jpeg.length);out.write(jpeg);
                    });
                    try{writer.put("cover/"+channel+"/"+id,record);}finally{Arrays.fill(record,(byte)0);}
                }finally{if(jpeg!=null)Arrays.fill(jpeg,(byte)0);}
                progress.update("Exportando portadas elegidas",rows.getPosition()+1);
            }
        }
        try(Cursor folders=library.db.rawQuery("SELECT id,name,name_key FROM chat_folders ORDER BY id",(String[])null)){
            while(folders.moveToNext()){
                GalleryBackup.checkExport(state);
                long folder=folders.getLong(0);
                String title=folders.getString(1);
                if(folder<=0||folder==Long.MAX_VALUE||!ChatFolders.checkedName(title).equals(title)
                    ||!title.toLowerCase(Locale.ROOT).equals(folders.getString(2)))throw invalid();
                try(Cursor members=library.db.rawQuery("SELECT channel FROM chat_folder_members WHERE folder=? ORDER BY channel",new String[]{""+folder})){
                    writer.put("chat-folder/"+folder,GalleryBackup.encode(out->{
                        out.writeLong(folder);GalleryBackup.string(out,title);out.writeInt(members.getCount());
                        while(members.moveToNext())out.writeInt(members.getInt(0));
                    }));
                }
                progress.update("Exportando carpetas de chats",folders.getPosition()+1);
            }
        }
        try(Cursor pins=library.db.rawQuery("SELECT channel FROM pinned_chats ORDER BY channel",(String[])null)){
            while(pins.moveToNext()){
                GalleryBackup.checkExport(state);
                int channel=pins.getInt(0);
                writer.put("chat-pin/"+channel,GalleryBackup.encode(out->out.writeInt(channel)));
                progress.update("Exportando chats fijados",pins.getPosition()+1);
            }
        }
    }
    boolean accept(String name,byte[] bytes)throws IOException{
        if(!name.startsWith("cover/")&&!name.startsWith("chat-folder/")&&!name.startsWith("chat-pin/"))return false;
        try(DataInputStream in=GalleryBackup.data(bytes)){
            if(name.startsWith("cover/"))readCover(name,in);
            else if(name.startsWith("chat-folder/"))readFolder(name,in);
            else{
                int channel=in.readInt();GalleryBackup.end(in);
                if(!name.equals("chat-pin/"+channel))throw invalid();
                add(PIN,1);requireChannel(channel);
                library.db.execSQL("INSERT INTO pinned_chats(channel) VALUES(?)",new Object[]{channel});
            }
        }
        return true;
    }
    private void readCover(String name,DataInputStream in)throws IOException{
        int channel=in.readInt(),id=in.readInt();String revision=GalleryBackup.string(in,16384);
        int size=in.readInt();
        if(channel<=0||id<=0||!name.equals("cover/"+channel+"/"+id)||size<4||size>Source.CHUNK||size!=in.available())throw invalid();
        add(COVER,1);
        Item video=library.get(channel,id);
        if(video==null||!video.video()||!revision.equals(video.revision))throw invalid();
        byte[] jpeg=new byte[size];
        try{in.readFully(jpeg);validateJpeg(jpeg);if(!library.saveCover(video,jpeg))throw invalid();}
        finally{Arrays.fill(jpeg,(byte)0);}
    }
    private void readFolder(String name,DataInputStream in)throws IOException{
        long id=in.readLong();String title=GalleryBackup.string(in,240);int members=in.readInt();
        if(id<=0||id==Long.MAX_VALUE||!name.equals("chat-folder/"+id)||!ChatFolders.checkedName(title).equals(title)
            ||members<0||members>ChatFolders.MAX_MEMBERS||in.available()!=members*Integer.BYTES)throw invalid();
        add(FOLDER,1);add(MEMBER,members);
        library.db.execSQL("INSERT INTO chat_folders(id,name,name_key) VALUES(?,?,?)",new Object[]{id,title,title.toLowerCase(Locale.ROOT)});
        for(int n=0;n<members;n++){
            int channel=in.readInt();requireChannel(channel);
            library.db.execSQL("INSERT INTO chat_folder_members(folder,channel) VALUES(?,?)",new Object[]{id,channel});
        }
    }
    void finish()throws IOException{
        if(!Arrays.equals(expected,seen))throw new IOException("Faltan portadas, carpetas o chats fijados en el respaldo.");
    }
    private void add(int type,int amount)throws IOException{
        seen[type]+=amount;if(seen[type]>expected[type])throw invalid();
    }
    private void requireChannel(int channel)throws IOException{
        if(channel<=0||!GalleryBackup.exists(library,"SELECT 1 FROM channels WHERE id=?",new String[]{""+channel}))throw invalid();
    }
    private static void validateJpeg(byte[] jpeg)throws IOException{
        if(jpeg==null||jpeg.length<4||jpeg.length>Source.CHUNK||(jpeg[0]&255)!=255||(jpeg[1]&255)!=216
            ||(jpeg[jpeg.length-2]&255)!=255||(jpeg[jpeg.length-1]&255)!=217)throw invalid();
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
        if(!"image/jpeg".equals(bounds.outMimeType)||bounds.outWidth<1||bounds.outWidth>MediaCache.PREVIEW_EDGE
            ||bounds.outHeight<1||bounds.outHeight>MediaCache.PREVIEW_EDGE)throw invalid();
        Bitmap image=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        if(image==null)throw invalid();
        image.recycle();
    }
    private static IOException invalid(){return new IOException("Portada, carpeta o chat fijado de respaldo inválido.");}
}
