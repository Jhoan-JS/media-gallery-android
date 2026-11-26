package local.ttlab.telegram;

import android.content.Context;
import android.os.CancellationSignal;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ConnectorRuntime {
    private static ConnectorRuntime instance;
    final Context context;
    final SecureStore store;
    final ConnectorDb db;
    private final ExecutorService engine=Executors.newSingleThreadExecutor();
    private ConnectorTransport client;
    private ConnectorRuntime(Context context)throws Exception {
        this.context=context.getApplicationContext();store=new SecureStore(this.context);db=new ConnectorDb(this.context,store);
    }
    static synchronized ConnectorRuntime get(Context context)throws Exception {
        if(instance==null)instance=new ConnectorRuntime(context);return instance;
    }
    boolean configured()throws Exception {byte[] value=store.get("credentials");try{return value!=null;}finally{if(value!=null)Arrays.fill(value,(byte)0);}}
    void configure(long apiId,String apiHash)throws Exception {
        CallerGate.foreground(context);
        if(apiId<=0||apiId>Integer.MAX_VALUE||!apiHash.matches("[0-9a-fA-F]{32}"))throw new IllegalArgumentException("E_TELEGRAM_CREDENTIALS");
        execute(null,()->{
            if(db.bound()!=null)throw new SecurityException("E_TELEGRAM_ALREADY_CONFIGURED");
            closeClient();
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("E_TELEGRAM_CANCELLED");
            byte[] clear=new JSONObject().put("api_id",apiId).put("api_hash",apiHash).toString().getBytes(StandardCharsets.UTF_8);
            try{store.saveSession(new byte[0]);store.put("credentials",clear);return true;}
            finally{Arrays.fill(clear,(byte)0);}
        });
    }
    private void closeClient() {
        ConnectorTransport previous=client;
        client=null;
        if(previous!=null)previous.close();
    }
    private ConnectorTransport client()throws Exception {
        if(client!=null&&client.available())return client;
        closeClient();
        byte[] clear=store.get("credentials");if(clear==null)throw new IllegalStateException("E_TELEGRAM_SETUP_REQUIRED");
        JSONObject credentials;
        try {credentials=new JSONObject(new String(clear,StandardCharsets.UTF_8));}
        finally{Arrays.fill(clear,(byte)0);}
        try {
            client=ConnectorTransport.open(context,credentials.getLong("api_id"),credentials.getString("api_hash"),store);return client;
        }catch(LinkageError unavailable){throw new IllegalStateException("E_TELEGRAM_ENGINE_UNAVAILABLE");}
    }
    JSONObject request(JSONObject request)throws Exception {
        String response=client().request(request.toString());
        if(response==null||response.length()>8*1024*1024)throw new IllegalStateException("E_TELEGRAM_PROTOCOL");
        return new JSONObject(response);
    }
    JSONObject status()throws Exception {
        JSONObject status=request(new JSONObject().put("op","status"));
        if(status.optBoolean("authorized"))db.bind(status.getString("account"));return status;
    }
    String authorized()throws Exception {
        CallerGate.foreground(context);JSONObject status=status();
        if(!status.optBoolean("authorized"))throw new SecurityException("E_TELEGRAM_LOGIN_REQUIRED");
        String account=status.getString("account");
        if(!BridgePolicy.sameAccount(db.bound(),account))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");return account;
    }
    String consent(CallerGate.Caller caller,String offered,boolean tokenRequired) {
        CallerGate.verify(context,caller.packageName,caller.uid);String account=db.bound();
        if(!BridgePolicy.account(account)||!db.consent(caller,account))throw new SecurityException("E_TELEGRAM_CONSENT_REQUIRED");
        if((tokenRequired||offered!=null)&&!BridgePolicy.sameAccount(account,offered))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");return account;
    }
    <T> T provider(CallerGate.Caller caller,String offered,boolean tokenRequired,CancellationSignal cancellation,Callable<T> work)throws Exception {
        String expected=consent(caller,offered,tokenRequired);
        return execute(cancellation,()->{
            consent(caller,expected,true);
            if(!BridgePolicy.sameAccount(expected,authorized()))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");
            T result=work.call();consent(caller,expected,true);return result;
        });
    }
    JSONObject chats(int folder,Object offset)throws Exception {
        String account=authorized();JSONObject request=new JSONObject().put("op","chats").put("folder",folder).put("limit",100);
        if(offset!=null&&offset!=JSONObject.NULL)request.put("offset",offset);
        JSONObject result=request(request);result.put("chats",db.remember(account,result.getJSONArray("chats")));return result;
    }
    JSONObject login(String op,String field,String value)throws Exception {
        CallerGate.foreground(context);
        JSONObject result=request(new JSONObject().put("op",op).put(field,value));
        if(result.optBoolean("authorized"))db.bind(result.getString("account"));return result;
    }
    JSONObject password(byte[] password)throws Exception {
        CallerGate.foreground(context);
        try {
            JSONObject result=new JSONObject(client().password(password));
            if(result.optBoolean("authorized"))db.bind(result.getString("account"));return result;
        }finally{Arrays.fill(password,(byte)0);}
    }
    ConnectorDb.Media media(String account,int channel,int id,String revision,boolean thumbnail)throws Exception {
        ConnectorDb.Media cached=db.media(account,channel,id,revision,thumbnail);
        if(cached!=null)return cached;
        // Loading a grid must not fetch the history of every uncached thumbnail.
        if(thumbnail)throw new IllegalStateException("E_TELEGRAM_NO_THUMBNAIL");
        JSONObject item=request(new JSONObject().put("op","locate").put("peer",db.peer(account,channel,true))
                .put("id",id).put("revision",revision));
        db.restoreMedia(account,channel,id,revision,item);
        return db.media(account,channel,id,revision,false);
    }
    byte[] read(ConnectorDb.Media media,long offset,int length,boolean thumbnail)throws Exception {
        byte[] bytes=client().read(new JSONObject().put("locator",media.locator).put("offset",offset).put("length",length).toString());
        if(bytes==null||bytes.length==0||bytes.length>BridgePolicy.MAX_RANGE||bytes.length!=length) {
            if(bytes!=null)Arrays.fill(bytes,(byte)0);throw new java.io.IOException("E_TRANSFER_INCOMPLETE");
        }return bytes;
    }
    void grant(CallerGate.Caller caller,String expected,String selection)throws Exception {
        if(!BridgePolicy.sameAccount(expected,authorized()))throw new SecurityException("E_SOURCE_ACCOUNT_CHANGED");
        CallerGate.verify(context,caller.packageName,caller.uid);
        synchronized(db) {if(!selection.equals(db.selectionToken(expected)))throw new SecurityException("E_TELEGRAM_SELECTION_CHANGED");db.grant(caller,expected);}
    }
    <T> T execute(CancellationSignal cancellation,Callable<T> work)throws Exception {
        if(cancellation!=null)cancellation.throwIfCanceled();
        Future<T> future=engine.submit(work);
        if(cancellation!=null)cancellation.setOnCancelListener(()->future.cancel(true));
        try {return future.get(50,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){future.cancel(true);throw new IllegalStateException("E_TELEGRAM_TIMEOUT");}
        catch(CancellationException cancelled){throw new IllegalStateException("E_TELEGRAM_CANCELLED");}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("E_TELEGRAM_CANCELLED");}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("E_TELEGRAM_ENGINE_UNAVAILABLE");}
        finally{if(cancellation!=null)cancellation.setOnCancelListener(null);}
    }
}
