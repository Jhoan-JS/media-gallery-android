package local.ttlab.gallery;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.*;

/** Telegram uses the existing protected-provider and encrypted block path. */
final class TelegramSource extends Source {
    @Override int playbackBatch(){return 4;}
    @Override int playbackReaders(){return 2;}
    static final String PACKAGE=BuildConfig.TELEGRAM_PACKAGE,AUTHORITY=BuildConfig.TELEGRAM_AUTHORITY;
    private final Context context;private final Library library;private final String authority;
    private Map<Integer,PeerLink> peers;
    private static final class PeerLink {final int provider;final String key;PeerLink(int id,String key){provider=id;this.key=key;}}
    TelegramSource(Context c,Vault vault,Library library){this(c,vault,library,PACKAGE,AUTHORITY);}
    TelegramSource(Context c,Vault vault,Library library,String connector,String authority){super(c,vault,library,connector,authority,true,"TG");context=c.getApplicationContext();this.library=library;this.authority=authority;}
    @Override synchronized List<Channel> channels()throws Exception{
        Map<Integer,PeerLink> fresh=new HashMap<>();List<Channel> result=new ArrayList<>();
        for(Channel remote:super.channels()){
            String key=Library.checkedPeerKey(remote.remoteKey);int local=library.localPeer(key,remote.title,remote.id);
            if(fresh.put(local,new PeerLink(remote.id,key))!=null)throw new SecurityException("E_TELEGRAM_PEER_MISMATCH");
            result.add(new Channel(local,remote.title,key));
        }
        peers=fresh;return result;
    }
    private synchronized PeerLink peer(int id)throws Exception{
        if(peers==null||!peers.containsKey(id))channels();PeerLink link=peers.get(id);
        if(link==null)throw new SecurityException("E_TELEGRAM_UNMAPPED_PEER");return link;
    }
    @Override protected android.net.Uri uri(String path)throws Exception{
        String[] part=path.split("/");
        if(part.length>=2&&("media".equals(part[0])||"file".equals(part[0])||"stream".equals(part[0])||"thumb".equals(part[0])||"sponsored".equals(part[0]))){
            PeerLink link=peer(Integer.parseInt(part[1]));part[1]=Integer.toString(link.provider);
            return super.uri(String.join("/",part)).buildUpon().appendQueryParameter("peer_key",link.key).build();
        }
        return super.uri(path);
    }
    private <T>T mapped(ConnectionRetry.Read<T> operation)throws Exception{
        try{return operation.get();}catch(Exception failed){
            boolean stale=false;Throwable e=failed;for(int depth=0;e!=null&&depth<8;depth++,e=e.getCause())if(String.valueOf(e.getMessage()).contains("E_TELEGRAM_PEER_MISMATCH")){stale=true;break;}
            if(!stale)throw failed;synchronized(this){peers=null;}channels();return operation.get();
        }
    }
    @Override Page page(int channel,int cursor)throws Exception{return mapped(()->{
        Page remote=super.page(channel,cursor),local=new Page();local.next=remote.next;local.newest=remote.newest;
        for(Item i:remote.items)local.items.add(new Item(i.id,channel,i.name,i.mime,i.size,i.date,i.width,i.height,i.duration,i.revision,i.contentKey));return local;
    });}
    @Override byte[] chunk(Item item,int index)throws Exception{return mapped(()->super.chunk(item,index));}
    @Override byte[] range(Item item,int first,int count)throws Exception{return mapped(()->super.range(item,first,count));}
    @Override void rangeBlocks(Item item,int first,int count,android.os.CancellationSignal cancel,BlockConsumer consumer)throws Exception{
        int[] next={first};mapped(()->{if(next[0]<first+count)super.rangeBlocks(item,next[0],first+count-next[0],cancel,(index,bytes)->{consumer.accept(index,bytes);next[0]++;});return true;});
    }
    @Override byte[] thumbnail(Item item)throws Exception{return mapped(()->super.thumbnail(item));}
    @Override void opened(Item item)throws Exception{mapped(()->{call("opened",item.channel,Integer.toString(item.id));return null;});}
    @Override JSONObject sponsored(int channel)throws Exception{return mapped(()->{
        try(Cursor cursor=query(uri("sponsored/"+channel))){
            if(!cursor.moveToFirst())return new JSONObject();String json=cursor.getString(0);
            if(json==null||json.length()>65536)throw new SecurityException("Respuesta de Telegram inválida");return new JSONObject(json);
        }
    });}
    @Override void sponsoredAction(String action,int channel,String id)throws Exception{
        if(!"sponsored_view".equals(action)&&!"sponsored_click".equals(action))throw new IllegalArgumentException("Acción inválida");
        if(id==null||id.length()>512)throw new IllegalArgumentException("Anuncio inválido");mapped(()->{call(action,channel,id);return null;});
    }
    private void call(String method,int channel,String id)throws Exception{
        guard();PeerLink link=peer(channel);Bundle arguments=new Bundle();arguments.putInt("channel",link.provider);arguments.putString("peer_key",link.key);if("opened".equals(method))arguments.putInt("id",Integer.parseInt(id));else arguments.putString("id",id);arguments.putString("account",uri("identity").getQueryParameter("account"));
        Bundle result=context.getContentResolver().call(android.net.Uri.parse("content://"+authority),method,null,arguments);
        if(result==null)throw new java.io.IOException("E_CONNECTOR_UNAVAILABLE");
    }
}
