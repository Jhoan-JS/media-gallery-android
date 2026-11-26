package local.ttlab.gallery;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import android.text.InputType;

abstract class SecureActivity extends Activity {
    State state;private FrameLayout frame;private View curtain;private AlertDialog pinDialog,privateDialog;private volatile boolean resumed;private volatile long pinEpoch;
    private android.os.CancellationSignal biometricSignal;private boolean biometricOffered;private final java.util.List<android.app.Dialog> ownedDialogs=new java.util.ArrayList<>();
    private android.window.OnBackInvokedCallback workspaceBackCallback;
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().setHideOverlayWindows(true);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        try{String key=b!=null?b.getString("source",UiSource.key(this)):getIntent().getStringExtra("source");state=State.resolve(this,key==null?UiSource.key(this):key,getIntent().getStringExtra("web-live"));getIntent().putExtra("source",state.key);}catch(Exception error){if(getIntent().hasExtra("web-live")){finish();return;}TextView message=new TextView(this);message.setText("No se pudo abrir el almacén cifrado. Libera espacio y reinicia la aplicación. No borres sus datos si tienes descargas que quieras conservar.");super.setContentView(message);}
    }
    void content(View view){frame=new FrameLayout(this);frame.addView(view);super.setContentView(frame);}
    @Override protected void onResume(){super.onResume();resumed=true;if(state==null||frame==null)return;
        updateWorkspaceBack();
        if(!Profiles.active(this)){state.unlocked=false;cover();new SecureDialogs.Builder(this).setTitle("Perfil no autorizado").setMessage("Abre la galería en el usuario Android que está activo.").setCancelable(false).setPositiveButton("Cerrar",(d,w)->finish()).show();return;}
        try{if(this instanceof RecoveryActivity&&state.securityVault.secret("pin")==null&&state.library.count(0,"",0,false)==0)return;}catch(Exception unavailable){cover();return;}
        if(!state.unlocked)lock();else uncover();
    }
    @Override protected void onPause(){resumed=false;pinEpoch++;cancelBiometric();if(frame!=null)cover();for(android.app.Dialog dialog:new java.util.ArrayList<>(ownedDialogs))if(dialog.isShowing())dialog.dismiss();ownedDialogs.clear();privateDialog=null;pinDialog=null;if(state!=null)state.paused=SystemClock.elapsedRealtime();super.onPause();}
    void onCovered(){}
    private void cover(){onCovered();if(curtain==null){frame.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);curtain=new View(this);curtain.setBackgroundColor(0xff101717);curtain.setClickable(true);frame.addView(curtain,new FrameLayout.LayoutParams(-1,-1));}}
    private void uncover(){if(curtain!=null){frame.removeView(curtain);curtain=null;}if(frame!=null&&frame.getChildCount()>0)frame.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);}
    void lock(){
        if(state==null||frame==null)return;
        if(state.unlocked){pinEpoch++;biometricOffered=false;}state.unlocked=false;cover();if(pinDialog!=null&&pinDialog.isShowing())return;for(android.app.Dialog dialog:new java.util.ArrayList<>(ownedDialogs))if(dialog.isShowing())dialog.dismiss();ownedDialogs.clear();
        try{
            final byte[] stored=state.securityVault.secret("pin");boolean create=stored==null;
            LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(8),dp(24),0);
            EditText pin=new EditText(this);pin.setHint(create?"Nuevo PIN (6 o más dígitos)":"PIN");pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);pin.setSaveEnabled(false);pin.setFilterTouchesWhenObscured(true);pin.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);pin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);fields.addView(pin);
            EditText confirm=new EditText(this);if(create){confirm.setHint("Repite el PIN");confirm.setInputType(pin.getInputType());confirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);fields.addView(confirm);}
            TextView error=new TextView(this);error.setTextColor(0xffffb4ab);fields.addView(error);
            AlertDialog.Builder pinBuilder=new SecureDialogs.Builder(this).setTitle(create?"Protege tu biblioteca":"Biblioteca bloqueada").setMessage(create?"Crea un PIN o restaura tu respaldo cifrado para conservar el anterior.":"Los archivos permanecen cifrados en este dispositivo.").setView(fields).setCancelable(false).setNegativeButton("Salir",(d,w)->finish()).setPositiveButton(create?"Crear PIN":"Desbloquear",null);
            final boolean biometric=!create&&BiometricUnlock.enabled(state);
            if(create)pinBuilder.setNeutralButton("Restaurar respaldo",(d,w)->startActivity(sourceIntent(RecoveryActivity.class)));
            else if(biometric)pinBuilder.setNeutralButton("Usar huella",null);
            final AlertDialog dialog=pinBuilder.create();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);final long dialogEpoch=pinEpoch;pinDialog=dialog;
            dialog.setOnShowListener(d->{
                if(!currentPinDialog(dialog,dialogEpoch))return;
                final Button submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(submit==null)return;
                if(biometric){Button fingerprint=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);if(fingerprint!=null)fingerprint.setOnClickListener(v->{if(currentPinDialog(dialog,dialogEpoch))tryBiometric(error);});}
                submit.setOnClickListener(v->{
                if(!currentPinDialog(dialog,dialogEpoch))return;
                if(state.maintenance){error.setText("La restauración está lista. Cierra y vuelve a abrir la aplicación.");return;}
                String value=pin.getText().toString();if(!value.matches("[0-9]{6,32}")||(create&&!value.equals(confirm.getText().toString()))){error.setText(create?"Usa al menos 6 dígitos e introduce el mismo PIN.":"Introduce tu PIN de al menos 6 dígitos.");return;}final char[] entered=value.toCharArray();pin.setText("");
                submit.setEnabled(false);final long attempt=dialogEpoch;
                state.database.execute(()->{
                    try{if(state.maintenance)throw new IllegalStateException("Restauración pendiente");boolean valid=create||PinAttempts.verify(state.securityVault,entered,stored);if(create)state.securityVault.secret("pin",PinHash.create(entered));
                        runOnUiThread(()->{if(currentPinDialog(dialog,attempt))finishPinAttempt(attempt,valid,pin,error);});
                    }catch(Exception failure){runOnUiThread(()->{if(!currentPinDialog(dialog,attempt))return;error.setText(failure instanceof SecurityException?failure.getMessage():"No se pudo desbloquear. Reintenta.");submit.setEnabled(true);});}finally{java.util.Arrays.fill(entered,'\0');}
                });
            });});dialog.show();
            if(biometric&&!biometricOffered&&BiometricUnlock.availability(this)==android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS)tryBiometric(error);
        }catch(Exception failure){new SecureDialogs.Builder(this).setMessage("No se pudo acceder a la clave de bloqueo.").setPositiveButton("Cerrar",(d,w)->finish()).show();}
    }
    void finishPinAttempt(long attempt,boolean valid,EditText pin,TextView error){
        if(isFinishing()||isDestroyed())return;
        final AlertDialog dialog=pinDialog;if(!currentPinDialog(dialog,attempt))return;
        if(valid)finishUnlock();
        else{error.setText("PIN incorrecto. Vuelve a intentarlo.");pin.setText("");final Button submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);new android.os.Handler(getMainLooper()).postDelayed(()->{if(currentPinDialog(dialog,attempt)&&submit!=null)submit.setEnabled(true);},1500);}
    }
    private boolean currentPinDialog(AlertDialog dialog,long epoch){return dialog!=null&&pinDialog==dialog&&dialog.isShowing()&&biometricCurrent(epoch);}
    private void tryBiometric(TextView error){
        if(!biometricCurrent(pinEpoch)||state.unlocked)return;biometricOffered=true;final long attempt=pinEpoch;
        BiometricUnlock.unlock(this,attempt,()->{if(biometricCurrent(attempt)&&!state.unlocked)finishUnlock();},message->{if(biometricCurrent(attempt)&&pinDialog!=null&&pinDialog.isShowing())error.setText(message);});
    }
    private void finishUnlock(){
        pinEpoch++;cancelBiometric();state.unlocked=true;state.paused=0;if(pinDialog!=null)pinDialog.dismiss();pinDialog=null;uncover();onUnlocked();
    }
    long unlockEpoch(){return pinEpoch;}
    boolean biometricCurrent(long epoch){
        try{return state!=null&&UnlockGate.accept(epoch,pinEpoch,resumed,Profiles.active(this),isFinishing()||isDestroyed(),state.maintenance);}catch(Exception unavailable){return false;}
    }
    void trackBiometric(android.os.CancellationSignal signal){cancelBiometric();biometricSignal=signal;}
    void trackDialog(android.app.Dialog dialog){dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setHideOverlayWindows(true);ownedDialogs.removeIf(old->!old.isShowing());if(!ownedDialogs.contains(dialog))ownedDialogs.add(dialog);}
    void trackPrivateDialog(AlertDialog dialog){trackDialog(dialog);if(privateDialog!=null)privateDialog.dismiss();privateDialog=dialog;}
    private void cancelBiometric(){if(biometricSignal!=null){biometricSignal.cancel();biometricSignal=null;}}
    void onUnlocked(){}
    android.content.Intent sourceIntent(Class<?> target){return new android.content.Intent(this,target).putExtra("source",state.key);}
    @Override protected void onSaveInstanceState(Bundle out){if(state!=null)out.putString("source",state.key);super.onSaveInstanceState(out);}
    boolean hasWorkspaceBack(){return false;}
    boolean handleWorkspaceBack(){return false;}
    void updateWorkspaceBack(){
        if(android.os.Build.VERSION.SDK_INT<33)return;
        if(hasWorkspaceBack()&&workspaceBackCallback==null){
            workspaceBackCallback=()->{if(!handleWorkspaceBack())finish();};
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,workspaceBackCallback);
        }else if(!hasWorkspaceBack())removeWorkspaceBack();
    }
    private void removeWorkspaceBack(){
        if(android.os.Build.VERSION.SDK_INT>=33&&workspaceBackCallback!=null){
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(workspaceBackCallback);
            workspaceBackCallback=null;
        }
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){
        if(!hasWorkspaceBack()||!handleWorkspaceBack())super.onBackPressed();
    }
    @Override protected void onDestroy(){removeWorkspaceBack();pinEpoch++;cancelBiometric();if(pinDialog!=null)pinDialog.dismiss();if(privateDialog!=null)privateDialog.dismiss();super.onDestroy();}
    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
}
