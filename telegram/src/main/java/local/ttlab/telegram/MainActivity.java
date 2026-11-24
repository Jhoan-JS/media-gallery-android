package local.ttlab.telegram;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private final Map<Integer,CheckBox> choices=new LinkedHashMap<>();
    private Object next;
    private int folder;
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
        choices.clear();folder=0;next=null;
        screen("Chats de esta biblioteca","Elige explícitamente canales, grupos, chats privados o Mensajes guardados. Guardar la selección requiere autorizar otra vez desde la galería.");
        chatRows=new LinearLayout(this);chatRows.setOrientation(LinearLayout.VERTICAL);add(chatRows,0,16);
        try(android.database.Cursor selected=runtime.db.channels(runtime.db.bound())) {while(selected.moveToNext())chatRow(selected.getInt(0),selected.getString(1),"Seleccionado",true);}
        button("Cargar más chats",this::more,false);
        button("Guardar selección",()->{
            ArrayList<Integer> selected=new ArrayList<>();for(Map.Entry<Integer,CheckBox> row:choices.entrySet())if(row.getValue().isChecked())selected.add(row.getKey());
            task(()->runtime.execute(null,()->{String account=runtime.authorized();runtime.db.select(account,selected);return runtime.status();}),this::connected);
        },true);
        button("Volver sin guardar",this::refresh,false);more();
    }
    private void more() {
        if(folder>1){feedback.setTextColor(MUTED);feedback.setText("Ya se cargaron los chats y los archivados disponibles.");return;}
        int requestedFolder=folder;Object requestedOffset=next;
        task(()->runtime.execute(null,()->runtime.chats(requestedFolder,requestedOffset)),result->{
            JSONArray chats=result.optJSONArray("chats");if(chats!=null)for(int i=0;i<chats.length();i++) {
                JSONObject chat=chats.optJSONObject(i);if(chat==null)continue;int id=chat.optInt("id");if(choices.containsKey(id))continue;
                chatRow(id,chat.optString("title"),kind(chat.optString("kind")),chat.optBoolean("selected"));
            }
            next=result.opt("next");if(next==null||next==JSONObject.NULL){next=null;folder++;}
            feedback.setTextColor(MUTED);feedback.setText(choices.isEmpty()?"No hay chats seleccionables en esta página. Puedes cargar más.":choices.size()+" chats cargados; marca los que quieras incluir.");
        });
    }
    private void chatRow(int id,String title,String kind,boolean checked) {
        CheckBox row=new CheckBox(this);row.setText(title+"\n"+kind);row.setTextColor(TEXT);row.setTextSize(16);row.setMinHeight(dp(64));row.setButtonTintList(android.content.res.ColorStateList.valueOf(PRIMARY));row.setChecked(checked);row.setSaveEnabled(false);row.setFilterTouchesWhenObscured(true);
        choices.put(id,row);chatRows.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private static String kind(String kind){return "channel".equals(kind)?"Canal":"group".equals(kind)?"Grupo":"Chat privado / Mensajes guardados";}
}
