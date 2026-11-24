package local.ttlab.telegram;

import android.os.Bundle;

public final class AuthorizeActivity extends SecureActivity {
    public static final String ACTION="local.ttlab.telegram.AUTHORIZE";
    private ConnectorRuntime runtime;
    private CallerGate.Caller caller;
    private String selection;
    @Override public void onCreate(Bundle ignored) {
        super.onCreate(null);setResult(RESULT_CANCELED);
    }
    @Override void authorizedScreen(){
        screen("Conectar con la galería","Comprueba la cuenta y los chats antes de conceder acceso en este usuario Android.");
        task(()->{
            if(!ACTION.equals(getIntent().getAction()))throw new SecurityException("E_GALLERY_CALLER");
            caller=CallerGate.activity(this,getCallingPackage());runtime=ConnectorRuntime.get(this);
            return runtime.execute(null,()->{runtime.authorized();return runtime.status();});
        },this::offer);
    }
    private void offer(org.json.JSONObject details) {
        String account=details.optString("account");if(!BridgePolicy.account(account)){finish();return;}
        screen("Autorizar Telegram","La galería podrá consultar metadatos, miniaturas y rangos de los chats seleccionados. No recibe tu sesión ni tus credenciales de Telegram.");
        add(text(accountLabel(details),18,TEXT),0,8);
        add(text("Cuenta vinculada · "+account.substring(0,12),18,TEXT),0,12);
        add(text("Galería · "+caller.packageName+"\nUsuario Android · "+android.os.Process.myUid()/100000,14,MUTED),0,20);
        int count=0;
        synchronized(runtime.db) {
            try(android.database.Cursor channels=runtime.db.channels(account)) {while(channels.moveToNext()){count++;add(text(channels.getString(1),16,TEXT),4,8);}}
            try{selection=runtime.db.selectionToken(account);}catch(Exception failed){feedback.setText(message(BridgePolicy.error(failed)));return;}
        }
        if(count==0){feedback.setTextColor(ERROR);feedback.setText("Primero elige al menos un chat en Conector TG.");}
        else button("Autorizar estos chats",()->task(()->runtime.execute(null,()->{runtime.grant(caller,account,selection);return true;}),granted->{setResult(RESULT_OK);finish();}),true);
        button("Cancelar",this::finish,false);
        button("Revocar acceso",()->{runtime.db.revoke();feedback.setTextColor(PRIMARY);feedback.setText("Acceso revocado.");},false);
        add(text("La autorización se conserva al cerrar las apps. Puedes revocarla en Conector TG. Cambiar de cuenta nunca reemplaza la cuenta de esta biblioteca.",14,MUTED),24,0);
    }
}
