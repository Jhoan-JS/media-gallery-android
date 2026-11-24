package local.ttlab.gallery;
import android.content.Intent;import android.content.pm.PackageManager;import android.os.Bundle;import android.widget.TextView;
/** Android delivers this one-use result only to the exact, same-signer connector that requested it. */
public final class ConnectorAuthActivity extends SecureActivity {
 private String challenge;private boolean valid,returned;
 @Override public void onCreate(Bundle saved){super.onCreate(null);setResult(RESULT_CANCELED);String caller=getCallingPackage();challenge=getIntent().getStringExtra("challenge");
  valid=state!=null&&caller!=null&&(BuildConfig.TELEGRAM_PACKAGE.equals(caller)||"org.im.ttalk.lab".equals(caller))&&challenge!=null&&challenge.matches("[0-9a-f]{64}")&&getPackageManager().checkSignatures(getPackageName(),caller)==PackageManager.SIGNATURE_MATCH;
  if(!valid){finish();return;}TextView message=text("Desbloquea la galería para administrar el conector. Tu sesión se conserva.",18,getColor(R.color.gallery_foreground));message.setPadding(dp(24),dp(48),dp(24),dp(24));content(message);
 }
 @Override protected void onResume(){super.onResume();if(valid&&state.unlocked)onUnlocked();}
 @Override void onUnlocked(){if(!valid||returned||!biometricCurrent(unlockEpoch()))return;returned=true;setResult(RESULT_OK,new Intent().putExtra("challenge",challenge));finish();}
}
