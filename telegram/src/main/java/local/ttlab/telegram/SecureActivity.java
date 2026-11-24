package local.ttlab.telegram;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

abstract class SecureActivity extends Activity {
    static final int SURFACE=0xff141715,TEXT=0xffe8ece5,MUTED=0xffadb5aa,PRIMARY=0xffbdc9a4,ERROR=0xffeab4ac;
    LinearLayout body;
    TextView feedback;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean busy;private volatile boolean authorized,resumed;private volatile long epoch;private boolean waiting;private String challenge;private long pendingEpoch;private final java.util.List<android.app.Dialog> dialogs=new java.util.ArrayList<>();
    @Override public void onCreate(Bundle ignored) {
        super.onCreate(null);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().setHideOverlayWindows(true);
        if(android.os.Build.VERSION.SDK_INT>=33)setRecentsScreenshotEnabled(false);
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(authorized){authorizedScreen();return;}if(waiting)return;
        waiting=true;pendingEpoch=++epoch;byte[] nonce=new byte[32];new java.security.SecureRandom().nextBytes(nonce);StringBuilder token=new StringBuilder();for(byte b:nonce)token.append(String.format(java.util.Locale.ROOT,"%02x",b&255));challenge=token.toString();
        try{String gallery=BuildConfig.GALLERY_PACKAGE;if(getPackageManager().checkSignatures(getPackageName(),gallery)!=android.content.pm.PackageManager.SIGNATURE_MATCH)throw new SecurityException();
            startActivityForResult(new android.content.Intent().setComponent(new android.content.ComponentName(gallery,"local.ttlab.gallery.ConnectorAuthActivity")).putExtra("challenge",challenge),901);
        }catch(Exception missing){waiting=false;screen("Actualiza Tato Galería","Instala Tato Galería 2.8 o posterior en este usuario para desbloquear la administración. La sesión y las conexiones existentes se conservan.");}
    }
    @Override protected void onPause(){resumed=false;authorized=false;epoch++;busy=false;for(android.app.Dialog dialog:dialogs)if(dialog.isShowing())dialog.dismiss();dialogs.clear();android.view.View cover=new android.view.View(this);cover.setBackgroundColor(SURFACE);setContentView(cover);super.onPause();}
    void trackDialog(android.app.Dialog dialog){dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setHideOverlayWindows(true);dialogs.add(dialog);}
    boolean canAdmin(){return current(epoch);}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request!=901)return;waiting=false;
        if(result!=RESULT_OK||challenge==null||data==null||!challenge.equals(data.getStringExtra("challenge"))||epoch!=pendingEpoch+1){challenge=null;finish();return;}
        challenge=null;authorized=true;
    }
    void authorizedScreen(){}
    private boolean current(long expected){return authorized&&resumed&&epoch==expected&&!isFinishing()&&!isDestroyed();}
    @Override protected void onSaveInstanceState(Bundle out) { /* Sensitive inputs never enter Android saved state. */ }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    void screen(String title,String description) {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(SURFACE);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.addView(body);scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
        });setContentView(scroll);
        TextView heading=text(title,26,TEXT);heading.setTypeface(null,android.graphics.Typeface.BOLD);add(heading,0,12);
        add(text(description,16,MUTED),0,24);feedback=text("",14,ERROR);add(feedback,0,8);
    }
    TextView text(String text,int size,int color) {TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setTextIsSelectable(false);return view;}
    void add(View view,int before,int after) {LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(before);p.bottomMargin=dp(after);body.addView(view,p);}
    EditText input(String label,int type) {
        add(text(label,14,MUTED),12,4);EditText input=new EditText(this);input.setSingleLine(true);input.setMinHeight(dp(56));input.setTextSize(16);input.setTextColor(TEXT);input.setInputType(type|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setFilterTouchesWhenObscured(true);add(input,0,8);return input;
    }
    Button button(String label,Runnable action,boolean primary) {
        Button button=new Button(this);button.setText(label);button.setTextSize(14);button.setAllCaps(false);button.setMinHeight(dp(48));button.setTextColor(primary?0xff192012:PRIMARY);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?PRIMARY:0xff1d221e));button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(v->{if(authorized&&resumed&&!busy)action.run();});add(button,8,8);return button;
    }
    void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(body.getWindowToken(),0);}
    static String accountLabel(org.json.JSONObject account){
        String name=account.optString("display_name").replaceAll("[\\p{Cntrl}]"," ").trim(),username=account.optString("username");
        if(name.length()>80)name=name.substring(0,80);if(!username.matches("[A-Za-z0-9_]{3,32}"))username="";
        return (name.isEmpty()?"Cuenta de Telegram":name)+(username.isEmpty()?"":" · @"+username);
    }
    <T> void task(Callable<T> action,Consumer<T> success) {
        if(busy||!authorized||!resumed)return;final long expected=epoch;busy=true;feedback.setText("Conectando…");feedback.setTextColor(MUTED);disable(body,false);
        worker.submit(()->{
            try {if(!current(expected))return;T value=action.call();runOnUiThread(()->{if(!current(expected))return;busy=false;disable(body,true);feedback.setText("");success.accept(value);});}
            catch(Throwable failed){String code=BridgePolicy.error(failed);runOnUiThread(()->{if(!current(expected))return;busy=false;disable(body,true);feedback.setTextColor(ERROR);feedback.setText(message(code));});}
        });
    }
    private void disable(android.view.ViewGroup parent,boolean enabled){for(int i=0;i<parent.getChildCount();i++){View view=parent.getChildAt(i);view.setEnabled(enabled);if(view instanceof android.view.ViewGroup)disable((android.view.ViewGroup)view,enabled);}}
    static String message(String code) {
        if(code.startsWith("E_TELEGRAM_FLOOD_WAIT_"))return "Telegram pide esperar "+code.substring("E_TELEGRAM_FLOOD_WAIT_".length())+" segundos. Vuelve a intentar después; la autorización se conserva.";
        switch(code) {
            case "E_TELEGRAM_SETUP_REQUIRED":return "Introduce tu API ID y API hash para empezar.";
            case "E_TELEGRAM_ENGINE_UNAVAILABLE":return "El motor Telegram no está disponible en esta instalación. Instala el conector completo para tu dispositivo.";
            case "E_TELEGRAM_LOGIN_REQUIRED":return "Inicia sesión en Conector TG antes de autorizar la galería.";
            case "E_TELEGRAM_PASSWORD_REQUIRED":return "La cuenta requiere su contraseña de verificación en dos pasos.";
            case "E_TELEGRAM_CODE_INVALID":return "El código no es válido. Comprueba el último código enviado por Telegram.";
            case "E_TELEGRAM_PASSWORD_INVALID":return "La contraseña no es válida. Vuelve a introducirla.";
            case "E_TELEGRAM_CREDENTIALS":return "Comprueba el API ID y el API hash de 32 caracteres.";
            case "E_TELEGRAM_SELECTION_REQUIRED":return "Selecciona al menos un chat en Conector TG antes de autorizar.";
            case "E_TELEGRAM_SELECTION_CHANGED":return "La selección cambió. Cierra esta pantalla y revisa de nuevo los chats antes de autorizar.";
            case "E_TELEGRAM_CONSENT_REQUIRED":return "Autoriza manualmente la conexión desde la galería.";
            case "E_SOURCE_ACCOUNT_CHANGED":return "La sesión pertenece a otra cuenta. Esta biblioteca conserva su cuenta original; no se reemplazó.";
            case "E_TELEGRAM_ALREADY_CONFIGURED":return "La configuración y la cuenta ya están fijadas en esta instalación.";
            case "E_FOREGROUND_PROFILE":return "Abre el conector en el usuario Android activo.";
            case "E_GALLERY_CALLER":return "Abre la autorización desde la galería firmada de esta instalación.";
            case "E_TELEGRAM_TIMEOUT":return "Telegram tardó demasiado. Puedes volver a intentar sin perder la sesión.";
            case "E_TELEGRAM_CANCELLED":return "La operación se canceló. Puedes volver a intentar.";
            default:return "No se pudo completar la operación ("+code+"). Comprueba la conexión e inténtalo de nuevo.";
        }
    }
    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
