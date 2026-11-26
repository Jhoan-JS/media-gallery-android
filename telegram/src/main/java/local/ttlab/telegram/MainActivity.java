package local.ttlab.telegram;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends SecureActivity {
    private ConnectorRuntime runtime;
    private final Map<Integer,TextView> choices=new LinkedHashMap<>();
    private Object next;
    private int folder;
    private boolean incomplete;
    private Button retryChats;
    private LinearLayout chatRows;
    @Override public void onCreate(Bundle ignored) {
        super.onCreate(null);
    }
    @Override void authorizedScreen(){boot();}
    private void boot() {
        screen("Conector TG","Sesión independiente de Telegram, guardada cifrada en este dispositivo.");
        button("Volver a intentar",this::boot,false);
        button("Revisar credenciales",()->{if(runtime!=null&&runtime.db.bound()==null)setup();else refresh();},false);
        task(()->{CallerGate.foreground(this);runtime=ConnectorRuntime.get(this);return runtime.configured();},configured->{if(configured)refresh();else setup();});
    }
    private void setup() {
        screen("Conecta tu Telegram","Introduce tus credenciales de la API de Telegram. Permanecen cifradas en este conector; no se envían a la galería.");
        EditText apiId=input("API ID",InputType.TYPE_CLASS_NUMBER),apiHash=input("API hash",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Guardar credenciales",()->{
            String id=apiId.getText().toString().trim(),hash=apiHash.getText().toString().trim();apiId.getText().clear();apiHash.getText().clear();hideKeyboard();
            task(()->{long number;try{number=Long.parseLong(id);}catch(NumberFormatException invalid){throw new IllegalArgumentException("E_TELEGRAM_CREDENTIALS");}runtime.configure(number,hash);return true;},saved->refresh());
        },true);
    }
    private void refresh() {task(()->runtime.execute(null,()->runtime.status()),this::state);}
    private void state(JSONObject state) {
        if(state.optBoolean("authorized")){connected(state);return;}
        String stage=state.optString("state","phone").toLowerCase(java.util.Locale.ROOT);
        if(stage.contains("password")){password();return;}
        if(stage.contains("code")){code();return;}
        phone();
    }
    private void phone() {
        screen("Inicia sesión","Usa tu número de Telegram con prefijo internacional. Telegram enviará el código por sus canales habituales.");
        EditText phone=input("Teléfono",InputType.TYPE_CLASS_PHONE);
        button("Solicitar código",()->{
            String value=phone.getText().toString().replace(" ","");phone.getText().clear();hideKeyboard();
            task(()->{if(!value.matches("\\+[0-9]{7,15}"))throw new IllegalArgumentException("E_TELEGRAM_BAD_REQUEST");return runtime.execute(null,()->runtime.login("login_phone","phone",value));},result->{if(result.optBoolean("authorized"))state(result);else code();});
        },true);
        button("Comprobar sesión",this::refresh,false);
        if(runtime.db.bound()==null)button("Revisar credenciales",this::setup,false);
    }
    private void code() {
        screen("Código de Telegram","Introduce el código que te envió Telegram. Se usa una vez y no se guarda.");
        EditText code=input("Código",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Verificar código",()->{
            String value=code.getText().toString().trim();code.getText().clear();hideKeyboard();
            task(()->runtime.execute(null,()->runtime.login("login_code","code",value)),result->{String nextState=result.optString("state","");if(result.optBoolean("authorized"))state(result);else if(nextState.toLowerCase(java.util.Locale.ROOT).contains("password"))password();else refresh();});
        },true);
        button("Usar otro número",this::phone,false);
    }
    private void password() {
        screen("Verificación en dos pasos","Introduce tu contraseña de Telegram. Solo el motor local la usa para verificar la sesión.");
        EditText password=input("Contraseña",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Verificar contraseña",()->{
            char[] chars=new char[password.length()];password.getText().getChars(0,chars.length,chars,0);password.getText().clear();hideKeyboard();
            ByteBuffer encoded=StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);Arrays.fill(chars,'\0');if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);
            task(()->{try{return runtime.execute(null,()->runtime.password(bytes));}finally{Arrays.fill(bytes,(byte)0);}},this::state);
        },true);
    }
    private void connected(JSONObject state) {
        screen("Telegram conectado","La sesión se recupera después de cerrar la app. Solo los chats que selecciones podrán aparecer en la galería.");
        add(text(accountLabel(state),18,TEXT),0,8);
        String account=state.optString("account","");if(BridgePolicy.account(account))add(text("Cuenta vinculada · "+account.substring(0,12),14,MUTED),0,16);
        try(android.database.Cursor selected=runtime.db.channels(account)) {add(text(selected.getCount()+" chats seleccionados",18,TEXT),0,16);}
        button("Elegir chats",this::selector,true);
        button("Revocar acceso de la galería",()->trackDialog(new AlertDialog.Builder(this).setTitle("¿Revocar el acceso?").setMessage("La galería dejará de leer Telegram hasta que vuelvas a autorizarla. Tu sesión del conector se conserva.").setNegativeButton("Cancelar",null).setPositiveButton("Revocar",(d,w)->{if(!canAdmin())return;runtime.db.revoke();feedback.setTextColor(PRIMARY);feedback.setText("Acceso revocado. Vuelve a autorizar desde la galería cuando lo necesites.");}).show()),false);
        button("Actualizar estado",this::refresh,false);
        add(text("Conexión inicial: abre Telegram en Tato Galería y confirma allí la autorización. Las aperturas intencionadas informan lectura y vistas a Telegram. El contenido protegido, efímero o secreto no se exporta.",14,MUTED),24,12);
    }
    private void selector() {
        choices.clear();folder=0;next=null;incomplete=false;
        screen("Chats de esta biblioteca","Elige explícitamente canales, grupos, chats privados o Mensajes guardados. Guardar la selección requiere autorizar otra vez desde la galería.");
        retryChats=button("Reintentar carga",this::more,false);
        chatRows=new LinearLayout(this);chatRows.setOrientation(LinearLayout.VERTICAL);add(chatRows,0,16);
        try(android.database.Cursor selected=runtime.db.channels(runtime.db.bound())) {
            while(selected.moveToNext())chatRow(selected.getInt(0),selected.getString(1)+"\nSeleccionado",true,false);
        }
        button("Guardar selección",()->{
            ArrayList<Integer> selected=new ArrayList<>();
            for(Map.Entry<Integer,TextView> row:choices.entrySet()) {
                if(row.getValue() instanceof CheckBox && ((CheckBox)row.getValue()).isChecked())selected.add(row.getKey());
            }
            task(()->runtime.execute(null,()->{String account=runtime.authorized();runtime.db.select(account,selected);return runtime.status();}),this::connected);
        },true);
        button("Volver sin guardar",this::refresh,false);more();
    }
    private void more() {
        int requestedFolder=folder;Object requestedOffset=next;
        task(()->runtime.execute(null,()->runtime.chats(requestedFolder,requestedOffset)),result->{
            JSONArray chats=result.optJSONArray("chats");
            for(int i=0;chats!=null&&i<chats.length();i++) {
                JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;
                int id=chat.optInt("id");
                boolean selected=chat.optBoolean("selected"),unavailable=chat.optBoolean("unavailable");
                String label=chat.optString("title")+"\n"+kind(chat)+(unavailable?" · Restringido para la galería":"");
                TextView existing=choices.get(id);
                if(existing==null)chatRow(id,label,selected,unavailable);
                else if(selected)existing.setText(label);
            }
            incomplete|=result.optBoolean("incomplete");
            next=result.opt("next");if(next==null||next==JSONObject.NULL){next=null;folder++;}
            if(folder<=1){
                more();
                return;
            }
            retryChats.setVisibility(View.GONE);
            feedback.setTextColor(incomplete?ERROR:MUTED);
            feedback.setText(incomplete?"Se cargaron "+choices.size()+" chats. Telegram no entregó la lista completa. Vuelve a abrir Elegir chats para reintentar."
                    :choices.size()+" chats cargados, incluidos los archivados.");
        });
    }
    private void chatRow(int id,String label,boolean checked,boolean unavailable) {
        TextView row;
        if(unavailable&&!checked){
            row=text(label,16,MUTED);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        }else{
            CheckBox checkbox=new CheckBox(this);
            checkbox.setText(label);checkbox.setTextColor(TEXT);checkbox.setTextSize(16);checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(PRIMARY));checkbox.setChecked(checked);checkbox.setSaveEnabled(false);checkbox.setFilterTouchesWhenObscured(true);
            row=checkbox;
        }
        row.setMinHeight(dp(64));
        choices.put(id,row);chatRows.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private static String kind(JSONObject chat){
        String kind=chat.optString("kind");
        if("group".equals(kind)||chat.optBoolean("supergroup"))return "Grupo";
        return "channel".equals(kind)?"Canal":"Chat privado / Mensajes guardados";
    }
}
