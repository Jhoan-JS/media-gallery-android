package local.ttlab.gallery;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/** Local visual browsing. Only an explicit catalogue analysis may request missing thumbnails. */
public final class VisualActivity extends SecureActivity {
    private final java.util.concurrent.ExecutorService queries=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"gallery-visual-query"));
    private static final int LIMIT=96;
    private final List<Entry> entries=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView title,status,empty;
    private EditText search;
    private GridView grid;
    private BaseAdapter adapter;
    private View refresh,modes;
    private Button analyze,sourceSelector,switchToTato;private State tatoState;
    private final Button[] modeButtons=new Button[3];
    private volatile int revision;
    private int columns,mode,indexed,total;
    private volatile boolean closed,resumed,progressPending;
    private boolean scanRequested,wasScanning;
    private String resultStatus="",displayedKey="";
    private final Runnable searchChanged=this::load;
    private final Runnable scanStatus=new Runnable(){
        @Override public void run(){
            if(!canUse())return;
            boolean running=tatoState.visual.scanning();
            if(wasScanning&&!running){wasScanning=false;load();}else pollProgress();
            if(running){wasScanning=true;handler.postDelayed(this,1000);}
        }
    };

    private static final class Entry {
        final Item item;final String label,targetKind,target;
        Entry(Item item,String label,String kind,String target){this.item=item;this.label=label;targetKind=kind;this.target=target;}
    }

    @Override public void onCreate(Bundle saved){
        setTheme(R.style.GalleryLibraryTheme);super.onCreate(saved);if(state==null)return;if("tato".equals(state.key))tatoState=state;
        columns=getSharedPreferences(SourceId.preferences(state.key),MODE_PRIVATE).getInt("columns",3);if(columns!=3&&columns!=4&&columns!=6)columns=3;
        mode=saved==null?0:saved.getInt("visual-mode",0);if(mode<0||mode>2)mode=0;
        scanRequested=saved!=null&&saved.getBoolean("scan-requested");
        boolean expanded=getResources().getConfiguration().screenWidthDp>=600;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(expanded?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);shell.setBackgroundColor(getColor(R.color.gallery_surface));
        if(expanded)shell.addView(navigation(true),new LinearLayout.LayoutParams(dp(96),-1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);shell.addView(page,new LinearLayout.LayoutParams(expanded?0:-1,expanded?-1:0,1));

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(8),dp(12),dp(4));
        if(detail()){ImageButton back=GalleryUi.icon(this,R.drawable.ic_gallery_back,"Volver");header.addView(back);back.setOnClickListener(v->finish());}
        title=GalleryUi.text(this,screenTitle(),26,getColor(R.color.gallery_foreground));title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setAccessibilityHeading(true);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        refresh=GalleryUi.icon(this,R.drawable.ic_gallery_sync,"Actualizar resultados visuales");header.addView(refresh);refresh.setOnClickListener(v->load());page.addView(header);

        LinearLayout context=new LinearLayout(this);context.setGravity(Gravity.CENTER_VERTICAL);context.setPadding(dp(20),dp(4),dp(20),dp(8));
        sourceSelector=UiSource.selector(this,this::sourceChanged);context.addView(sourceSelector,new LinearLayout.LayoutParams(-2,-2));
        context.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        analyze=GalleryUi.button(this,"Analizar",true);context.addView(analyze,new LinearLayout.LayoutParams(-2,-2));analyze.setOnClickListener(v->analysisChoice());switchToTato=GalleryUi.button(this,"Cambiar a Tato",false);switchToTato.setVisibility(View.GONE);switchToTato.setOnClickListener(v->{getSharedPreferences("gallery-appearance",MODE_PRIVATE).edit().putString("source","tato").apply();sourceChanged();});context.addView(switchToTato);page.addView(context);
        if(expanded&&getResources().getConfiguration().screenHeightDp<480){context.removeView(sourceSelector);context.removeView(analyze);header.addView(sourceSelector,header.getChildCount()-1);LinearLayout.LayoutParams actionSize=new LinearLayout.LayoutParams(-2,-2);actionSize.setMargins(dp(8),0,dp(8),0);header.addView(analyze,header.getChildCount()-1,actionSize);context.setVisibility(View.GONE);}

        search=new EditText(this);search.setSaveEnabled(false);search.setSingleLine(true);search.setTextSize(16);search.setHint("Describe lo que buscas…");search.setContentDescription("Buscar imágenes y portadas por una descripción en español");search.setTextColor(getColor(R.color.gallery_foreground));search.setHintTextColor(getColor(R.color.gallery_muted));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        search.setText(saved==null?"":state.preferences.getString("visual-query",""));LinearLayout.LayoutParams searchSize=new LinearLayout.LayoutParams(-1,dp(52));searchSize.setMargins(dp(20),0,dp(20),dp(4));page.addView(search,searchSize);

        HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);tabs.setClipToPadding(false);tabs.setPadding(dp(20),0,dp(20),dp(8));
        LinearLayout choices=new LinearLayout(this);String[] names={"Temas","Parecidas","Casi iguales"};
        for(int i=0;i<names.length;i++){final int selected=i;Button button=GalleryUi.button(this,names[i],false);button.setPadding(dp(12),0,dp(12),0);modeButtons[i]=button;LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,dp(48));if(i>0)size.setMargins(dp(8),0,0,0);choices.addView(button,size);button.setOnClickListener(v->{mode=selected;search.setText("");handler.removeCallbacks(searchChanged);load();});}
        tabs.addView(choices);modes=tabs;page.addView(tabs);
        status=GalleryUi.text(this,"Consultando el índice local…",12,getColor(R.color.gallery_muted));status.setPadding(dp(20),0,dp(20),dp(12));status.setLineSpacing(dp(2),1);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(status);

        FrameLayout body=new FrameLayout(this);grid=new GridView(this);grid.setNumColumns(columns);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(6));grid.setVerticalSpacing(dp(6));grid.setClipToPadding(false);grid.setPadding(dp(16),0,dp(16),dp(16));body.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        empty=GalleryUi.text(this,"Analiza el catálogo para encontrar temas, imágenes parecidas y posibles duplicados.",16,getColor(R.color.gallery_muted));empty.setGravity(Gravity.CENTER);empty.setLineSpacing(dp(5),1);empty.setPadding(dp(32),dp(24),dp(32),dp(24));body.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);page.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(!expanded)shell.addView(navigation(false),new LinearLayout.LayoutParams(-1,-2));content(shell);

        adapter=new BaseAdapter(){
            @Override public int getCount(){return entries.size();}
            @Override public Object getItem(int position){return entries.get(position).item;}
            @Override public long getItemId(int position){Item item=entries.get(position).item;return ((long)item.channel<<32)|(item.id&0xffffffffL);}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                PagedAdapter.Tile tile=recycled instanceof PagedAdapter.Tile?(PagedAdapter.Tile)recycled:new PagedAdapter.Tile(VisualActivity.this);
                int side=(parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight()-dp(6)*(columns-1))/columns;tile.setLayoutParams(new AbsListView.LayoutParams(-1,Math.max(dp(48),side)));
                Entry entry=entries.get(position);Item item=entry.item;String label=(item.video()&&entry.targetKind==null?"Video · ":"")+entry.label;
                tile.bound=item.cacheKey();tile.setTag(tile.bound);tile.placeholder();tile.caption.setVisibility(View.VISIBLE);tile.caption.setText(label);tile.caption.setMaxLines(entry.targetKind==null?1:2);tile.caption.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.video.setVisibility(item.video()?View.VISIBLE:View.GONE);tile.setContentDescription((entry.targetKind==null?item.label():entry.label+", abrir grupo")+(item.video()?", video: solo se analiza la portada":""));
                Bitmap cached=tatoState.media.memoryPreview(item);if(cached!=null)tile.preview(cached);else tatoState.thumbnails.execute(()->{
                    if(!canUse()||!item.cacheKey().equals(tile.bound))return;
                    try{Bitmap bitmap=tatoState.media.availableDisplayThumbnail(item);runOnUiThread(()->{if(canUse()&&item.cacheKey().equals(tile.getTag()))tile.preview(bitmap);});}
                    catch(Exception absent){runOnUiThread(()->{if(canUse()&&item.cacheKey().equals(tile.getTag()))tile.setContentDescription(item.label()+", miniatura no disponible, "+entry.label);});}
                });return tile;
            }
        };grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,position,id)->{if(!canUse()||position>=entries.size())return;Entry entry=entries.get(position);if(entry.targetKind!=null)startActivity(sourceIntent(VisualActivity.class).putExtra(entry.targetKind,entry.target).putExtra("visual-title",entry.label));else open(entry.item);});
        grid.setOnItemLongClickListener((parent,view,position,id)->{if(!canUse()||position>=entries.size())return false;actions(entries.get(position).item);return true;});
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){revision++;handler.removeCallbacks(searchChanged);updateModes();handler.postDelayed(searchChanged,500);}
            public void afterTextChanged(Editable value){}
        });
        search.setOnEditorActionListener((view,action,event)->{if(action!=EditorInfo.IME_ACTION_SEARCH)return false;handler.removeCallbacks(searchChanged);load();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(),0);return true;});
        updateModes();if(!UiSource.isTato(this))comingSoon();
    }

    private boolean detail(){return getIntent().hasExtra("topic")||getIntent().hasExtra("group")||getIntent().hasExtra("duplicate")||getIntent().getIntExtra("id",0)>0;}
    private String screenTitle(){if(getIntent().getIntExtra("id",0)>0)return "Similares";String value=getIntent().getStringExtra("visual-title");if(value!=null&&!value.isEmpty())return value;if(getIntent().hasExtra("topic"))return "Tema";if(getIntent().hasExtra("duplicate"))return "Casi iguales";if(getIntent().hasExtra("group"))return "Parecidas";return "Visual";}
    private boolean canUse(){return !closed&&resumed&&state!=null&&state.unlocked&&UiSource.isTato(this);}
    private boolean current(int request){return request==revision&&canUse();}

    @Override protected void onResume(){super.onResume();resumed=true;if(state!=null&&state.unlocked){load();if(UiSource.isTato(this)&&tatoState.visual.scanning()){scanRequested=true;wasScanning=true;handler.postDelayed(scanStatus,1000);}}}
    @Override protected void onPause(){resumed=false;revision++;handler.removeCallbacks(searchChanged);handler.removeCallbacks(scanStatus);if(tatoState!=null)tatoState.visual.pauseAvailable();super.onPause();}
    @Override void onUnlocked(){load();}

    private void analysisChoice(){
        if(!canUse())return;
        if(tatoState.visual.scanning()){tatoState.visual.pauseAvailable();wasScanning=false;resultStatus=tatoState.visual.status();handler.removeCallbacks(scanStatus);updateScanStatus();pollProgress();return;}
        TextView note=GalleryUi.text(this,"El catálogo obtiene solo las miniaturas que faltan, sin descargar originales. El análisis se hace aquí; en videos usa solo la portada.",14,getColor(R.color.gallery_muted));note.setPadding(dp(24),dp(8),dp(24),dp(16));
        new SecureDialogs.Builder(this).setTitle("Analizar miniaturas")
            .setItems(new String[]{"Analizar catálogo","Solo miniaturas guardadas"},(dialog,choice)->{
                if(!canUse())return;scanRequested=true;
                if(choice==0)tatoState.visual.startCatalogue(tatoState.media,this::canUse);else tatoState.visual.startAvailable();
                wasScanning=true;updateScanStatus();handler.removeCallbacks(scanStatus);handler.postDelayed(scanStatus,1000);pollProgress();
            })
            .setView(note)
            .setNegativeButton("Cancelar",null).show();
    }

    private void load(){
        if(state==null||search==null)return;
        if(!UiSource.isTato(this)){comingSoon();return;}if(!canUse())return;
        final int request=++revision,selected=mode;final String query=search.getText().toString().trim();
        final String topic=getIntent().getStringExtra("topic"),group=getIntent().getStringExtra("group"),duplicate=getIntent().getStringExtra("duplicate");final int channel=getIntent().getIntExtra("channel",0),id=getIntent().getIntExtra("id",0);
        final String key=topic+"\n"+group+"\n"+duplicate+"\n"+id+"\n"+selected+"\n"+query;
        title.setText(screenTitle());sourceSelector.setText(UiSource.label(this));sourceSelector.setContentDescription("Fuente: "+UiSource.label(this)+". Cambiar fuente");analyze.setVisibility(View.VISIBLE);switchToTato.setVisibility(View.GONE);refresh.setVisibility(View.VISIBLE);search.setVisibility(detail()?View.GONE:View.VISIBLE);modes.setVisibility(detail()?View.GONE:View.VISIBLE);refresh.setEnabled(false);updateModes();resultStatus="Consultando resultados…";updateScanStatus();
        queries.execute(()->{
            if(!current(request))return;
            List<Entry> result=new ArrayList<>();String message,emptyMessage;int done=indexed,all=total;
            try{
                if(!query.isEmpty()&&!detail()){
                    for(VisualIndex.Match match:tatoState.visual.search(query,LIMIT,()->current(request)))result.add(new Entry(match.item,match.item.label(),null,null));
                    message=result.size()+" resultados aproximados · hasta 96 por vista";emptyMessage="No hay coincidencias con esa descripción.\n\nPrueba otras palabras o analiza más miniaturas del catálogo.";
                }else if(id>0){
                    Item source=tatoState.library.get(channel,id);if(source==null)throw new IllegalStateException("El archivo ya no está en el catálogo. Actualiza Biblioteca.");
                    Bitmap thumbnail;try{thumbnail=tatoState.media.availableThumbnail(source);}catch(Exception absent){throw new IllegalStateException("Falta la miniatura. Usa Analizar catálogo y vuelve a intentarlo.");}
                    if(!current(request))return;tatoState.visual.index(source,thumbnail,()->current(request));if(!current(request))return;
                    for(VisualIndex.Match match:tatoState.visual.similar(source,LIMIT))result.add(new Entry(match.item,match.item.label(),null,null));
                    message=result.size()+" resultados parecidos · aproximados";emptyMessage="Todavía no hay resultados parecidos.\n\nAnaliza más miniaturas del catálogo y vuelve a intentarlo.";
                }else if(topic!=null){
                    for(Item item:tatoState.visual.topicItems(topic,LIMIT))result.add(new Entry(item,item.label(),null,null));
                    message=result.size()+" elementos del tema · hasta 96 por vista";emptyMessage="Este tema todavía no tiene elementos analizados.";
                }else if(duplicate!=null){
                    for(Item item:tatoState.visual.duplicateItems(duplicate,LIMIT))result.add(new Entry(item,item.label(),null,null));
                    message=result.size()+" posibles duplicados · revisa antes de decidir";emptyMessage="Este grupo ya no tiene elementos disponibles.";
                }else if(group!=null){
                    for(Item item:tatoState.visual.groupItems(group,LIMIT))result.add(new Entry(item,item.label(),null,null));
                    message=result.size()+" elementos parecidos · hasta 96 por vista";emptyMessage="Este grupo ya no tiene elementos disponibles.";
                }else if(selected==0){
                    for(VisualIndex.Topic found:tatoState.visual.topics(LIMIT))result.add(new Entry(found.representative,found.title+" · "+found.count,"topic",found.id));
                    message=result.size()+" temas · organización aproximada";emptyMessage="Todavía no hay temas.\n\nPulsa Analizar y elige el catálogo o las miniaturas guardadas.";
                }else if(selected==1){
                    for(VisualIndex.Group found:tatoState.visual.groups(LIMIT))result.add(new Entry(found.representative,found.count+" elementos parecidos","group",found.id));
                    message=result.size()+" grupos de elementos parecidos";emptyMessage="Todavía no hay grupos parecidos.\n\nAnaliza más miniaturas o busca similares desde un archivo.";
                }else{
                    for(VisualIndex.Group found:tatoState.visual.nearDuplicateGroups(LIMIT))result.add(new Entry(found.representative,found.count+" casi iguales","duplicate",found.id));
                    message=result.size()+" grupos de posibles duplicados";emptyMessage="No hay elementos casi iguales entre los analizados.\n\nEsta vista propone coincidencias visuales; no confirma archivos idénticos.";
                }
                if(!current(request))return;done=tatoState.visual.indexedCount();all=tatoState.visual.totalCount();
                String error=tatoState.visual.error();if(error!=null&&!error.isEmpty())message+="\n"+error;
            }catch(Exception error){
                String detail=error.getMessage();message=detail!=null&&(detail.startsWith("El archivo ya no")||detail.startsWith("Falta la miniatura"))?detail:"No se pudo consultar el análisis local. Pulsa Actualizar para reintentar.";
                emptyMessage="No se pudieron cargar los resultados.\n\nPuedes volver a intentarlo o continuar en Biblioteca.";
                try{if(current(request)){done=tatoState.visual.indexedCount();all=tatoState.visual.totalCount();}}catch(Exception unavailable){}
            }
            final String resultMessage=message,emptyText=emptyMessage;final int complete=done,catalogue=all;
            runOnUiThread(()->{
                if(!current(request))return;boolean preserve=key.equals(displayedKey);int position=preserve?grid.getFirstVisiblePosition():0,top=preserve&&grid.getChildCount()>0?grid.getChildAt(0).getTop():0;
                entries.clear();entries.addAll(result);adapter.notifyDataSetChanged();if(!entries.isEmpty())GalleryUi.restoreGrid(grid,Math.min(position,entries.size()-1),top);displayedKey=key;
                indexed=complete;total=catalogue;resultStatus=resultMessage;empty.setText(emptyText);refresh.setEnabled(true);updateScanStatus();
            });
        });
    }

    private void pollProgress(){
        if(!canUse()||progressPending)return;final int request=revision;progressPending=true;
        queries.execute(()->{
            try{if(!current(request))return;int complete=tatoState.visual.indexedCount(),catalogue=tatoState.visual.totalCount();runOnUiThread(()->{if(!current(request))return;indexed=complete;total=catalogue;updateScanStatus();});}
            catch(Exception unavailable){runOnUiThread(()->{if(current(request))status.setText("No se pudo consultar el progreso. Pulsa Actualizar.");});}
            finally{progressPending=false;}
        });
    }
    private void updateModes(){if(search==null)return;boolean browsing=search.getText().toString().trim().isEmpty();for(int i=0;i<modeButtons.length;i++){Button button=modeButtons[i];if(button==null)continue;boolean selected=browsing&&mode==i;button.setSelected(selected);button.setTextColor(getColor(selected?R.color.gallery_foreground:R.color.gallery_muted));button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_ripple)),GalleryUi.background(getColor(selected?R.color.gallery_primary_container:R.color.gallery_surface_container),dp(12)),null));button.setContentDescription(button.getText()+(selected?", seleccionado":""));}}
    private void updateScanStatus(){
        if(analyze==null||status==null||!UiSource.isTato(this))return;
        boolean scanning=tatoState.visual.scanning();analyze.setText(scanning?"Pausar":"Analizar");analyze.setContentDescription(scanning?"Pausar análisis de miniaturas":"Elegir análisis del catálogo o solo de miniaturas guardadas");
        String phase=scanning?tatoState.visual.status():resultStatus;if((phase==null||phase.isEmpty())&&scanRequested)phase=tatoState.visual.status();
        status.setText(indexed+" de "+total+" elementos analizados\n"+(phase==null?"":phase)+"\nVideos: solo portada");
    }
    private void open(Item item){if(!canUse())return;java.util.ArrayList<Long> order=new java.util.ArrayList<>();for(Entry entry:entries)if(entry.targetKind==null&&entry.item!=null&&order.size()<96)order.add(((long)entry.item.channel<<32)|(entry.item.id&0xffffffffL));long[] keys=new long[order.size()];for(int n=0;n<keys.length;n++)keys[n]=order.get(n);startActivity(sourceIntent(ViewerActivity.class).putExtra("channel",item.channel).putExtra("id",item.id).putExtra("viewer-keys",keys));}
    private void actions(Item item){new SecureDialogs.Builder(this).setTitle(item.label()).setItems(new String[]{"Abrir","Buscar similares","Guardar sin conexión"},(dialog,choice)->{if(!canUse())return;if(choice==0)open(item);else if(choice==1)startActivity(sourceIntent(VisualActivity.class).putExtra("channel",item.channel).putExtra("id",item.id));else DownloadService.start(this,state.key,item);}).show();}
    private View navigation(boolean rail) {
        return GalleryUi.navigation(this, GalleryWorkspace.LIBRARY, rail, destination -> {
            if (!GalleryWorkspace.usable(this)) return;
            UiSource.open(this, state.key, destination);
            finish();
        });
    }
    private void sourceChanged(){
        revision++;handler.removeCallbacks(searchChanged);handler.removeCallbacks(scanStatus);if(tatoState!=null)tatoState.visual.pauseAvailable();
        try{state=State.get(this,UiSource.key(this));getIntent().putExtra("source",state.key);if("tato".equals(state.key))tatoState=state;}catch(Exception unavailable){status.setText("No se pudo abrir esta fuente. Reintenta.");return;}
        entries.clear();if(adapter!=null)adapter.notifyDataSetChanged();displayedKey="";scanRequested=wasScanning=false;indexed=total=0;mode=0;search.setText("");
        for(String key:new String[]{"topic","group","duplicate","id","channel","visual-title"})getIntent().removeExtra(key);
        sourceSelector.setText(UiSource.label(this));if(!UiSource.isTato(this)){comingSoon();return;}load();
    }
    private void comingSoon(){revision++;handler.removeCallbacks(searchChanged);handler.removeCallbacks(scanStatus);entries.clear();if(adapter!=null)adapter.notifyDataSetChanged();title.setText("Visual");sourceSelector.setText(UiSource.label(this));sourceSelector.setContentDescription("Fuente: "+UiSource.label(this)+". Cambiar fuente");analyze.setVisibility(View.GONE);switchToTato.setVisibility(View.VISIBLE);refresh.setVisibility(View.GONE);search.setVisibility(View.GONE);modes.setVisibility(View.GONE);status.setText("Telegram · Visual no disponible");empty.setText("El análisis visual se ofrece solo para Tato.\n\nLos términos de la API de Telegram restringen el uso de sus datos en IA. Sus fotos y videos siguen disponibles en Biblioteca, sin analizarlos.");}
    private void library(boolean downloads){startActivity(sourceIntent(MainActivity.class).putExtra("downloads",downloads).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("visual-mode",mode);out.putBoolean("scan-requested",scanRequested);if(search!=null)try{state.preferences.edit().putString("visual-query",search.getText().toString()).apply();}catch(RuntimeException unavailable){}out.remove("visual-query");}
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);recreate();}
    @Override protected void onDestroy(){closed=true;revision++;handler.removeCallbacksAndMessages(null);queries.shutdownNow();super.onDestroy();}
}
