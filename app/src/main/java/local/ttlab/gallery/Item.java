package local.ttlab.gallery;
import android.database.Cursor;
final class Item {
    final int id,channel,width,height,duration;final String name,mime;final long size,date;
    final String revision,contentKey;
    String webVersion="";
    boolean viewed;int stackCount=1;
    boolean favorite,watchLater,completed,archived;int positionMs,durationMs;
    Item(int i,int c,String n,String m,long s,long d,int w,int h,int t){this(i,c,n,m,s,d,w,h,t,"legacy:"+d+":"+s+":"+n+":"+m);}
    Item(int i,int c,String n,String m,long s,long d,int w,int h,int t,String r){this(i,c,n,m,s,d,w,h,t,r,"");}
    Item(int i,int c,String n,String m,long s,long d,int w,int h,int t,String r,String key){id=i;channel=c;name=n;mime=m;size=s;date=d;width=w;height=h;duration=t;revision=r;contentKey=key==null?"":key;}
    static Item cursor(Cursor c){Item item=new Item(c.getInt(0),c.getInt(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getInt(6),c.getInt(7),c.getInt(8),c.getColumnCount()>9?c.getString(9):"",c.getColumnCount()>10?c.getString(10):"");int stack=c.getColumnIndex("stack_count");if(stack>=0)item.stackCount=Math.max(1,c.getInt(stack));int seen=c.getColumnIndex("viewed");item.viewed=seen>=0&&c.getInt(seen)!=0;int n=c.getColumnIndex("favorite");if(n>=0){item.favorite=c.getInt(n)!=0;item.watchLater=c.getInt(c.getColumnIndexOrThrow("later"))!=0;item.positionMs=c.getInt(c.getColumnIndexOrThrow("position_ms"));item.durationMs=c.getInt(c.getColumnIndexOrThrow("duration_ms"));item.completed=c.getInt(c.getColumnIndexOrThrow("completed"))!=0;int archive=c.getColumnIndex("archived");item.archived=archive>=0&&c.getInt(archive)!=0;}return item;}
    String key(){return channel+"/"+id;}
    String cacheKey(){String base=revision.isEmpty()?key():key()+"/"+revision;return webVersion.isEmpty()?base:base+"/"+webVersion;}
    Item webRepresentation(long length,String version){
        Item opened=new Item(id,channel,name,mime,length,date,width,height,duration,revision,contentKey);
        opened.webVersion=version;
        return opened;
    }
    boolean video(){return mime.startsWith("video/");}
    String label(){return name.isEmpty()?(video()?"Video sin nombre":"Imagen sin nombre"):name;}
    int chunks(){return (int)((size+Source.CHUNK-1)/Source.CHUNK);}
}
