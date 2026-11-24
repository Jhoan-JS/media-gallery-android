package local.ttlab.gallery;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.Arrays;

/** Secure, device-local passphrase entry; SAF transfers ciphertext only. */
public final class RecoveryActivity extends SecureActivity {
    private static final int CREATE=61,OPEN=62;
    private EditText password,confirmation;
    private TextView status;
    private Button export,restore;
    private char[] pendingPassword;
    private Uri pendingUri;
    private int pendingMode;
    private volatile boolean busy,cancelled,resumed;
    private String lastProgress="";

    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.GalleryLibraryTheme);super.onCreate(saved);if(state==null)return;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(getColor(R.color.gallery_surface));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(8),dp(20),dp(8));
        Button back=GalleryUi.button(this,"Volver",false);back.setOnClickListener(v->{if(!busy)finish();});header.addView(back,new LinearLayout.LayoutParams(-2,-2));
        TextView title=GalleryUi.text(this,"Respaldo · "+SourceId.label(state.key),26,getColor(R.color.gallery_foreground));title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setAccessibilityHeading(true);LinearLayout.LayoutParams titleSize=new LinearLayout.LayoutParams(0,-2,1);titleSize.setMargins(dp(16),0,0,0);header.addView(title,titleSize);shell.addView(header);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(24),dp(12),dp(24),dp(32));
        layout.addView(copy("tato".equals(state.key)?"Tu catálogo, miniaturas y descargas guardadas de Tato, con el PIN original.":SourceId.local(state.key)?"Tus copias importadas, catálogo, miniaturas y marcas. El PIN común se conserva en esta instalación.":"Tu catálogo, miniaturas y descargas guardadas de Telegram. El PIN común se conserva en esta instalación y no se incluye en este respaldo.",16,false));
        TextView organization=copy(SourceId.local(state.key)?"Incluye las portadas elegidas de tus videos.":"Incluye tus portadas elegidas, carpetas de chats y chats fijados.",14,true);
        organization.setPadding(0,dp(8),0,0);layout.addView(organization);
        TextView cache=copy("La caché temporal de originales se regenera y no se incluye.",14,true);cache.setPadding(0,dp(8),0,0);layout.addView(cache);
        layout.addView(section("Frase del respaldo"));
        password=field("12 caracteres como mínimo");layout.addView(password,new LinearLayout.LayoutParams(-1,-2));
        TextView confirmLabel=copy("Confirmar frase",14,true);confirmLabel.setPadding(0,dp(16),0,dp(8));layout.addView(confirmLabel);
        confirmation=field("Repite la misma frase");layout.addView(confirmation,new LinearLayout.LayoutParams(-1,-2));
        TextView help=copy("Usa una frase larga y única. Guárdala fuera del teléfono: la necesitas para abrir el archivo. Tu PIN sigue siendo el mismo.",14,true);help.setPadding(0,dp(12),0,dp(24));layout.addView(help);
        export=GalleryUi.button(this,"Crear respaldo cifrado",true);export.setOnClickListener(v->choose(false));layout.addView(export,new LinearLayout.LayoutParams(-1,-2));
        layout.addView(section("Restaurar una biblioteca"));
        TextView restoreHelp=copy("tato".equals(state.key)?"Disponible en una instalación vacía, antes de crear un PIN. Se conserva el PIN del respaldo.":("Disponible cuando "+SourceId.label(state.key)+" está vacío y el PIN común está desbloqueado. Tras reinstalar, crea primero un PIN."),14,true);restoreHelp.setPadding(0,0,0,dp(16));layout.addView(restoreHelp);
        restore=GalleryUi.button(this,"Restaurar respaldo",false);restore.setOnClickListener(v->choose(true));layout.addView(restore,new LinearLayout.LayoutParams(-1,-2));
        status=copy("Elige Almacenamiento interno o Descargas. El archivo permanece cifrado.",14,true);status.setPadding(0,dp(24),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);layout.addView(status);
        FrameLayout canvas=new FrameLayout(this);FrameLayout.LayoutParams formSize=new FrameLayout.LayoutParams(getResources().getConfiguration().screenWidthDp>=600?dp(560):-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);canvas.addView(layout,formSize);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addView(canvas);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content(shell);buttons();
    }

    private TextView copy(String value,int size,boolean muted){TextView view=GalleryUi.text(this,value,size,getColor(muted?R.color.gallery_muted:R.color.gallery_foreground));view.setLineSpacing(dp(4),1);return view;}
    private TextView section(String value){TextView view=copy(value,18,false);view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));view.setPadding(0,dp(28),0,dp(12));view.setAccessibilityHeading(true);return view;}
    private EditText field(String hint){EditText field=new EditText(this);field.setHint(hint);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);field.setSaveEnabled(false);field.setTextSize(16);field.setTextColor(getColor(R.color.gallery_foreground));field.setHintTextColor(getColor(R.color.gallery_muted));field.setMinimumHeight(dp(56));field.setPadding(dp(16),dp(12),dp(16),dp(12));android.graphics.drawable.GradientDrawable normal=GalleryUi.background(getColor(R.color.gallery_surface_container),dp(12)),focused=GalleryUi.background(getColor(R.color.gallery_surface_container),dp(12));focused.setStroke(dp(2),getColor(R.color.gallery_primary));android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();background.addState(new int[]{android.R.attr.state_focused},focused);background.addState(new int[]{},normal);field.setBackground(background);return field;}

    private void choose(boolean importing) {
        if(busy||state.maintenance)return;
        if(importing&&!empty()){status.setText("tato".equals(state.key)?"La restauración de Tato solo está disponible antes de crear el PIN, en una biblioteca vacía. Tus datos actuales se conservan.":("Desbloquea el PIN común y deja vacía la fuente "+SourceId.label(state.key)+" para restaurar."));return;}
        if(!importing&&!state.unlocked){lock();return;}
        char[] phrase=chars(password),confirm=chars(confirmation);
        boolean valid=strong(phrase)&&Arrays.equals(phrase,confirm);Arrays.fill(confirm,'\0');
        if(!valid){Arrays.fill(phrase,'\0');status.setText("Repite la misma frase: al menos 12 caracteres, 6 distintos y dos tipos (letras, números, símbolos); o una frase de 20 caracteres o más.");return;}
        wipePending();pendingPassword=phrase;pendingMode=importing?OPEN:CREATE;password.setText("");confirmation.setText("");
        if(importing)new SecureDialogs.Builder(this).setTitle("tato".equals(state.key)?"Restaurar el PIN y la biblioteca originales":("Restaurar "+SourceId.label(state.key))).setMessage("Se verificará el respaldo de "+SourceId.label(state.key)+" antes de publicar sus datos. No se reemplazará una biblioteca existente. Al terminar deberás cerrar y volver a abrir la aplicación. "+("tato".equals(state.key)?"Introduce entonces el PIN original del respaldo.":"Se conserva tu PIN común.")).setNegativeButton("Cancelar",(d,w)->wipePending()).setPositiveButton("Elegir respaldo y restaurar",(d,w)->picker()).setOnCancelListener(d->wipePending()).show();
        else picker();
    }

    private void picker() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        Intent intent=new Intent(pendingMode==CREATE?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        if(pendingMode==CREATE)intent.putExtra(Intent.EXTRA_TITLE,"tato-galeria-"+state.key+"-respaldo.ttbackup");
        try{startActivityForResult(intent,pendingMode);}catch(Exception e){wipePending();status.setText("No hay un selector de archivos disponible.");}
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=CREATE&&request!=OPEN)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){wipePending();return;}
        if(!localDocument(data.getData())){wipePending();status.setText("Elige Almacenamiento interno o Descargas. No se admiten proveedores de nube ni otros destinos.");return;}
        if(pendingPassword==null||pendingMode!=request){status.setText("Introduce otra vez la frase para continuar.");return;}
        pendingUri=data.getData();continuePending();
    }

    @Override protected void onResume(){super.onResume();resumed=true;if(state!=null&&status!=null){buttons();if(!busy&&state.maintenance&&GalleryBackup.hasReady(this))restart();else continuePending();}}
    @Override void onUnlocked(){buttons();continuePending();}
    @Override protected void onPause(){resumed=false;if(busy){cancelled=true;state.unlocked=false;}super.onPause();}
    @Override protected void onDestroy(){if(!busy)wipePending();super.onDestroy();}

    private void continuePending() {
        if(!resumed||busy||pendingUri==null||pendingPassword==null)return;
        if(!state.unlocked&&!(pendingMode==OPEN&&empty()))return;
        if(!Profiles.current()||!getSystemService(UserManager.class).isUserForeground()){wipePending();finish();return;}
        final Uri uri=pendingUri;final int mode=pendingMode;final char[] phrase=pendingPassword;pendingUri=null;pendingPassword=null;
        busy=true;cancelled=false;buttons();status.setText("Procesando respaldo cifrado…");
        state.database.execute(()->{
            boolean ready=false;
            try {
                GalleryBackup.Progress progress=(message,count)->{if(cancelled||!resumed)throw new IOException("Operación cancelada al salir de la pantalla");lastProgress=message;if(count%64==0)runOnUiThread(()->{if(!isDestroyed())status.setText(message+" · "+count);});};
                if(mode==CREATE) {
                    try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new IOException("No se pudo abrir el destino");GalleryBackup.export(state,output,phrase,progress);output.flush();}
                } else {
                    try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("No se pudo abrir el respaldo");GalleryBackup.stage(this,state,input,phrase,progress);ready=true;}
                }
                final boolean restartReady=ready;
                runOnUiThread(()->{if(isDestroyed())return;busy=false;buttons();if(restartReady)restart();else status.setText("Respaldo cifrado terminado.\n"+lastProgress);});
            }catch(Exception failure){
                final boolean staged=ready;
                runOnUiThread(()->{if(isDestroyed())return;busy=false;buttons();if(staged)restart();else status.setText(cancelled?"Operación cancelada. No se publicó ninguna restauración. Descarta cualquier archivo de exportación incompleto.":"No se completó el respaldo. Comprueba la frase, el archivo, el espacio libre y que no haya descargas ni sincronización activas. Tus datos actuales se conservan.");});
            }finally{Arrays.fill(phrase,'\0');}
        });
    }

    private void restart() {
        String pin="tato".equals(state.key)?"Introduce el PIN original del respaldo, no crees otro.":"Tu PIN común actual se conserva.";
        status.setText("Respaldo de "+SourceId.label(state.key)+" verificado y listo. Cierra y vuelve a abrir Tato Galería para terminar. "+pin);
        new SecureDialogs.Builder(this).setTitle("Restauración lista").setMessage(lastProgress+"\nCierra la aplicación y vuelve a abrirla para terminar. "+pin).setCancelable(false).setPositiveButton("Cerrar para terminar",(d,w)->{finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());}).show();
    }

    private boolean empty(){try{if("tato".equals(state.key)?state.securityVault.secret("pin")!=null:!state.unlocked||state.securityVault.secret("pin")==null)return false;return GalleryBackup.empty(state.library);}catch(Exception e){return false;}}
    private void buttons(){if(export==null)return;boolean available=!busy&&!state.maintenance;export.setEnabled(available&&state.unlocked);restore.setEnabled(available&&empty());password.setEnabled(available);confirmation.setEnabled(available);}
    private static char[] chars(EditText field){char[] value=new char[field.length()];field.getText().getChars(0,value.length,value,0);return value;}
    private static boolean strong(char[] value){if(value.length<12||value.length>1024)return false;int kinds=0,distinct=0;boolean letter=false,digit=false,other=false;for(int i=0;i<value.length;i++){char c=value[i];boolean found=false;for(int j=0;j<i;j++)if(value[j]==c){found=true;break;}if(!found)distinct++;if(Character.isLetter(c))letter=true;else if(Character.isDigit(c))digit=true;else other=true;}if(letter)kinds++;if(digit)kinds++;if(other)kinds++;return distinct>=6&&(value.length>=20||kinds>=2);}
    private void wipePending(){if(pendingPassword!=null)Arrays.fill(pendingPassword,'\0');pendingPassword=null;pendingUri=null;pendingMode=0;}
    boolean localDocument(Uri uri){return uri!=null&&"content".equals(uri.getScheme())&&("com.android.externalstorage.documents".equals(uri.getAuthority())||"com.android.providers.downloads.documents".equals(uri.getAuthority()))&&DocumentsContract.isDocumentUri(this,uri);}
}
