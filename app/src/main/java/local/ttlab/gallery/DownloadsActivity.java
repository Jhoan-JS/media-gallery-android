package local.ttlab.gallery;

import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, bounded queue snapshots; transfers remain in the existing encrypted foreground service. */
public final class DownloadsActivity extends SecureActivity {
    private static final int PAGE=48;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private TextView summary,notice,pageLabel;private ListView list;private Button all,previous,next;
    private Rows adapter;private Button saved;private boolean resumed,listening,loading,scrolling;private int page;private long epoch;
    private final Runnable poll=()->refresh();
    private final BroadcastReceiver changed=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(state==null||!state.key.equals(i.getStringExtra("source")))return;ui.removeCallbacks(poll);ui.post(poll);}};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);if(this.state==null)return;if(state!=null)page=Math.max(0,state.getInt("downloads-page",0));
        LinearLayout root = GalleryWorkspace.page(this);
        LinearLayout top = GalleryWorkspace.toolbar(this, "Descargas", null, null);
        ImageButton more = GalleryUi.icon(this, R.drawable.ic_gallery_more, "Opciones de descargas");
        more.setOnClickListener(view -> showOptions());
        top.addView(more);
        root.addView(top);
        GalleryWorkspace.sectionLabel(this, root, SourceId.label(this.state.key));
        summary=text("Cargando descargas…",14,color(R.color.gallery_muted));summary.setPadding(dp(20),dp(8),dp(20),dp(8));root.addView(summary);
        LinearLayout commands=new LinearLayout(this);commands.setGravity(Gravity.CENTER_VERTICAL);commands.setPadding(dp(16),0,dp(16),dp(8));
        all=GalleryUi.button(this,"Reanudar todas",false);all.setEnabled(false);commands.addView(all,new LinearLayout.LayoutParams(0,-2,1));
        saved=GalleryUi.button(this,"Archivos guardados",false);saved.setOnClickListener(view -> navigate(GalleryWorkspace.DOWNLOAD_FILES));LinearLayout.LayoutParams savedLayout=new LinearLayout.LayoutParams(0,-2,1);savedLayout.leftMargin=dp(8);commands.addView(saved,savedLayout);root.addView(commands);
        notice=text("Las copias se guardan cifradas. Puedes pausar y continuar sin empezar de cero.",12,color(R.color.gallery_muted));notice.setPadding(dp(20),0,dp(20),dp(12));root.addView(notice);
        list=new ListView(this);list.setDividerHeight(0);list.setClipToPadding(false);list.setPadding(dp(16),0,dp(16),dp(8));adapter=new Rows();list.setAdapter(adapter);list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){scrolling=state!=SCROLL_STATE_IDLE;}public void onScroll(AbsListView v,int first,int visible,int total){}});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout pages=new LinearLayout(this);pages.setPadding(dp(16),dp(4),dp(16),dp(12));pages.setGravity(Gravity.CENTER_VERTICAL);
        previous=GalleryUi.button(this,"Anterior",false);previous.setOnClickListener(v->{if(page>0){page--;list.setSelection(0);refresh();}});pages.addView(previous);
        pageLabel=text("",12,color(R.color.gallery_muted));pageLabel.setGravity(Gravity.CENTER);pages.addView(pageLabel,new LinearLayout.LayoutParams(0,-2,1));
        next=GalleryUi.button(this,"Siguiente",false);next.setOnClickListener(v->{page++;list.setSelection(0);refresh();});pages.addView(next);root.addView(pages);
        GalleryWorkspace workspace = new GalleryWorkspace(this, root, this::navigate);
        workspace.show(GalleryWorkspace.DOWNLOADS, null);
        content(workspace.view());
    }
    private void navigate(String section) {
        if (!GalleryWorkspace.usable(this) || GalleryWorkspace.DOWNLOADS.equals(section)) return;
        if (getIntent().hasExtra(GalleryWorkspace.SECTION)) {
            setResult(RESULT_OK, new Intent().putExtra(GalleryWorkspace.SECTION, section));
        } else UiSource.open(this, state.key, section);
        finish();
    }

    private int color(int id){return getColor(id);}
    @Override protected void onResume(){super.onResume();resumed=true;if(state!=null&&state.unlocked)refresh();}
    @Override void onUnlocked(){refresh();}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(DownloadService.CHANGED);registerReceiver(changed,f,"local.ttlab.permission.GALLERY",null,Build.VERSION.SDK_INT>=33?Context.RECEIVER_NOT_EXPORTED:0);listening=true;}
    @Override protected void onStop(){if(listening){unregisterReceiver(changed);listening=false;}super.onStop();}
    @Override protected void onPause(){resumed=false;epoch++;ui.removeCallbacksAndMessages(null);super.onPause();}
    private void refresh(){
        if(!resumed||state==null||!state.unlocked||isFinishing()||isDestroyed())return;
        saved.setEnabled(true);
        if(loading){ui.removeCallbacks(poll);ui.postDelayed(poll,500);return;}loading=true;final long request=++epoch;final int requestedPage=page;
        reader.execute(()->{
            try{
                Snapshot snapshot=new Snapshot();
                try(Cursor c=state.library.db.rawQuery("SELECT count(*),COALESCE(sum(CASE WHEN status IN('queued','running') THEN 1 ELSE 0 END),0),COALESCE(sum(progress),0) FROM downloads",(String[])null)){c.moveToFirst();snapshot.total=c.getInt(0);snapshot.active=c.getInt(1);snapshot.bytes=c.getLong(2);}
                snapshot.saved=state.library.count(0,"",0,true);
                int safePage=Math.max(0,Math.min(requestedPage,Math.max(0,(snapshot.total-1)/PAGE)));snapshot.page=safePage;
                try(Cursor c=state.library.db.rawQuery("SELECT d.channel,d.id,d.progress,d.status,d.error,m.name,m.size FROM downloads d LEFT JOIN media m ON m.channel=d.channel AND m.id=d.id ORDER BY CASE d.status WHEN 'running' THEN 0 WHEN 'queued' THEN 1 WHEN 'error' THEN 2 ELSE 3 END,d.priority DESC,d.added,d.channel,d.id LIMIT ? OFFSET ?",new String[]{""+PAGE,""+(safePage*PAGE)})){
                    while(c.moveToNext())snapshot.rows.add(new Row(c.getInt(0),c.getInt(1),c.getLong(2),c.getString(3),c.getString(4),c.isNull(5)?"Archivo fuera del catálogo":c.getString(5),c.isNull(6)?0:c.getLong(6)));
                }
                ui.post(()->{loading=false;if(!resumed||request!=epoch||!state.unlocked||isDestroyed())return;render(snapshot);ui.removeCallbacks(poll);ui.postDelayed(poll,1000);});
            }catch(Exception failure){ui.post(()->{loading=false;if(!resumed||request!=epoch||isDestroyed())return;summary.setText("No se pudo leer la cola. Vuelve a abrir Descargas para reintentar.");});}
        });
    }
    private void render(Snapshot snapshot){
        page=snapshot.page;summary.setText(snapshot.total==0?"Sin descargas pendientes · "+snapshot.saved+" guardadas":snapshot.total+" pendientes · "+snapshot.saved+" guardadas · "+DownloadProgress.bytes(snapshot.bytes)+" preparados");
        boolean live=DownloadService.running(state.key)&&snapshot.active>0;all.setText(live?"Pausar todas":"Reanudar todas");all.setEnabled(snapshot.total>0);all.setOnClickListener(v->control(live?"pause-all":"resume-all",0,0));
        int first=list.getFirstVisiblePosition(),offset=list.getChildCount()>0?list.getChildAt(0).getTop():0;adapter.rows=snapshot.rows;adapter.notifyDataSetChanged();if(!scrolling&&!adapter.rows.isEmpty())list.setSelectionFromTop(Math.min(first,adapter.rows.size()-1),offset);
        previous.setEnabled(page>0);next.setEnabled((page+1)*PAGE<snapshot.total);pageLabel.setText(snapshot.total==0?"Sin cola":(page+1)+" / "+((snapshot.total+PAGE-1)/PAGE));
        notice.setText(snapshot.total==0?"Guarda un archivo desde Biblioteca o desde su visor. Las descargas completas están en Archivos guardados.":live?"En ⋮ puedes elegir la siguiente descarga de esta fuente. La que está en curso termina primero.":"La cola está detenida. En ⋮ puedes ordenar qué descargar primero al reanudar; los bloques cifrados se conservan.");
    }
    private void showOptions(){
        String[] options=adapter.getCount()>0?new String[]{"Almacenamiento","Cancelar toda la cola"}:new String[]{"Almacenamiento"};
        GalleryUi.menu(this,"Descargas",options,n->{
            if(n==0){startActivity(sourceIntent(StorageActivity.class));return;}
            android.app.AlertDialog confirm=new SecureDialogs.Builder(this).setTitle("¿Cancelar toda la cola?")
                .setMessage("Se retirarán las descargas pendientes de "+SourceId.label(state.key)+" y se perderá el progreso parcial. Los archivos ya guardados se conservan.")
                .setNegativeButton("Conservar",null).setPositiveButton("Cancelar cola",(d,w)->control("cancel",0,0)).create();
            trackPrivateDialog(confirm);confirm.show();
        });
    }
    private void control(String action,int channel,int id){
        if(!state.unlocked)return;
        try{if(!DownloadService.control(this,state.key,action,channel,id)){notice.setText("Android no permitió iniciar la descarga. Mantén la galería abierta y reintenta.");return;}notice.setText(action.startsWith("pause")?"Pausa solicitada. Esperando al bloque en curso…":"Solicitud enviada. Actualizando la cola…");ui.removeCallbacks(poll);ui.postDelayed(poll,250);}
        catch(RuntimeException blocked){notice.setText("Android no permitió iniciar la descarga. Mantén la galería abierta y reintenta.");}
    }
    private final class Rows extends BaseAdapter {
        List<Row> rows=Collections.emptyList();
        @Override public int getCount(){return rows.size();}@Override public Object getItem(int p){return rows.get(p);}@Override public long getItemId(int p){return p<0||p>=rows.size()?AdapterView.INVALID_ROW_ID:((long)rows.get(p).channel<<32)^(rows.get(p).id&0xffffffffL);}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            Row row=rows.get(position);LinearLayout view=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(DownloadsActivity.this);view.removeAllViews();view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(4),dp(14),dp(4),dp(14));
            view.setContentDescription(row.name);
            TextView name=text(row.name.isEmpty()?"Archivo sin nombre":row.name,16,color(R.color.gallery_foreground));name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);view.addView(name);
            boolean current="running".equals(row.status)&&DownloadService.running(state.key);String label=current?"Descargando":"paused".equals(row.status)?"Pausada":"error".equals(row.status)?"Detenida":"running".equals(row.status)?"Interrumpida":"En cola";
            int percent=DownloadProgress.percent(row.bytes,row.total);TextView status=text(label+" · "+percent+" %",13,color(R.color.gallery_muted));status.setPadding(0,dp(5),0,dp(5));view.addView(status);
            ProgressBar progress=new ProgressBar(DownloadsActivity.this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(percent);progress.setProgressTintList(ColorStateList.valueOf(color(R.color.gallery_primary)));progress.setProgressBackgroundTintList(ColorStateList.valueOf(color(R.color.gallery_outline)));progress.setContentDescription("Descarga: "+percent+" por ciento");view.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));
            String detail=DownloadProgress.bytes(row.bytes)+" de "+DownloadProgress.bytes(row.total);DownloadService.Transfer active=DownloadService.transfer(state.key);
            if(current&&active!=null&&active.channel==row.channel&&active.id==row.id){long elapsed=SystemClock.elapsedRealtime()-active.startElapsed;long speed=elapsed>0?(long)((active.bytes-active.startBytes)*1000d/elapsed):0;detail+=" · "+DownloadProgress.bytes(speed)+"/s\n"+DownloadProgress.remainingLabel(active.remainingSeconds());}
            TextView bytes=text(detail,12,color(R.color.gallery_muted));bytes.setPadding(0,dp(7),0,dp(4));view.addView(bytes);
            if("error".equals(row.status)&&row.error!=null&&!row.error.isEmpty()){TextView error=text(row.error,13,color(R.color.gallery_error));error.setPadding(0,dp(4),0,dp(4));view.addView(error);}
            LinearLayout controls=new LinearLayout(DownloadsActivity.this);boolean pausable=("running".equals(row.status)||"queued".equals(row.status))&&DownloadService.running(state.key);
            Button toggle=GalleryUi.button(DownloadsActivity.this,pausable?"Pausar":"error".equals(row.status)?"Reintentar":"Reanudar",false);toggle.setOnClickListener(v->control(pausable?"pause":"resume",row.channel,row.id));controls.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));
            ImageButton options=GalleryUi.icon(DownloadsActivity.this,R.drawable.ic_gallery_more,"Opciones de descarga de "+row.name);options.setTag("queue-options-"+row.channel+"-"+row.id);options.setOnClickListener(v->{boolean inFlight="running".equals(row.status)&&DownloadService.running(state.key);String[] actions=inFlight?new String[]{"Cancelar descarga"}:new String[]{"Descargar primero","Cancelar descarga"};GalleryUi.menu(DownloadsActivity.this,"Descarga",actions,n->{if(!inFlight&&n==0)control("priority",row.channel,row.id);else new SecureDialogs.Builder(DownloadsActivity.this).setTitle("¿Cancelar esta descarga?").setMessage("Se retirará de la cola y se eliminará su copia parcial. Los archivos completos se conservan.").setNegativeButton("Conservar",null).setPositiveButton("Cancelar descarga",(d,w)->control("remove",row.channel,row.id)).show();});});controls.addView(options,new LinearLayout.LayoutParams(dp(48),dp(48)));view.addView(controls);return view;
        }
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("downloads-page",page);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){epoch++;ui.removeCallbacksAndMessages(null);reader.shutdownNow();super.onDestroy();}
    private static final class Snapshot {int total,active,saved,page;long bytes;final List<Row> rows=new ArrayList<>();}
    private static final class Row {final int channel,id;final long bytes,total;final String status,error,name;Row(int channel,int id,long bytes,String status,String error,String name,long total){this.channel=channel;this.id=id;this.bytes=bytes;this.status=status;this.error=error;this.name=name;this.total=total;}}
}
