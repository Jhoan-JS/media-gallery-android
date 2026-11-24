package local.ttlab.gallery;

import android.app.AlertDialog;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.widget.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A per-use, strong-biometric key authorizes a marker, never the database or PIN key. */
final class BiometricUnlock {
    interface Observer {void stage(String code);default void failure(Throwable error){}}
    static volatile Observer observer;
    private static void trace(String code){Observer current=observer;if(current!=null)try{current.stage(code);}catch(Exception ignored){}}
    private static void traceFailure(Throwable error){
        Observer current=observer;if(current==null)return;
        try{current.failure(error);for(Throwable cause=error;cause!=null;cause=cause.getCause())if(android.os.Build.VERSION.SDK_INT>=33&&cause instanceof android.security.KeyStoreException){android.security.KeyStoreException key=(android.security.KeyStoreException)cause;current.stage("keystore_code_"+key.getNumericErrorCode()+"_auth_"+key.requiresUserAuthentication()+"_system_"+key.isSystemError()+"_transient_"+key.isTransientFailure());}}catch(Exception ignored){}
    }
    private static final String ALIAS="tato-gallery-biometric-v1",SECRET="biometric";
    private static final int STRONG=BiometricManager.Authenticators.BIOMETRIC_STRONG;
    private static final byte[] AAD="TatoGallery/biometric/v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static boolean enabled(State state){try{byte[] record=state.securityVault.secret(SECRET);return record!=null&&record.length==60;}catch(Exception unavailable){return false;}}
    static int availability(SecureActivity activity){BiometricManager manager=activity.getSystemService(BiometricManager.class);return manager==null?BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:manager.canAuthenticate(STRONG);}
    static void showSettings(SecureActivity activity){
        if(!activity.biometricCurrent(activity.unlockEpoch())||!activity.state.unlocked)return;
        boolean active=enabled(activity.state);int available=availability(activity);
        AlertDialog.Builder dialog=new SecureDialogs.Builder(activity).setTitle("Desbloqueo con huella")
            .setMessage(active?"Activado en este usuario Android. El PIN sigue disponible; si cambia la biometría registrada, tendrás que usarlo y activar la huella de nuevo."
                :available==BiometricManager.BIOMETRIC_SUCCESS?"Activa la huella o biometría fuerte de Android. Primero confirma el PIN de la galería. Tu PIN y tus datos cifrados se conservan."
                :"Configura una huella o biometría fuerte en los ajustes de este usuario Android para usar esta opción. Mientras tanto, puedes seguir entrando con el PIN.")
            .setNegativeButton("Cerrar",null);
        if(active)dialog.setPositiveButton("Desactivar",(d,w)->activity.state.database.execute(()->{
            try{disable(activity.state);activity.runOnUiThread(()->message(activity,"Desbloqueo con huella desactivado"));}
            catch(Exception error){activity.runOnUiThread(()->message(activity,"No se pudo desactivar. Reintenta."));}
        }));
        else if(available==BiometricManager.BIOMETRIC_SUCCESS)dialog.setPositiveButton("Activar huella",(d,w)->confirmPin(activity));
        activity.trackPrivateDialog(dialog.show());
    }
    private static void confirmPin(SecureActivity activity){
        final long epoch=activity.unlockEpoch();
        if(!activity.biometricCurrent(epoch)||!activity.state.unlocked)return;
        LinearLayout fields=new LinearLayout(activity);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(activity.dp(24),activity.dp(8),activity.dp(24),0);
        EditText pin=new EditText(activity);pin.setHint("PIN de la galería");pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});pin.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);fields.addView(pin);
        TextView error=activity.text("",14,activity.getColor(R.color.gallery_error));fields.addView(error);
        AlertDialog dialog=new SecureDialogs.Builder(activity).setTitle("Confirma tu PIN").setMessage("La huella se activará solo después de verificar tu PIN y tu biometría.").setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Continuar",null).create();
        dialog.setOnShowListener(d->{
            if(!activity.biometricCurrent(epoch)||!activity.state.unlocked||!dialog.isShowing())return;
            final Button submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(submit==null)return;
            submit.setOnClickListener(v->{
            if(!activity.biometricCurrent(epoch)||!activity.state.unlocked||!dialog.isShowing())return;
            char[] value=pin.getText().toString().toCharArray();pin.setText("");
            if(value.length<6||value.length>32||!new String(value).matches("[0-9]+")){Arrays.fill(value,'\0');error.setText("Introduce tu PIN de al menos 6 dígitos.");return;}
            submit.setEnabled(false);
            activity.state.database.execute(()->{
                boolean valid=false;
                String failureMessage="PIN incorrecto. Vuelve a intentarlo.";
                try{byte[] verifier=activity.state.securityVault.secret("pin");valid=PinAttempts.verify(activity.state.securityVault,value,verifier);}catch(SecurityException waiting){failureMessage=waiting.getMessage();}catch(Exception ignored){}
                finally{Arrays.fill(value,'\0');}
                final boolean accepted=valid;final String rejection=failureMessage;
                activity.runOnUiThread(()->{
                    if(!activity.biometricCurrent(epoch)||!activity.state.unlocked||!dialog.isShowing()){dialog.dismiss();return;}
                    if(!accepted){error.setText(rejection);pin.postDelayed(()->{if(activity.biometricCurrent(epoch)&&activity.state.unlocked&&dialog.isShowing())submit.setEnabled(true);},1500);return;}
                    dialog.dismiss();enroll(activity,epoch);
                });
            });
        });});dialog.setOnDismissListener(d->pin.setText(""));dialog.show();
        activity.trackPrivateDialog(dialog);
    }
    private static void enroll(SecureActivity activity,long epoch){
        activity.state.database.execute(()->{
            try{
                if(!activity.biometricCurrent(epoch)||!activity.state.unlocked)return;
                disable(activity.state);SecretKey key=createKey();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
                activity.runOnUiThread(()->prompt(activity,epoch,cipher,"Activar desbloqueo con huella","Cancelar",authorized->{
                    trace("enroll_authorized");
                    bind(authorized,activity.state);
                    byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
                    byte[] encrypted;try{encrypted=authorized.doFinal(challenge);trace("enroll_encrypted");}finally{Arrays.fill(challenge,(byte)0);}
                    byte[] nonce=authorized.getIV();if(nonce.length!=12||encrypted.length!=48)throw new java.security.GeneralSecurityException("Invalid biometric record");
                    byte[] record=Arrays.copyOf(nonce,60);System.arraycopy(encrypted,0,record,12,encrypted.length);
                    activity.state.database.execute(()->{
                        try{if(activity.biometricCurrent(epoch)&&activity.state.unlocked){activity.state.securityVault.secret(SECRET,record);trace("enroll_stored");activity.runOnUiThread(()->message(activity,"Desbloqueo con huella activado"));}else trace("enroll_store_rejected_gate");}
                        catch(Exception error){trace("enroll_store_error_"+error.getClass().getSimpleName());activity.runOnUiThread(()->message(activity,"No se pudo guardar la huella. Se conserva el PIN."));}
                    });
                },reason->message(activity,reason)));
            }catch(Exception error){trace("enroll_prepare_error_"+error.getClass().getSimpleName());activity.runOnUiThread(()->message(activity,"No se pudo activar la huella. Se conserva el PIN."));}
        });
    }
    static void unlock(SecureActivity activity,long epoch,Runnable success,Consumer<String> fallback){
        try{
            byte[] record=activity.state.securityVault.secret(SECRET);if(record==null||record.length!=60)throw new java.security.GeneralSecurityException("Missing biometric marker");
            KeyStore store=store();SecretKey key=(SecretKey)store.getKey(ALIAS,null);if(key==null)throw new java.security.GeneralSecurityException("Missing biometric key");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,record,0,12));
            prompt(activity,epoch,cipher,"Desbloquear biblioteca","Usar PIN",authorized->{
                byte[] plain;try{bind(authorized,activity.state);plain=authorized.doFinal(record,12,record.length-12);}catch(Exception invalid){activity.state.database.execute(()->{try{disable(activity.state);}catch(Exception ignored){}});throw invalid;}
                try{if(plain.length!=32)throw new java.security.GeneralSecurityException("Invalid biometric marker");if(activity.biometricCurrent(epoch))success.run();}
                finally{Arrays.fill(plain,(byte)0);}
            },fallback);
        }catch(Exception invalid){
            activity.state.database.execute(()->{try{disable(activity.state);}catch(Exception ignored){}});
            fallback.accept("La huella necesita activarse de nuevo. Introduce tu PIN.");
        }
    }
    private interface Authorized {void run(Cipher cipher)throws Exception;}
    private static void prompt(SecureActivity activity,long epoch,Cipher cipher,String title,String cancel,Authorized authorized,Consumer<String> fallback){
        if(!activity.biometricCurrent(epoch)){trace("prompt_rejected_gate");return;}
        if(availability(activity)!=BiometricManager.BIOMETRIC_SUCCESS){trace("prompt_unavailable");fallback.accept("La huella no está disponible ahora. Puedes usar tu PIN.");return;}
        CancellationSignal signal=new CancellationSignal();activity.trackBiometric(signal);boolean[] completed={false};
        try{
            new BiometricPrompt.Builder(activity).setTitle(title).setSubtitle("Tato Galería")
                .setAllowedAuthenticators(STRONG).setConfirmationRequired(true)
                .setNegativeButton(cancel,activity.getMainExecutor(),(d,w)->{trace("prompt_negative");if(!completed[0]){completed[0]=true;fallback.accept("Usar PIN".equals(cancel)?"Introduce tu PIN.":"La huella no se ha activado.");}}).build()
                .authenticate(new BiometricPrompt.CryptoObject(cipher),signal,activity.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                        trace("auth_success_received");if(completed[0]){trace("auth_rejected_completed");return;}if(signal.isCanceled()){trace("auth_rejected_cancelled");return;}if(!activity.biometricCurrent(epoch)){trace("auth_rejected_gate");return;}completed[0]=true;
                        try{trace("auth_type_"+result.getAuthenticationType());if(result.getAuthenticationType()!=BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC||result.getCryptoObject()==null||result.getCryptoObject().getCipher()!=cipher)throw new java.security.GeneralSecurityException("Unexpected biometric result");authorized.run(cipher);trace("auth_operation_finished");}
                        catch(Exception invalid){trace("auth_operation_error_"+invalid.getClass().getSimpleName());Throwable cause=invalid.getCause();if(cause!=null)trace("auth_operation_cause_"+cause.getClass().getSimpleName());traceFailure(invalid);fallback.accept("No se pudo verificar la huella. Introduce tu PIN.");}
                    }
                    @Override public void onAuthenticationError(int code,CharSequence text){trace("auth_error_"+code);if(completed[0])return;completed[0]=true;if(!signal.isCanceled()&&activity.biometricCurrent(epoch))fallback.accept("La huella no está disponible. Puedes usar tu PIN.");}
                    @Override public void onAuthenticationFailed(){trace("auth_failed_match");if(!completed[0]&&activity.biometricCurrent(epoch))fallback.accept("Huella no reconocida. Reintenta o usa tu PIN.");}
                });
            trace("prompt_presented");
        }catch(Exception unavailable){trace("prompt_error_"+unavailable.getClass().getSimpleName());signal.cancel();fallback.accept("No se pudo abrir la huella. Puedes usar tu PIN.");}
    }
    private static void bind(Cipher cipher,State state)throws Exception{
        // Keystore caches a pre-authentication updateAAD failure until doFinal.
        bind(cipher,state.securityVault.secret("pin"));
    }
    static void bind(Cipher cipher,byte[] pin)throws Exception{
        if(pin==null||pin.length!=48)throw new java.security.GeneralSecurityException("PIN required");
        cipher.updateAAD(AAD);cipher.updateAAD(MessageDigest.getInstance("SHA-256").digest(pin));
    }
    private static KeyStore store()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;}
    static SecretKey createKey()throws Exception{
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG).setInvalidatedByBiometricEnrollment(true).build());
        return generator.generateKey();
    }
    static void disable(State state)throws Exception{state.securityVault.secret(SECRET,new byte[0]);KeyStore store=store();if(store.containsAlias(ALIAS))store.deleteEntry(ALIAS);}
    private static void message(SecureActivity activity,String message){if(activity.biometricCurrent(activity.unlockEpoch()))Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
}
