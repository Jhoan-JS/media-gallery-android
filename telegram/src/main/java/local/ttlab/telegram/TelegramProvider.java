package local.ttlab.telegram;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Only selected, encrypted local references cross the signature permission boundary. */
public final class TelegramProvider extends ContentProvider {
    private final ConcurrentHashMap<String,String> sponsored=new ConcurrentHashMap<>();
    @Override public boolean onCreate(){return true;}
    private CallerGate.Caller caller(){return CallerGate.provider(getContext(),getCallingPackage());}
    private static int integer(Uri uri,String name,int fallback) {String value=uri.getQueryParameter(name);return value==null?fallback:Integer.parseInt(value);}
    private static int id(String value) {int result=Integer.parseInt(value);if(result<=0)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");return result;}
    private static void path(Uri uri) {
        if(uri.getEncodedFragment()!=null||uri.getPath()==null||uri.getPath().contains("..")||uri.getQueryParameter("locator")!=null)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {return query(uri,projection,selection,args,sort,null);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort,CancellationSignal cancellation) {
        try {
            CallerGate.Caller caller=caller();path(uri);
            if(selection!=null||args!=null||sort!=null)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
            ConnectorRuntime runtime=ConnectorRuntime.get(getContext());List<String> path=uri.getPathSegments();
            boolean identity=path.size()==1&&"identity".equals(path.get(0));
            return runtime.provider(caller,uri.getQueryParameter("account"),!identity,cancellation,()->{
                String account=runtime.db.bound();
                if(identity){MatrixCursor cursor=new MatrixCursor(new String[]{"account"});cursor.addRow(new Object[]{account});return cursor;}
                if(path.size()==1&&"capabilities".equals(path.get(0))){MatrixCursor cursor=new MatrixCursor(new String[]{"protocol","max_range"});cursor.addRow(new Object[]{4,BridgePolicy.MAX_RANGE});return cursor;}
                if(path.size()==1&&"channels".equals(path.get(0)))return runtime.db.channels(account);
                if(path.size()==2&&"media".equals(path.get(0))) {
                    int channel=id(path.get(1)),cursor=integer(uri,"cursor",0),limit=integer(uri,"limit",50);
                    runtime.db.requirePeer(account,channel,uri.getQueryParameter("peer_key"));
                    if(cursor<0||limit<1||limit>50)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
                    JSONObject response=runtime.request(new JSONObject().put("op","page").put("peer",runtime.db.peer(account,channel,true)).put("cursor",cursor).put("limit",limit));
                    int next=response.getInt("next");if(cursor>0&&next>=cursor)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
                    return runtime.db.page(account,channel,response);
                }
                if(path.size()==2&&"sponsored".equals(path.get(0))) {
                    int channel=id(path.get(1));runtime.db.requirePeer(account,channel,uri.getQueryParameter("peer_key"));JSONObject response=runtime.request(new JSONObject().put("op","sponsored").put("peer",runtime.db.peer(account,channel,true)));
                    JSONArray messages=response.getJSONArray("messages");if(messages.length()>100)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
                    for(int i=0;i<messages.length();i++){String messageId=messages.getJSONObject(i).getString("id");if(messageId.length()>1024)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");sponsored.put(account+"/"+channel+"/"+messageId,"allowed");}
                    if(sponsored.size()>1000)sponsored.clear();
                    MatrixCursor result=new MatrixCursor(new String[]{"json"});result.addRow(new Object[]{response.toString()});return result;
                }
                throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
            });
        }catch(SecurityException denied){throw new SecurityException(BridgePolicy.error(denied));}
        catch(Exception failed){throw new IllegalStateException(BridgePolicy.error(failed));}
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        try {
            CallerGate.Caller caller=caller();if(extras==null)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
            ConnectorRuntime runtime=ConnectorRuntime.get(getContext());String account=extras.getString("account");int channel=extras.getInt("channel",extras.getInt("localchat",0));
            return runtime.provider(caller,account,true,null,()->{
                runtime.db.requirePeer(runtime.db.bound(),channel,extras.getString("peer_key"));
                JSONObject peer=runtime.db.peer(account,channel,true),request=new JSONObject().put("op",method);
                if("opened".equals(method)) {
                    int message=extras.getInt("id",0);if(message<=0)throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
                    runtime.db.knownMessage(account,channel,message);
                    request.put("peer",peer).put("id",message);
                } else if("sponsored_view".equals(method)||"sponsored_click".equals(method)) {
                    String message=extras.getString("id");if(message==null||!sponsored.containsKey(account+"/"+channel+"/"+message))throw new SecurityException("E_TELEGRAM_SPONSORED_UNKNOWN");
                    request.put("id",message);
                }else throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
                Bundle result=new Bundle();result.putString("json",runtime.request(request).toString());return result;
            });
        }catch(SecurityException denied){throw new SecurityException(BridgePolicy.error(denied));}
        catch(Exception failed){throw new IllegalStateException(BridgePolicy.error(failed));}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {return openFile(uri,mode,null);}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode,CancellationSignal cancellation)throws FileNotFoundException {
        try {
            CallerGate.Caller caller=caller();path(uri);
            if(!"r".equals(mode))throw new SecurityException("E_READ_ONLY");
            List<String> path=uri.getPathSegments();if(path.size()!=3||!path.get(0).matches("file|stream|thumb"))throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
            ConnectorRuntime runtime=ConnectorRuntime.get(getContext());String account=uri.getQueryParameter("account");int channel=id(path.get(1)),message=id(path.get(2));boolean thumbnail="thumb".equals(path.get(0));
            byte[] bytes=runtime.provider(caller,account,true,cancellation,()->{
                runtime.db.requirePeer(runtime.db.bound(),channel,uri.getQueryParameter("peer_key"));
                ConnectorDb.Media media=runtime.db.media(account,channel,message,uri.getQueryParameter("revision"),thumbnail);
                String offsetText=uri.getQueryParameter("offset");long offset=offsetText==null?0:Long.parseLong(offsetText);
                long available=thumbnail?media.locator.getLong("size"):media.size;
                int length=integer(uri,"length",(int)Math.min(131072,available));
                if(!BridgePolicy.range(offset,length,available)||(thumbnail&&(offset!=0||available>131072||length!=available)))throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");
                return runtime.read(media,offset,length,thumbnail);
            });
            return openPipeHelper(uri,"application/octet-stream",null,bytes,(output,u,mime,options,data)->{
                try(OutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                    runtime.consent(caller,account,true);stream.write(data);
                }catch(Exception failed){try{output.closeWithError("E_TELEGRAM_TRANSFER");}catch(Exception ignored){}}
                finally{Arrays.fill(data,(byte)0);}
            });
        }catch(SecurityException denied){throw new SecurityException(BridgePolicy.error(denied));}
        catch(Exception failed){throw new FileNotFoundException(BridgePolicy.error(failed));}
    }
    @Override public String getType(Uri uri){caller();return "application/octet-stream";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
