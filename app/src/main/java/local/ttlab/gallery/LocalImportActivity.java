package local.ttlab.gallery;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalImportActivity extends SecureActivity {
    private static final int PICK=217,MAX_SELECTION=100;
    private static final java.util.Set<String> DEVICE_DOCUMENTS=java.util.Set.of(
        "com.android.externalstorage.documents","com.android.providers.downloads.documents","com.android.providers.media.documents");
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private List<Uri> selected=Collections.emptyList();
    private Button choose,cancel;
    private TextView status;
    private ProgressBar progress;
    private CancellationSignal operation;

    @Override protected void onCreate(Bundle saved){
        setTheme(R.style.GalleryLibraryTheme);
        super.onCreate(saved);
        if(state==null||!SourceId.local(state.key)){finish();return;}
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.gallery_surface));
        page.setPadding(dp(20),dp(12),dp(20),dp(24));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        View back=GalleryUi.icon(this,R.drawable.ic_gallery_back,"Volver a Archivos propios");
        back.setOnClickListener(v->finish());header.addView(back);
        TextView title=text("Importar archivos",24,getColor(R.color.gallery_foreground));title.setAccessibilityHeading(true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        TextView description=text("Elige fotos y videos del teléfono. La galería guarda una copia cifrada para ver sin conexión. El original sigue en su ubicación.",16,getColor(R.color.gallery_muted));
        description.setPadding(0,dp(16),0,dp(18));page.addView(description);
        choose=GalleryUi.button(this,"Elegir fotos y videos",true);choose.setTag("pick-local-files");
        choose.setOnClickListener(v->pick());page.addView(choose,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_primary)));
        progress.setContentDescription("Importación en curso");progress.setVisibility(View.GONE);page.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        status=text("",15,getColor(R.color.gallery_foreground));status.setPadding(0,dp(16),0,dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(status);
        cancel=GalleryUi.button(this,"Cancelar importación",false);cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(v->stop());page.addView(cancel,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);content(scroll);
    }
    static Intent pickerIntent(){
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"})
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"))
            .putExtra(Intent.EXTRA_LOCAL_ONLY,true).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private void pick(){
        if(operation!=null||!canImport())return;
        status.setText("");
        try{startActivityForResult(pickerIntent(),PICK);}
        catch(android.content.ActivityNotFoundException unavailable){status.setText("El selector de archivos de Android no está disponible.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK||result!=RESULT_OK||data==null||status==null)return;
        LinkedHashSet<Uri> files=new LinkedHashSet<>();
        if(data.getData()!=null)files.add(data.getData());
        ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<clip.getItemCount()&&files.size()<=MAX_SELECTION;i++){
            Uri uri=clip.getItemAt(i).getUri();
            if(uri!=null)files.add(uri);
        }
        if(files.size()>MAX_SELECTION){status.setText("Elige hasta 100 archivos por importación.");return;}
        for(Uri uri:files){
            if(!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())||uri.getAuthority()==null
                ||!DEVICE_DOCUMENTS.contains(uri.getAuthority())||!DocumentsContract.isDocumentUri(this,uri)){
                status.setText("Elige los archivos desde Almacenamiento interno, Descargas o el proveedor multimedia del dispositivo.");
                return;
            }
        }
        selected=new ArrayList<>(files);
        if(selected.isEmpty()){status.setText("No se recibió ningún archivo que se pueda abrir.");return;}
        begin();
    }
    @Override protected void onResume(){
        super.onResume();
        if(status!=null)begin();
    }
    @Override void onUnlocked(){begin();}
    private boolean canImport(){
        return state!=null&&SourceId.local(state.key)&&State.unlocked&&biometricCurrent(unlockEpoch());
    }
    private void begin(){
        if(operation!=null||selected.isEmpty()||!canImport())return;
        List<Uri> files=selected;selected=Collections.emptyList();
        CancellationSignal signal=new CancellationSignal();operation=signal;
        choose.setEnabled(false);cancel.setEnabled(true);cancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);status.setText("Preparando la importación…");
        State origin=state;
        long epoch=unlockEpoch();
        writer.execute(()->runImport(origin,files,signal,epoch));
    }
    private void runImport(State origin,List<Uri> files,CancellationSignal signal,long epoch){
        int imported=0,duplicates=0,rejected=0;
        ContentResolver resolver=getContentResolver();
        for(int i=0;i<files.size()&&!signal.isCanceled();i++){
            Uri uri=files.get(i);
            String position="Importando "+(i+1)+" de "+files.size();
            try{
                updateProgress(signal,epoch,position+"…");
                String name="";long length=-1;
                try(Cursor metadata=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null,signal)){
                    if(metadata!=null&&metadata.moveToFirst()){
                        int nameColumn=metadata.getColumnIndex(OpenableColumns.DISPLAY_NAME),sizeColumn=metadata.getColumnIndex(OpenableColumns.SIZE);
                        if(nameColumn>=0)name=metadata.getString(nameColumn);
                        if(sizeColumn>=0&&!metadata.isNull(sizeColumn))length=metadata.getLong(sizeColumn);
                    }
                }
                String mime=resolver.getType(uri);
                if(mime!=null)mime=mime.toLowerCase(java.util.Locale.ROOT);
                ParcelFileDescriptor descriptor=resolver.openFileDescriptor(uri,"r",signal);
                if(descriptor==null)throw new IOException("Archivo no disponible.");
                try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
                    long expected=length<0?descriptor.getStatSize():length;
                    long[] updated={0};
                    Item copy=LocalFiles.add(origin,input,name,mime,expected,signal::isCanceled,bytes->{
                        long now=SystemClock.elapsedRealtime();
                        if(now-updated[0]<200)return;
                        updated[0]=now;
                        updateProgress(signal,epoch,position+" · "+DownloadProgress.bytes(bytes));
                    });
                    if(copy==null)duplicates++;else imported++;
                }
            }catch(Exception unavailable){if(!signal.isCanceled())rejected++;}
        }
        String summary=imported+(imported==1?" archivo importado":" archivos importados");
        if(duplicates>0)summary+=" · "+duplicates+(duplicates==1?" duplicado omitido":" duplicados omitidos");
        if(rejected>0)summary+=" · "+rejected+" sin importar";
        if(signal.isCanceled())summary="Importación interrumpida. "+summary;
        else if(rejected>0)summary+="\nRevisa el formato, el espacio disponible y el límite de 4 GiB por archivo.";
        String completed=summary;
        runOnUiThread(()->{
            if(operation!=signal||isFinishing()||isDestroyed())return;
            operation=null;choose.setEnabled(true);cancel.setVisibility(View.GONE);progress.setVisibility(View.GONE);
            status.setText(completed);
        });
    }
    private void updateProgress(CancellationSignal signal,long epoch,String message){
        runOnUiThread(()->{if(operation==signal&&!signal.isCanceled()&&biometricCurrent(epoch))status.setText(message);});
    }
    private void stop(){
        if(operation==null)return;
        cancel.setEnabled(false);status.setText("Deteniendo la importación…");operation.cancel();
    }
    @Override void onCovered(){if(operation!=null)operation.cancel();}
    @Override protected void onDestroy(){
        selected=Collections.emptyList();
        if(operation!=null)operation.cancel();
        writer.shutdown();super.onDestroy();
    }
}
