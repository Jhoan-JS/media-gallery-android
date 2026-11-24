package local.ttlab.gallery;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends SecureActivity {
    static final int FG=0xffE8ECE5,MUTED=0xffADB5AA,ACCENT=0xffBDC9A4;
    private EditText search;private TextView title,status,empty;
    private Button channelControl,searchBoth;private ImageButton overflow;
    private GalleryGridView grid;private ListView channelView;private FrameLayout body,navHost;
    private SponsoredBanner sponsored;
    private ActionMode selectionMode;private final LinkedHashMap<String,Item> selected=new LinkedHashMap<>();private boolean selectionBusy;
    private SelectionViewport selectionViewport;
    private static final class SelectionViewport {
        final String key;final Item item;final int position,top;
        SelectionViewport(String key,Item item,int position,int top){this.key=key;this.item=item;this.position=position;this.top=top;}
    }
    private Map<Integer,Library.ChatStatus> chatStatus=new HashMap<>();
    private ChatFolders.Snapshot chatFolders=new ChatFolders.Snapshot();
    private long chatFolderId;
    private boolean gridIdle=true;private PagedAdapter adapter;private SharedPreferences preferences;private Dialog settingsDialog;
    private int channelId,type,columns=3,revision,restorePosition,restoreTop,collectionTitleRevision,personalContext;
    private final UndoBar undo=new UndoBar(this);
    private long from,until;private boolean offline,duplicates,downloads,channelsMode,loaded,listening,expanded,stackResends;
    private MediaFilter advanced=new MediaFilter();private boolean refreshInFlight,refreshAgain,restoreViewport;private String viewportKey="";private int viewportPosition,viewportTop;private Item viewportAnchor;
    private String filterKey="",mediaQuery="";private List<Source.Channel> channelList=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable updateSearch=this::refresh;
    private final Runnable updateCatalogue=()->{loadChannels();refresh();};
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(state!=null&&state.key.equals(intent.getStringExtra("source"))){updateSyncControl();if(DownloadService.CHANGED.equals(intent.getAction())&&!intent.getBooleanExtra("catalogue",true))return;handler.removeCallbacks(updateCatalogue);handler.postDelayed(updateCatalogue,250);}}};

    @Override public void onCreate(Bundle saved){
        setTheme(R.style.GalleryLibraryTheme);super.onCreate(saved);if(state==null)return;
        getSharedPreferences("gallery-appearance",MODE_PRIVATE).edit().putString("source",state.key).apply();
        authorizationSource=saved==null?null:saved.getString("authorization-source");
        loadPreferences();
        downloads=saved!=null?saved.getBoolean("downloads"):getIntent().getBooleanExtra("downloads",false);
        channelsMode=saved!=null?saved.getBoolean("channels"):"channels".equals(getIntent().getStringExtra("section"));
        if(UiSource.isLocal(this))channelsMode=downloads=false;
        restorePosition=saved==null?0:saved.getInt("position");restoreTop=saved==null?0:saved.getInt("top");
        expanded=getResources().getConfiguration().screenWidthDp>=600;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(expanded?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);shell.setBackgroundColor(color(R.color.gallery_surface));shell.setFocusableInTouchMode(true);
        navHost=new FrameLayout(this);if(expanded)shell.addView(navHost,new LinearLayout.LayoutParams(dp(104),-1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);shell.addView(page,new LinearLayout.LayoutParams(expanded?0:-1,expanded?-1:0,1));
        LinearLayout top=row();top.setPadding(dp(20),dp(6),dp(8),dp(6));
        title=text("Biblioteca",24,FG);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,icon(R.drawable.ic_gallery_chevron_down),null);title.setCompoundDrawablePadding(dp(4));title.setClickable(true);title.setFocusable(true);title.setOnClickListener(v->showCollections());title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setAccessibilityHeading(true);top.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        Button source=UiSource.selector(this,this::sourceChanged);source.setTag("source-selector");source.setBackgroundColor(android.graphics.Color.TRANSPARENT);source.setPadding(dp(10),0,dp(10),0);top.addView(source,new LinearLayout.LayoutParams(-2,dp(48)));
        overflow=GalleryUi.icon(this,R.drawable.ic_gallery_more,"Más opciones");top.addView(overflow);overflow.setOnClickListener(v->showOverflow());page.addView(top);
        LinearLayout searchRow=row();searchRow.setPadding(dp(16),0,dp(16),dp(10));
        LinearLayout searchBox=row();searchBox.setBackground(GalleryUi.background(color(R.color.gallery_surface_container),dp(12)));
        ImageView searchIcon=new ImageView(this);searchIcon.setImageResource(R.drawable.ic_gallery_search);searchIcon.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));searchIcon.setPadding(dp(14),dp(14),dp(8),dp(14));searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);searchBox.addView(searchIcon,new LinearLayout.LayoutParams(dp(44),dp(52)));
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(16);search.setTextColor(FG);search.setHintTextColor(MUTED);search.setBackgroundColor(android.graphics.Color.TRANSPARENT);search.setPadding(0,0,0,0);search.setHint("Buscar por nombre");search.setContentDescription("Buscar por nombre");search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});search.setSaveEnabled(false);search.setText(preferences.getString("query",""));searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(52),1));
        searchBoth=GalleryUi.button(this,"Ambas",false);searchBoth.setTag("search-both");
        searchBoth.setContentDescription("Buscar en ambas fuentes");searchBoth.setPadding(dp(10),0,dp(10),0);
        searchBoth.setOnClickListener(v->{if(state.unlocked&&biometricCurrent(unlockEpoch()))startActivity(sourceIntent(CombinedSearchActivity.class));});
        searchBox.addView(searchBoth,new LinearLayout.LayoutParams(-2,dp(48)));
        ImageButton clear=GalleryUi.icon(this,R.drawable.ic_gallery_close,"Borrar búsqueda");clear.setOnClickListener(v->search.setText(""));searchBox.addView(clear);searchRow.addView(searchBox,new LinearLayout.LayoutParams(-1,dp(52)));page.addView(searchRow);
        LinearLayout tools=row();tools.setPadding(dp(16),0,dp(20),dp(4));
        channelControl=button((UiSource.isTato(this)?"Todos los canales":"Todos los chats"));channelControl.setBackgroundColor(android.graphics.Color.TRANSPARENT);channelControl.setPadding(dp(4),0,dp(8),0);channelControl.setTextSize(14);channelControl.setSingleLine(true);channelControl.setEllipsize(TextUtils.TruncateAt.END);channelControl.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,icon(R.drawable.ic_gallery_chevron_down),null);channelControl.setCompoundDrawablePadding(dp(4));tools.addView(channelControl,new LinearLayout.LayoutParams(0,dp(48),1));channelControl.setOnClickListener(v->{if(channelsMode)showChatFolders();else showChannels();});
        status=text("",12,MUTED);status.setSingleLine(true);status.setMaxWidth(dp(150));status.setEllipsize(TextUtils.TruncateAt.END);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);tools.addView(status,new LinearLayout.LayoutParams(-2,-2));page.addView(tools);
        sponsored=new SponsoredBanner(this);page.addView(sponsored,new LinearLayout.LayoutParams(-1,-2));
        body=new FrameLayout(this);grid=new GalleryGridView(this);grid.setNumColumns(columns);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(5));grid.setVerticalSpacing(dp(5));grid.setClipToPadding(false);grid.setPadding(dp(12),0,dp(12),dp(16));grid.setSaveEnabled(false);grid.setFastScrollAlwaysVisible(false);body.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        channelView=new ListView(this);channelView.setTag("chat-folders-rows");channelView.setDivider(new android.graphics.drawable.ColorDrawable(color(R.color.gallery_outline)));channelView.setDividerHeight(1);channelView.setPadding(dp(16),0,dp(16),dp(16));channelView.setClipToPadding(false);body.addView(channelView,new FrameLayout.LayoutParams(-1,-1));channelView.setVisibility(View.GONE);
        empty=text("",16,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(40),dp(24),dp(40),dp(24));body.addView(empty,new FrameLayout.LayoutParams(-1,-1));empty.setVisibility(View.GONE);page.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(!expanded)shell.addView(navHost,new LinearLayout.LayoutParams(-1,-2));content(shell);shell.requestFocus();updateChrome();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){finishSelection();clear.setVisibility(s.length()==0?View.GONE:View.VISIBLE);updateSearchScope();handler.removeCallbacks(updateSearch);handler.postDelayed(updateSearch,250);}public void afterTextChanged(Editable e){}});
        search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){handler.removeCallbacks(updateSearch);refresh();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(),0);return true;}return false;});
        if(saved==null&&getIntent().hasExtra("initial-filter"))try{applyCollection(MediaFilter.from(getIntent().getBundleExtra("initial-filter")));}catch(IllegalArgumentException invalid){Toast.makeText(this,"No se pudieron aplicar los filtros",Toast.LENGTH_SHORT).show();}
        clear.setVisibility(search.length()==0?View.GONE:View.VISIBLE);
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int scroll){gridIdle=scroll==SCROLL_STATE_IDLE;if(adapter!=null)adapter.viewport(grid.getFirstVisiblePosition(),grid.getChildCount(),gridIdle);}public void onScroll(AbsListView view,int first,int visible,int total){if(adapter!=null)adapter.viewport(first,visible,gridIdle);}});
        grid.setOnItemClickListener((parent,v,position,id)->{Item item=adapter==null?null:adapter.item(position);if(item!=null&&UiSource.available(this)){if(selectionMode!=null)toggleSelection(item);else open(item);}});
        grid.setOnItemLongClickListener((parent,v,position,id)->{if(grid.dateGesturePending())return true;Item item=adapter==null?null:adapter.item(position);if(item==null||!UiSource.available(this))return false;beginSelection(position);toggleSelection(item);return true;});
    }
    private void updateChrome(){
        updateCollections();
        View source=getWindow().getDecorView().findViewWithTag("source-selector");if(source instanceof Button){((Button)source).setText(UiSource.label(this));source.setContentDescription("Fuente: "+UiSource.label(this)+". Cambiar fuente");}
        navHost.removeAllViews();navHost.addView(GalleryUi.navigation(this,channelsMode?"channels":downloads?"downloads":"library",expanded,this::navigate));
        search.setHint(channelsMode?"Buscar canales":"Buscar por nombre");search.setContentDescription(channelsMode?"Buscar canales":"Buscar por nombre");
        boolean enabled=UiSource.available(this);overflow.setEnabled(enabled);search.setEnabled(enabled);channelControl.setEnabled(enabled);
        updateSearchScope();updateChannelLabel();updateFilterLabel();updateSyncControl();
    }
    private void updateSearchScope(){
        searchBoth.setVisibility(!UiSource.isLocal(this)&&!channelsMode&&!downloads&&!search.getText().toString().trim().isEmpty()?View.VISIBLE:View.GONE);
    }
    private void navigate(String destination){
        finishSelection();rememberViewport();
        if(UiSource.isLocal(this)&&!destination.equals("library")){
            if(destination.equals("import")&&state.unlocked&&biometricCurrent(unlockEpoch()))startActivity(sourceIntent(LocalImportActivity.class));
            return;
        }
        if(destination.equals("downloads")){startActivity(sourceIntent(DownloadsActivity.class));return;}
        if(destination.equals("visual")){startActivity(sourceIntent(VisualActivity.class));return;}
        boolean nextChannels=destination.equals("channels");if(nextChannels!=channelsMode){if(nextChannels){mediaQuery=search.getText().toString();search.setText("");}else search.setText(mediaQuery);}
        channelsMode=nextChannels;downloads=destination.equals("downloads");updateChrome();refresh();
    }
    private void loadPreferences(){
        preferences=state.preferences;
        chatFolderId=preferences.getLong("chat-folder-id",0);
        stackResends=preferences.getBoolean("stack-resends",false);
        channelId=preferences.getInt("channel",0);type=preferences.getInt("type",0);columns=preferences.getInt("columns",3);
        if(columns!=3&&columns!=4&&columns!=6)columns=3;
        offline=preferences.getBoolean("offline",false);duplicates=preferences.getBoolean("duplicates",false);from=preferences.getLong("from",0);until=preferences.getLong("until",0);
        advanced.albumId=preferences.getLong("albumId",0);advanced.tagId=preferences.getLong("tagId",0);advanced.recent=preferences.getBoolean("recent",false);
        advanced.availability=preferences.getInt("availability",0);advanced.watched=preferences.getInt("watched",0);advanced.sort=preferences.getInt("sort",0);advanced.shape=preferences.getInt("shape",0);advanced.format=preferences.getInt("format",0);advanced.collection=preferences.getInt("collection",0);advanced.archive=preferences.getInt("archive",0);advanced.minBytes=preferences.getLong("minBytes",0);advanced.maxBytes=preferences.getLong("maxBytes",0);advanced.minSeconds=preferences.getLong("minSeconds",0);advanced.maxSeconds=preferences.getLong("maxSeconds",0);try{advanced.check();}catch(IllegalArgumentException invalid){advanced=new MediaFilter();}
    }
    private void sourceChanged(){
        if(state==null||state.key.equals(UiSource.key(this)))return;
        personalContext++;undo.clear();finishSelection();rememberViewport();savePreferences();revision++;handler.removeCallbacks(updateSearch);handler.removeCallbacks(updateCatalogue);
        if(adapter!=null){adapter.close();adapter=null;}grid.setAdapter(null);channelList=new ArrayList<>();chatFolders=new ChatFolders.Snapshot();chatStatus=new HashMap<>();filterKey="";viewportKey="";restoreViewport=false;selectionViewport=null;restorePosition=restoreTop=0;refreshInFlight=refreshAgain=false;advanced=new MediaFilter();
        try{state=State.get(this,UiSource.key(this));getIntent().putExtra("source",state.key);}catch(Exception unavailable){status.setText("No se pudo abrir esta fuente. Vuelve a intentarlo.");return;}
        if(UiSource.isLocal(this))channelsMode=downloads=false;
        loadPreferences();grid.setNumColumns(columns);search.setText(preferences.getString("query",""));mediaQuery="";updateChrome();loadChannels();refresh();
    }
    private void updateChannelLabel(){
        channelControl.setVisibility(UiSource.isLocal(this)?View.GONE:View.VISIBLE);
        String name=(UiSource.isTato(this)?"Todos los canales":"Todos los chats");
        if(channelsMode){
            ChatFolders.Folder folder=chatFolders.folders.get(chatFolderId);
            if(folder!=null)name=folder.name;
            channelControl.setText(name);
            channelControl.setContentDescription("Carpetas de chats. "+name);
            return;
        }
        for(Source.Channel c:channelList)if(c.id==channelId){name=c.title;break;}
        channelControl.setText(name);channelControl.setContentDescription("Elegir canal. "+name);
    }
    private void updateCollections(){
        if(title==null)return;final int generation=++collectionTitleRevision;String[] names={"Biblioteca","Favoritos","Ver después","Continuar"};
        String label=channelsMode?(UiSource.isTato(this)?"Canales":"Chats"):advanced.recent?"Historial":advanced.albumId>0?"Álbum":advanced.tagId>0?"Etiqueta":advanced.archive==1?"Archivados":advanced.collection>0?names[advanced.collection]:downloads?"Guardados":"Biblioteca";
        title.setText(label);title.setClickable(!channelsMode);title.setFocusable(!channelsMode);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,channelsMode?null:icon(R.drawable.ic_gallery_chevron_down),null);
        title.setContentDescription(channelsMode?label:"Elegir colección. "+label);
        if(!channelsMode&&!advanced.recent&&(advanced.albumId>0||advanced.tagId>0)){
            final State origin=state;final long expected=unlockEpoch();final int kind=advanced.albumId>0?0:1;final long id=kind==0?advanced.albumId:advanced.tagId;
            origin.database.execute(()->{try{PersonalLibrary.Group group=origin.library.personal.group(kind,id);runOnUiThread(()->{if(state!=origin||generation!=collectionTitleRevision||isDestroyed()||!state.unlocked||!biometricCurrent(expected))return;String name=group==null?(kind==0?"Álbum eliminado":"Etiqueta eliminada"):group.name;title.setText(name);title.setContentDescription("Elegir colección. "+name);});}catch(Exception ignored){}});
        }
    }
    private void showCollections(){
        if(channelsMode)return;GalleryUi.menu(this,"Tu biblioteca",new String[]{"Todo","Favoritos","Ver después","Continuar","Archivados","Historial","Álbumes y etiquetas","Filtros guardados"},n->{
            if(n==7){SavedCollectionsUi.list(this,state,this::applyCollection,false);return;}
            if(n==6){GalleryUi.menu(this,"Álbumes y etiquetas",new String[]{"Álbumes","Etiquetas"},this::choosePersonalGroup);return;}
            finishSelection();rememberViewport();boolean previousRecent=advanced.recent;advanced.albumId=advanced.tagId=0;advanced.recent=n==5;advanced.collection=n<4?n:0;advanced.archive=n==4?1:0;
            if(advanced.recent){advanced.sort=6;from=until=0;}else if(previousRecent){advanced.sort=0;from=until=0;}
            savePreferences();updateCollections();updateFilterLabel();refresh();
            if(advanced.recent){final State origin=state;origin.database.execute(()->{try{boolean offer=!origin.library.personal.historyEnabled()&&origin.library.count(currentRecentFilter())==0;runOnUiThread(()->{if(offer&&state==origin&&advanced.recent&&state.unlocked&&!isDestroyed())PersonalUi.historySettings(this,origin,this::refresh);});}catch(Exception ignored){}});}
        });
    }
    private static MediaFilter currentRecentFilter(){MediaFilter filter=new MediaFilter();filter.recent=true;return filter;}
    private void choosePersonalGroup(int kind){final State origin=state;PersonalUi.groups(this,origin,kind,group->{if(state!=origin)return;if(group==null){updateCollections();refresh();return;}MediaFilter f=currentFilter();if(f.recent){f.from=f.until=0;f.sort=0;}f.recent=false;f.collection=f.archive=0;f.albumId=kind==0?group.id:0;f.tagId=kind==1?group.id:0;applyCollection(f);});}
    private void applyCollection(MediaFilter filter){finishSelection();rememberViewport();handler.removeCallbacks(updateSearch);advanced=filter.copy();channelId=filter.channel;type=filter.type;offline=filter.availability==1;downloads=false;channelsMode=false;duplicates=filter.duplicates;from=filter.from;until=filter.until;search.setText(filter.query);handler.removeCallbacks(updateSearch);savePreferences();updateChrome();refresh();}
    private void showOverflow(){
        if(UiSource.isLocal(this)){
            List<String> options=new ArrayList<>(Arrays.asList("Filtrar y ordenar","Cuadrícula","Seleccionar archivos","Ajustes"));
            if(advanced.recent)options.add("Opciones del historial");
            GalleryUi.menu(this,"Archivos propios",options.toArray(new String[0]),n->{
                switch(n){case 0:showFilters();break;case 1:chooseLayout();break;case 2:beginSelection();break;
                    case 3:showSettings();break;case 4:PersonalUi.historySettings(this,state,this::refresh);break;default:break;}
            });
            return;
        }
        boolean running=SyncService.running(state.key);
        if(channelsMode){
            GalleryUi.menu(this,UiSource.isTato(this)?"Canales":"Chats",new String[]{running?"Pausar actualización":"Actualizar todos","Ajustes"},n->{if(n==0)toggleSync(0);else showSettings();});
            return;
        }
        String update=running?"Pausar actualización":channelId==0?"Actualizar":"Actualizar este chat";
        java.util.List<String> labels=new ArrayList<>(Arrays.asList("Filtrar y ordenar","Cuadrícula",update,"Estado de actualización","Seleccionar archivos","Ajustes"));if(advanced.recent)labels.add("Opciones del historial");
        labels.add(stackResends?"Mostrar reenvíos separados":"Apilar reenvíos");
        GalleryUi.menu(this,"Biblioteca",labels.toArray(new String[0]),n->{if(n==labels.size()-1){finishSelection();rememberViewport();stackResends=!stackResends;savePreferences();refresh();return;}switch(n){case 0:showFilters();break;case 1:chooseLayout();break;case 2:toggleSync(selectedChannel());break;case 3:if(channelId==0){navigate("channels");loadChannels();}else showSyncDetail(channelId);break;case 4:beginSelection();break;case 5:showSettings();break;case 6:PersonalUi.historySettings(this,state,this::refresh);break;default:break;}});
    }
    private void toggleSync(int channel){
        if(SyncService.running(state.key))SyncService.pause(this,state.key);else SyncService.start(this,state.key,channel);
        updateSyncControl();
    }
    private void beginSelection(){beginSelection(grid.getFirstVisiblePosition());}
    private void beginSelection(int position){
        if(selectionMode!=null)return;selectionMode=startActionMode(new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){ImageButton actions=GalleryUi.icon(MainActivity.this,R.drawable.ic_gallery_more,"Acciones de selección");actions.setOnClickListener(v->selectionActions());menu.add("Acciones").setActionView(actions).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);return true;}
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){return false;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){return false;}
            public void onDestroyActionMode(ActionMode mode){
                selected.clear();selectionMode=null;selectionBusy=false;
                if(stackResends)keepSelectionViewport(grid.getFirstVisiblePosition());
                if(adapter!=null)adapter.selection(selected);
                if(stackResends&&!isFinishing())refresh();
            }
        });if(selectionMode!=null){
            selectionMode.setTitle("Seleccionar archivos");
            if(stackResends){keepSelectionViewport(position);refresh();}
        }
    }
    private void keepSelectionViewport(int position){
        selectionViewport=null;
        if(adapter==null||channelsMode)return;
        MediaFilter visible=adapter.snapshot(),target=currentFilter();
        if(visible.stacked==target.stacked)return;
        visible.stacked=target.stacked;
        String key=target.key();
        if(!visible.key().equals(key))return;
        View tile=grid.getChildAt(position-grid.getFirstVisiblePosition());
        if(tile!=null)selectionViewport=new SelectionViewport(key,adapter.item(position),position,tile.getTop());
    }
    private void toggleSelection(Item item){
        if(selectionBusy)return;beginSelection();if(selectionMode==null)return;if(selected.containsKey(item.key()))selected.remove(item.key());else if(selected.size()<1000)selected.put(item.key(),item);else Toast.makeText(this,"Hasta 1.000 archivos por grupo",Toast.LENGTH_SHORT).show();selectionMode.setTitle(selected.size()+" seleccionados");adapter.selection(selected);
    }
    private void finishSelection(){if(selectionMode!=null)selectionMode.finish();}
    private void selectionActions(){
        if(selected.isEmpty()){Toast.makeText(this,"Toca los archivos que quieras seleccionar",Toast.LENGTH_SHORT).show();return;}if(selectionBusy)return;
        boolean archived=advanced.archive==1,local=UiSource.isLocal(this);
        String[] labels={"Descargar","Añadir a Favoritos","Ver después","Marcar como visto",archived?"Mostrar en Biblioteca":"Archivar","Quitar marcas",selected.size()==1?(local?"Abrir archivo":"Opciones del archivo"):"Cancelar selección","Álbumes","Etiquetas"};
        String[] actions={"download","favorite","later","viewed",archived?"unarchive":"archive"};
        GalleryUi.menu(this,selected.size()+" seleccionados",local?Arrays.copyOfRange(labels,1,labels.length):labels,choice->{int n=choice+(local?1:0);if(n<5)applySelected(actions[n]);else if(n==5)GalleryUi.menu(this,"Quitar marcas",new String[]{"Quitar de Favoritos","Quitar de Ver después","Marcar como no visto","Mostrar en Biblioteca"},k->applySelected(new String[]{"unfavorite","unlater","unviewed","unarchive"}[k]));else if(n==7||n==8){final State origin=state;PersonalUi.memberships(this,origin,new ArrayList<>(selected.values()),n==7?0:1,()->{if(state==origin){finishSelection();refresh();}});}else if(selected.size()==1){Item item=selected.values().iterator().next();finishSelection();if(local)open(item);else actions(item);}else finishSelection();});
    }
    private void applySelected(String action){
        if(selected.isEmpty()||selectionBusy)return;final State origin=state;final List<Item> batch=new ArrayList<>(selected.values());final long expected=unlockEpoch();final int context=personalContext;selectionBusy=true;
        origin.database.execute(()->{try{if(!origin.unlocked||origin.maintenance||!Profiles.current()||!getSystemService(android.os.UserManager.class).isUserForeground())throw new SecurityException();PersonalLibrary.Change change=action.equals("download")?null:origin.library.personal.change(batch,action);int applied=change==null?origin.library.enqueueSelection(batch):change.applied;
            runOnUiThread(()->{if(!personalCurrent(origin,expected,context))return;selectionBusy=false;finishSelection();boolean launched=!action.equals("download")||applied==0||DownloadService.wake(this,origin.key);if(change==null)Toast.makeText(this,launched?applied+" archivos añadidos a Descargas":"Cola guardada. Abre Descargas para continuar",Toast.LENGTH_SHORT).show();else undo.show(origin,change,"Aplicado a "+applied+" archivos",expanded?16:72,this::refresh);refresh();});
        }catch(Exception error){runOnUiThread(()->{if(!personalCurrent(origin,expected,context))return;selectionBusy=false;Toast.makeText(this,error instanceof IllegalStateException?error.getMessage():"No se pudo aplicar. Tu selección se conserva para reintentar.",Toast.LENGTH_LONG).show();});}});
    }
    private String syncLabel(Library.ChatStatus value){
        if(value==null)return "Pendiente de actualizar";if(value.state==1)return SyncService.currentChannel(state.key)==value.channel&&SyncService.running(state.key)?value.complete?"Buscando novedades":"Buscando historial antiguo":value.cursor>0?"Interrumpida · punto guardado":"Interrumpida";if(value.state==2)return value.cursor>0?"Pausada · punto guardado":"Pausada";if(value.state==3)return "Interrumpida por error";return value.scanning?"Novedades pendientes":value.complete?"Historial indexado":"Historial pendiente";
    }
    private void showSyncDetail(int channel){
        final State origin=state;origin.database.execute(()->{try{Library.ChatStatus value=origin.library.chatStatuses().get(channel);runOnUiThread(()->{if(state!=origin||isDestroyed()||!origin.unlocked||!biometricCurrent(unlockEpoch()))return;String name="Estado del chat";for(Source.Channel row:channelList)if(row.id==channel)name=row.title;String detail=value==null?"Todavía no hay una página confirmada.":java.text.NumberFormat.getIntegerInstance().format(value.count)+" elementos indexados\n"+syncLabel(value)+(value.oldest>0?"\nFecha más antigua indexada: "+date(value.oldest):"")+(value.boundary>0?"\nÚltimo lote confirmado, medios del "+date(value.boundary):"")+(value.updated>0?"\nÚltima actividad: "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new Date(value.updated)):"")+(value.cursor>0||value.scanning?"\n\nSe continuará desde el punto guardado.":"\n\nActualizar consultará los elementos disponibles.")+" No hay un total remoto confirmado para calcular un porcentaje.";String sourceMessage=SyncService.message(origin.key);if(sourceMessage!=null&&!sourceMessage.startsWith("Catálogo"))detail+="\n\n"+sourceMessage;AlertDialog dialog=new SecureDialogs.Builder(this).setTitle(name).setMessage(detail).setNegativeButton("Cerrar",null).setPositiveButton(SyncService.running(origin.key)?"Pausar actualización":"Actualizar este chat",(d,w)->{if(SyncService.running(origin.key))SyncService.pause(this,origin.key);else SyncService.start(this,origin.key,channel);}).create();trackPrivateDialog(dialog);dialog.show();});}catch(Exception error){runOnUiThread(()->Toast.makeText(this,"No se pudo consultar el estado. Reintenta.",Toast.LENGTH_SHORT).show());}});
    }
    private static String date(long seconds){return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(seconds*1000));}
    private void restoreViewerAnchor(int result,Intent data){
        if(result!=RESULT_OK||data==null||state==null||!state.key.equals(data.getStringExtra("source")))return;
        // The first visible item and its pixel offset were captured before opening the viewer.
        refresh();
    }
    private int selectedChannel(){return channelId;}
    private void savePreferences(){try{preferences.edit().putLong("chat-folder-id",chatFolderId).putBoolean("stack-resends",stackResends).putLong("albumId",advanced.albumId).putLong("tagId",advanced.tagId).putBoolean("recent",advanced.recent).putInt("archive",advanced.archive).putInt("collection",advanced.collection).putString("query",search==null?"":search.getText().toString()).putInt("channel",channelId).putInt("type",type).putInt("columns",columns).putBoolean("offline",offline).putBoolean("duplicates",duplicates).putLong("from",from).putLong("until",until).putInt("availability",advanced.availability).putInt("watched",advanced.watched).putInt("sort",advanced.sort).putInt("shape",advanced.shape).putInt("format",advanced.format).putLong("minBytes",advanced.minBytes).putLong("maxBytes",advanced.maxBytes).putLong("minSeconds",advanced.minSeconds).putLong("maxSeconds",advanced.maxSeconds).apply();}catch(RuntimeException failure){Toast.makeText(this,"No se pudieron guardar los filtros cifrados. Libera espacio y vuelve a intentarlo.",Toast.LENGTH_LONG).show();}}
    private void setChannels(List<Source.Channel> list){channelList=new ArrayList<>();channelList.add(new Source.Channel(0,(UiSource.isTato(this)?"Todos los canales":"Todos los chats")));channelList.addAll(list);boolean found=channelId==0;for(Source.Channel c:list)if(c.id==channelId)found=true;if(!found)channelList.add(new Source.Channel(channelId,UiSource.isTato(this)?"Canal sin información":"Chat sin información"));updateChannelLabel();if(channelsMode)renderChannels();}
    private View channelRow(Source.Channel channel,View recycled){
        LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(this);row.removeAllViews();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),dp(12),dp(8),dp(12));
        ImageView image=new ImageView(this);image.setImageResource(R.drawable.ic_gallery_channels);image.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));image.setPadding(dp(10),dp(10),dp(10),dp(10));image.setBackground(GalleryUi.background(color(R.color.gallery_surface_container),dp(12)));row.addView(image,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(dp(14),0,dp(8),0);TextView name=text(channel.title,16,FG);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);copy.addView(name);Library.ChatStatus progress=chatStatus.get(channel.id);copy.addView(text(progress==null?UiSource.label(this):java.text.NumberFormat.getIntegerInstance().format(progress.count)+" indexados · "+syncLabel(progress),12,MUTED));if(progress!=null&&progress.oldest>0)copy.addView(text("Desde "+date(progress.oldest),12,MUTED));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        if(channel.id==channelId){ImageView selected=new ImageView(this);selected.setImageResource(R.drawable.ic_gallery_check);selected.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));row.addView(selected,new LinearLayout.LayoutParams(dp(24),dp(24)));}
        boolean pinned=chatFolders.pins.contains(channel.id);
        if(pinned){
            TextView mark=text("Fijado",11,MUTED);
            mark.setPadding(0,0,dp(8),0);
            row.addView(mark);
        }
        row.setContentDescription(channel.title+", "+UiSource.label(this)+(pinned?", fijado":""));return row;
    }
    private List<Source.Channel> filteredChannels(String query,boolean includeAll){
        return chatFolders.filter(channelList,query,includeAll,includeAll?0:chatFolderId);
    }
    private void renderChannels(){
        List<Source.Channel> rows=filteredChannels(search.getText().toString(),false);
        channelView.setAdapter(new BaseAdapter(){public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return rows.get(p).id;}public View getView(int p,View recycled,ViewGroup parent){return channelRow(rows.get(p),recycled);}});
        channelView.setOnItemLongClickListener((p,v,position,id)->{showChatOptions(rows.get(position));return true;});
        channelView.setOnItemClickListener((p,v,position,id)->{channelId=rows.get(position).id;savePreferences();navigate("library");});
        grid.setVisibility(View.GONE);channelView.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
        empty.setText(search.getText().toString().trim().isEmpty()
            ?chatFolders.folders.containsKey(chatFolderId)?"Carpeta vacía. Elige Todos y mantén pulsado un chat para añadirlo."
                :"Tus canales aparecerán aquí\n\nActualiza la biblioteca para encontrarlos."
            :"No hay chats con ese nombre.\n\nPrueba otra búsqueda.");
        String message=SyncService.message(state.key);status.setText(rows.size()+(UiSource.isTato(this)?" canales · ":" chats · ")+UiSource.label(this)+(message==null||message.isEmpty()?"":"\n"+message));
    }

    private void showChatFolders(){
        State origin=state;
        long expected=unlockEpoch();
        int context=personalContext;
        List<ChatFolders.Folder> folders=new ArrayList<>(chatFolders.folders.values());
        ChatFolders.Folder currentFolder=chatFolders.folders.get(chatFolderId);
        List<String> labels=new ArrayList<>();
        labels.add("Todos");
        for(ChatFolders.Folder folder:folders)labels.add(folder.name);
        labels.add("Crear carpeta");
        if(currentFolder!=null){labels.add("Renombrar carpeta");labels.add("Eliminar carpeta");}
        GalleryUi.menu(this,"Carpetas de chats",labels.toArray(new String[0]),choice->{
            if(!personalCurrent(origin,expected,context))return;
            if(choice<=folders.size()){
                chatFolderId=choice==0?0:folders.get(choice-1).id;
                savePreferences();updateChannelLabel();renderChannels();
            }else if(choice==folders.size()+1)editChatFolder(null);
            else if(choice==folders.size()+2)editChatFolder(currentFolder);
            else {
                AlertDialog dialog=new SecureDialogs.Builder(this).setTitle("Eliminar carpeta")
                    .setMessage("Los chats y sus archivos quedan disponibles en Todos.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Eliminar",(d,w)->{
                        if(personalCurrent(origin,expected,context))changeChatFolders(()->{origin.library.chatFolders.delete(currentFolder.id);return 0L;},true);
                    }).create();
                trackPrivateDialog(dialog);dialog.show();
            }
        });
    }

    private void editChatFolder(ChatFolders.Folder folder){
        State origin=state;
        long expected=unlockEpoch();
        int context=personalContext;
        EditText name=new EditText(this);
        name.setSingleLine(true);name.setSaveEnabled(false);
        name.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        name.setHint("Nombre de la carpeta");
        if(folder!=null)name.setText(folder.name);
        LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),0,dp(20),0);box.addView(name,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new SecureDialogs.Builder(this).setTitle(folder==null?"Crear carpeta":"Renombrar carpeta")
            .setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{
                if(!personalCurrent(origin,expected,context))return;
                String proposed=name.getText().toString();
                changeChatFolders(()->origin.library.chatFolders.save(folder==null?0:folder.id,proposed),true);
            }).create();
        trackPrivateDialog(dialog);dialog.show();
    }

    private void showChatOptions(Source.Channel channel){
        State origin=state;
        long expected=unlockEpoch();
        int context=personalContext;
        boolean pinned=chatFolders.pins.contains(channel.id);
        GalleryUi.menu(this,channel.title,new String[]{pinned?"Desfijar":"Fijar","Carpetas","Estado de actualización"},choice->{
            if(!personalCurrent(origin,expected,context))return;
            if(choice==0)changeChatFolders(()->{origin.library.chatFolders.pin(channel.id,!pinned);return 0L;},false);
            else if(choice==2)showSyncDetail(channel.id);
            else {
                List<ChatFolders.Folder> folders=new ArrayList<>(chatFolders.folders.values());
                if(folders.isEmpty()){editChatFolder(null);return;}
                String[] labels=new String[folders.size()];
                for(int i=0;i<folders.size();i++){
                    ChatFolders.Folder folder=folders.get(i);
                    labels[i]=(folder.channels.contains(channel.id)?"Quitar de ":"Añadir a ")+folder.name;
                }
                GalleryUi.menu(this,"Carpetas",labels,index->{
                    if(!personalCurrent(origin,expected,context))return;
                    ChatFolders.Folder folder=folders.get(index);
                    changeChatFolders(()->{
                        origin.library.chatFolders.member(folder.id,channel.id,!folder.channels.contains(channel.id));
                        return 0L;
                    },false);
                });
            }
        });
    }

    private void changeChatFolders(java.util.concurrent.Callable<Long> change,boolean selectFolder){
        State origin=state;
        long expected=unlockEpoch();
        int context=personalContext;
        origin.database.execute(()->{
            if(!personalCurrent(origin,expected,context)||origin.maintenance)return;
            try{
                long folder=change.call();
                ChatFolders.Snapshot snapshot=origin.library.chatFolders.snapshot();
                runOnUiThread(()->{
                    if(!personalCurrent(origin,expected,context))return;
                    chatFolders=snapshot;
                    if(selectFolder){chatFolderId=folder;savePreferences();}
                    updateChannelLabel();if(channelsMode)renderChannels();
                });
            }catch(Exception error){
                runOnUiThread(()->{if(personalCurrent(origin,expected,context))Toast.makeText(this,error instanceof IllegalArgumentException?error.getMessage():"No se pudo guardar la organización de chats",Toast.LENGTH_LONG).show();});
            }
        });
    }
    private void showChannels(){
        Dialog dialog=new SecureDialogs.Dialog(this);dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(20),dp(20),dp(12));panel.setBackground(GalleryUi.background(color(R.color.gallery_surface_container),dp(24)));
        panel.addView(text("Elegir canal",22,FG));EditText query=new EditText(this);query.setSingleLine(true);query.setTextSize(16);query.setHint("Buscar canal");query.setContentDescription("Buscar canal");panel.addView(query,new LinearLayout.LayoutParams(-1,dp(56)));
        ListView choices=new ListView(this);choices.setDividerHeight(0);panel.addView(choices,new LinearLayout.LayoutParams(-1,dp(340)));
        Runnable bind=()->{List<Source.Channel> rows=filteredChannels(query.getText().toString(),true);choices.setAdapter(new BaseAdapter(){public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return rows.get(p).id;}public View getView(int p,View recycled,ViewGroup parent){return channelRow(rows.get(p),recycled);}});choices.setOnItemClickListener((p,v,position,id)->{finishSelection();channelId=rows.get(position).id;savePreferences();updateChannelLabel();dialog.dismiss();refresh();});};
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){bind.run();}public void afterTextChanged(Editable e){}});bind.run();Button close=button("Cerrar");close.setOnClickListener(v->dialog.dismiss());panel.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));dialog.setContentView(panel);dialog.show();dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,-2);
    }
    private void showSettings(){
        settingsDialog=new SecureDialogs.Dialog(this,R.style.GalleryLibraryTheme);settingsDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(12),dp(16),dp(24));page.setBackgroundColor(color(R.color.gallery_surface));
        LinearLayout bar=row();ImageButton back=GalleryUi.icon(this,R.drawable.ic_gallery_back,"Volver");back.setOnClickListener(v->settingsDialog.dismiss());bar.addView(back);TextView h=text("Ajustes",26,FG);h.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));bar.addView(h);page.addView(bar);
        settingsSection(page,"Fuentes");settingsRow(page,"Fuente actual",UiSource.label(this),()->{settingsDialog.dismiss();UiSource.choose(this,this::sourceChanged);});
        if(!UiSource.isLocal(this)){
            settingsRow(page,UiSource.isTato(this)?"Abrir Conector Tato":"Abrir Conector TG","Sesión independiente",this::openConnector);
            if(!UiSource.isTato(this)||Profiles.owner())settingsRow(page,"Autorizar conexión","Elegir y vincular contenido de esta fuente",this::authorizeConnector);
        }
        settingsSection(page,"Privacidad");settingsRow(page,"Protección del PIN","Bloquea la interfaz. Las tareas automáticas conservan acceso a las claves cifradas del dispositivo.",()->new SecureDialogs.Builder(this).setTitle("Protección local").setMessage("Los archivos y el catálogo se guardan cifrados. El PIN y la huella protegen la interfaz. Para reanudar tareas automáticamente, la clave del almacén puede usarse sin introducir el PIN; esto limita la protección si alguien compromete el sistema o el proceso de la aplicación. Tus cuentas no necesitan autorizarse otra vez al desbloquear.").setPositiveButton("Entendido",null).show());settingsSection(page,"Biblioteca");if(!UiSource.isLocal(this))settingsRow(page,"Descargas","Progreso, cola y archivos guardados",()->startActivity(sourceIntent(DownloadsActivity.class)));settingsRow(page,"Almacenamiento","Caché temporal y archivos guardados",this::storage);
        settingsSection(page,"Privacidad y recuperación");settingsRow(page,"Huella y biometría","Opcional, con PIN de respaldo",()->{settingsDialog.dismiss();BiometricUnlock.showSettings(this);});settingsRow(page,"Respaldo cifrado","Exportar o restaurar tu biblioteca",()->startActivity(sourceIntent(RecoveryActivity.class)));settingsRow(page,"Bloquear biblioteca","Pedir PIN o huella al volver",()->{settingsDialog.dismiss();lock();});settingsRow(page,"Ayuda y privacidad","Cómo se guarda y protege el contenido",this::help);
        TextView version=text("Tato Galería · "+BuildConfig.VERSION_NAME,12,MUTED);version.setPadding(dp(12),dp(28),0,0);page.addView(version);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);settingsDialog.setContentView(scroll);settingsDialog.show();settingsDialog.getWindow().setLayout(-1,-1);
        settingsDialog.getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    }
    private void settingsSection(LinearLayout page,String value){TextView section=text(value,14,ACCENT);section.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));section.setPadding(dp(12),dp(28),dp(12),dp(8));page.addView(section);}
    private void settingsRow(LinearLayout page,String name,String detail,Runnable action){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setMinimumHeight(dp(64));row.setClickable(true);row.setFocusable(true);row.setBackgroundResource(R.drawable.gallery_control);row.addView(text(name,16,FG));TextView note=text(detail,13,MUTED);note.setPadding(0,dp(3),0,0);row.addView(note);row.setContentDescription(name+". "+detail);row.setOnClickListener(v->action.run());page.addView(row,new LinearLayout.LayoutParams(-1,-2));}
    private String authorizationSource;
    private void openConnector(){try{String pkg=UiSource.isTato(this)?"org.im.ttalk.lab":TelegramSource.PACKAGE;Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null||!Profiles.current())throw new android.content.ActivityNotFoundException();startActivity(launch);}catch(android.content.ActivityNotFoundException|SecurityException unavailable){Toast.makeText(this,"Instala y abre Conector "+(UiSource.isTato(this)?"Tato":"TG")+" en este mismo perfil",Toast.LENGTH_LONG).show();}}
    private void authorizeConnector(){if(state==null||!state.unlocked||(UiSource.isTato(this)&&!Profiles.owner()))return;authorizationSource=state.key;try{boolean tato=UiSource.isTato(this);Intent intent=new Intent().setComponent(new ComponentName(tato?"org.im.ttalk.lab":TelegramSource.PACKAGE,tato?"local.ttlab.lab.GalleryAuthorizeActivity":"local.ttlab.telegram.AuthorizeActivity"));if(!tato)intent.setAction("local.ttlab.telegram.AUTHORIZE");startActivityForResult(intent,73);}catch(android.content.ActivityNotFoundException|SecurityException unavailable){authorizationSource=null;Toast.makeText(this,"No se pudo abrir la autorización. Instala y abre el conector en este mismo perfil.",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==74){restoreViewerAnchor(result,data);return;}if(request!=73)return;String source=authorizationSource;authorizationSource=null;if(result!=RESULT_OK||source==null||state==null||!source.equals(state.key))return;
        if(state.unlocked){SyncService.start(this,state.key,selectedChannel());updateSyncControl();}
        else if(status!=null)status.setText("Conexión autorizada. Desbloquea la biblioteca y pulsa Actualizar.");
    }
    private String ownerSteps(){return "Actualiza cuando quieras revisar novedades";}
    private String ownerSetup(){
        if(UiSource.isLocal(this))return "Todavía no hay archivos propios.\n\nPulsa Importar para elegir fotos y videos del teléfono.";
        return UiSource.isTato(this)?"Tu biblioteca empieza aquí\n\nAbre el conector y autoriza tu cuenta desde Ajustes.":"Conecta Telegram\n\nAbre Conector TG desde Ajustes, inicia sesión y autoriza los chats que quieras ver aquí. Después pulsa Actualizar.";
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(title==null)return;String incoming=intent.getStringExtra("source");if(incoming!=null&&!incoming.equals(state.key)){getSharedPreferences("gallery-appearance",MODE_PRIVATE).edit().putString("source",SourceId.require(incoming)).apply();sourceChanged();}navigate("channels".equals(intent.getStringExtra("section"))?"channels":intent.getBooleanExtra("downloads",false)?"downloads":"library");}
    @Override protected void onResume(){super.onResume();if(adapter!=null)adapter.resume();if(state!=null&&state.unlocked){if(!loaded)load();else{reloadChatFolders();updateChrome();refresh();}}}
    @Override protected void onStart(){super.onStart();if(state!=null){IntentFilter filter=new IntentFilter(DownloadService.CHANGED);filter.addAction(SyncService.CHANGED);int flags=Build.VERSION.SDK_INT>=33?Context.RECEIVER_NOT_EXPORTED:0;registerReceiver(changes,filter,"local.ttlab.permission.GALLERY",null,flags);listening=true;}}
    @Override protected void onStop(){if(listening){unregisterReceiver(changes);listening=false;}super.onStop();}
    @Override void onUnlocked(){if(!loaded)load();else{loadChannels();refresh();}}
    private void load(){loaded=true;loadChannels();refresh();}
    private void reloadChatFolders(){
        State origin=state;
        long expected=unlockEpoch();
        int context=personalContext;
        origin.database.execute(()->{
            try{
                ChatFolders.Snapshot snapshot=origin.library.chatFolders.snapshot();
                runOnUiThread(()->{
                    if(!personalCurrent(origin,expected,context))return;
                    chatFolders=snapshot;
                    if(chatFolderId!=0&&!snapshot.folders.containsKey(chatFolderId))chatFolderId=0;
                    updateChannelLabel();if(channelsMode)renderChannels();
                });
            }catch(RuntimeException unavailable){ /* Keep the existing chat list usable. */ }
        });
    }
    private void loadChannels(){if(state==null||!state.unlocked)return;final State origin=state;origin.database.execute(()->{try{List<Source.Channel> cached=origin.library.channels();Map<Integer,Library.ChatStatus> progress=origin.library.chatStatuses();ChatFolders.Snapshot organization=origin.library.chatFolders.snapshot();runOnUiThread(()->{if(state==origin&&!isFinishing()&&!isDestroyed()){chatStatus=progress;chatFolders=organization;setChannels(cached);}});}catch(Exception error){runOnUiThread(()->{if(state==origin&&!isDestroyed())status.setText("No se pudieron leer los canales. Prueba Actualizar.");});}});}
    private void updateSyncControl(){if(status!=null&&SyncService.running(state.key))status.setText("Actualizando…");}
    private void updateFilterLabel(){if(overflow!=null){int count=currentFilter().active();overflow.setContentDescription("Más opciones"+(count>0?", "+count+" filtros activos":""));overflow.setImageTintList(android.content.res.ColorStateList.valueOf(count>0?ACCENT:FG));}}
    private void chooseLayout(){new SecureDialogs.Builder(this).setTitle("Cuadrícula").setSingleChoiceItems(new String[]{"3 columnas · Cómoda","4 columnas · Equilibrada","6 columnas · Compacta"},columns==3?0:columns==4?1:2,(d,which)->{columns=new int[]{3,4,6}[which];grid.setNumColumns(columns);savePreferences();d.dismiss();}).setNegativeButton("Cerrar",null).show();}
    private MediaFilter currentFilter(){MediaFilter f=advanced.copy();f.channel=selectedChannel();f.query=search==null?"":search.getText().toString();f.type=type;f.availability=downloads||offline?1:advanced.availability;f.from=from;f.until=until;f.duplicates=duplicates;f.stacked=stackResends&&selectionMode==null;return f;}
    private void showFilters(){finishSelection();MediaFilterDialog.show(this,currentFilter(),downloads,f->{type=f.type;offline=f.availability==1;duplicates=f.duplicates;from=f.from;until=f.until;advanced=f.copy();savePreferences();updateFilterLabel();updateCollections();refresh();},f->SavedCollectionsUi.name(this,state,0,"",f));}
    private void rememberViewport(){
        if(state==null||state.maintenance||grid==null||adapter==null||channelsMode||filterKey.isEmpty())return;
        viewportKey=filterKey;viewportPosition=grid.getFirstVisiblePosition();viewportTop=grid.getChildCount()>0?grid.getChildAt(0).getTop():0;viewportAnchor=adapter.item(viewportPosition);restoreViewport=true;
        final String key=viewportKey;final int position=viewportPosition,top=viewportTop;final Item anchor=viewportAnchor;
        final State origin=state;origin.database.execute(()->{try{if(!origin.maintenance)origin.library.saveViewport(key,anchor,position,top);}catch(Exception ignored){}});
    }
    @Override void lock(){finishSelection();super.lock();}
    @Override void onCovered(){personalContext++;collectionTitleRevision++;undo.clear();}
    @Override protected void onPause(){undo.clear();if(adapter!=null)adapter.suspend();finishSelection();rememberViewport();if(preferences!=null)savePreferences();super.onPause();}
    private void finishRefresh(){refreshInFlight=false;if(refreshAgain&&!isFinishing()&&!isDestroyed()){refreshAgain=false;handler.post(updateSearch);}}
    void refresh(){
        if(state==null||state.maintenance||!state.unlocked||search==null)return;
        if(sponsored!=null)sponsored.bind(state,channelsMode||downloads?0:channelId);
        if(channelsMode){selectionViewport=null;renderChannels();return;}
        if(refreshInFlight){refreshAgain=true;return;}refreshInFlight=true;
        final State origin=state;final int current=++revision;final MediaFilter f=currentFilter();final String key=f.key();
        if(selectionViewport!=null&&!key.equals(selectionViewport.key))selectionViewport=null;
        final SelectionViewport transition=selectionViewport;
        final boolean preserve=adapter!=null&&key.equals(filterKey),remembered=transition==null&&restoreViewport&&key.equals(viewportKey);
        final int anchorIndex=transition!=null?transition.position:remembered?viewportPosition:preserve?grid.getFirstVisiblePosition():0;
        final int anchorTop=transition!=null?transition.top:remembered?viewportTop:preserve&&grid.getChildCount()>0?grid.getChildAt(0).getTop():0;
        final Item anchor=transition!=null?transition.item:remembered?viewportAnchor:preserve?adapter.item(anchorIndex):null;
        final boolean needsStored=transition==null&&!remembered&&!preserve;
        origin.database.execute(()->{try{
            if(origin.maintenance)throw new IllegalStateException();final DateSections months=origin.library.dateSections(f);int count=f.sort<=1?months.total:origin.library.count(f);final int ranked=anchor==null?-1:origin.library.position(anchor,f,transition!=null&&f.stacked);final Library.Viewport stored=needsStored?origin.library.viewport(f):null;
            runOnUiThread(()->{if(state!=origin||current!=revision)return;finishRefresh();if(isFinishing()||isDestroyed()||!state.unlocked||!UiSource.available(this)||channelsMode||!key.equals(currentFilter().key()))return;
                if(transition!=selectionViewport){refresh();return;}
                selectionViewport=null;
                boolean anchored=transition!=null||preserve||remembered;
                int position=anchored?(ranked>=0?ranked:anchorIndex):stored==null?0:stored.position,top=anchored?anchorTop:stored==null?0:stored.top;
                if(transition==null&&preserve&&!remembered&&grid.getFirstVisiblePosition()!=anchorIndex){position=grid.getFirstVisiblePosition();top=grid.getChildCount()>0?grid.getChildAt(0).getTop():0;}
                if(adapter==null&&restorePosition>0){position=restorePosition;top=restoreTop;restorePosition=0;}
                if(transition!=null&&top>0&&grid.getChildCount()>0){
                    // Restore the first visible row even when the pressed tile was farther down.
                    int columns=Math.max(1,grid.getNumColumns());
                    int rowHeight=Math.max(1,grid.getChildAt(0).getHeight()+grid.getVerticalSpacing());
                    int rows=Math.min(position/columns,(top-1)/rowHeight+1);
                    position-=rows*columns;
                    top=Math.min(0,top-rows*rowHeight);
                }
                if(preserve){adapter.sections(months);adapter.refreshCount(count);}else{if(adapter!=null)adapter.close();adapter=new PagedAdapter(this,origin,f,count);adapter.sections(months);adapter.selection(selected);grid.setAdapter(adapter);}filterKey=key;grid.setDateNavigationEnabled(f.sort<=1&&count>0);
                channelView.setVisibility(View.GONE);grid.setVisibility(count>0?View.VISIBLE:View.GONE);empty.setVisibility(count>0?View.GONE:View.VISIBLE);if(count>0)GalleryUi.restoreGrid(grid,Math.min(position,count-1),top);
                // Keep an explicit return anchor until layout applies it; a faster refresh otherwise captures the old row.
                if(remembered&&count>0){final Item expected=viewportAnchor;final int expectedPosition=viewportPosition;grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(viewportAnchor==expected&&viewportPosition==expectedPosition&&viewportKey.equals(key)&&grid.getCount()>expectedPosition&&(grid.getFirstVisiblePosition()>expectedPosition||grid.getLastVisiblePosition()<expectedPosition))return true;if(grid.getViewTreeObserver().isAlive())grid.getViewTreeObserver().removeOnPreDrawListener(this);if(viewportAnchor==expected&&viewportPosition==expectedPosition&&viewportKey.equals(key))restoreViewport=false;return true;}});}else restoreViewport=false;
                boolean restricted=!f.query.trim().isEmpty()||f.active()>(downloads?1:0);
                empty.setText(count==0?(restricted?"No hay archivos con estos filtros.\n\nCambia la búsqueda o restablece los filtros.":f.availability==1?"Todavía no hay descargas aquí.\n\nAbre Descargas para ver el progreso o guarda un archivo desde Biblioteca.":(!UiSource.isTato(this)||Profiles.owner())?ownerSetup():"Tu biblioteca, a tu ritmo\n\nPulsa Actualizar para consultar el catálogo.\nLos originales se abren bajo demanda."):"");
                updateSyncControl();if(!SyncService.running(state.key)){String message=SyncService.message(state.key);boolean normal=message==null||message.equals("Catálogo local · originales bajo demanda")||message.startsWith("Catálogo actualizado");String summary=normal?java.text.NumberFormat.getIntegerInstance().format(count)+" archivos"+(f.availability==1?" · guardados":""):message;status.setText(summary);status.setContentDescription(summary);}
            });
        }catch(Exception error){runOnUiThread(()->{if(state!=origin||current!=revision)return;finishRefresh();if(!channelsMode&&!isDestroyed()&&key.equals(currentFilter().key()))status.setText("No se pudo buscar. Cambia los filtros o vuelve a intentarlo.");});}});
    }
    private void open(Item item){rememberViewport();if(adapter!=null)adapter.suspend();MediaFilter visible=adapter==null?currentFilter():adapter.snapshot();startActivityForResult(sourceIntent(visible.stacked&&item.stackCount>1?StackActivity.class:ViewerActivity.class).putExtra("channel",item.channel).putExtra("id",item.id).putExtra("revision",item.revision).putExtra("viewer-filter",visible.bundle()),74);}
    private boolean personalCurrent(State origin,long expected,int context){return state==origin&&context==personalContext&&state.unlocked&&!isFinishing()&&!isDestroyed()&&biometricCurrent(expected);}
    private void actions(Item item){
        final State origin=state;final long expected=unlockEpoch();final int context=personalContext;origin.database.execute(()->{boolean saved=origin.library.offline(item);Library.Flags marks=origin.library.flags(item);runOnUiThread(()->{if(!personalCurrent(origin,expected,context))return;new SecureDialogs.Builder(this).setTitle(item.label()).setItems(new String[]{"Abrir",saved?"Quitar descarga":"Guardar sin conexión",UiSource.isTato(this)?"Buscar similares":"Información de Visual","Activar filtro de duplicados",marks.favorite?"Quitar de Favoritos":"Añadir a Favoritos",marks.later?"Quitar de Ver después":"Ver después","Álbumes","Etiquetas"},(dialog,choice)->{
            if(choice==6||choice==7){PersonalUi.memberships(this,origin,Collections.singletonList(item),choice==6?0:1,this::refresh);}else if(choice==4||choice==5){origin.database.execute(()->{try{PersonalLibrary.Change change=origin.library.personal.change(Collections.singletonList(item),choice==4?(marks.favorite?"unfavorite":"favorite"):(marks.later?"unlater":"later"));runOnUiThread(()->{if(personalCurrent(origin,expected,context)){undo.show(origin,change,"Marca guardada",expanded?16:72,this::refresh);refresh();}});}catch(Exception failed){runOnUiThread(()->Toast.makeText(this,"No se pudo guardar la marca",Toast.LENGTH_SHORT).show());}});}else if(choice==0)open(item);else if(choice==2)startActivity(sourceIntent(VisualActivity.class).putExtra("channel",item.channel).putExtra("id",item.id));else if(choice==3){duplicates=true;savePreferences();updateFilterLabel();refresh();}else if(saved){new SecureDialogs.Builder(this).setTitle("¿Quitar descarga?").setMessage("Se eliminará la copia sin conexión de este archivo.").setNegativeButton("Conservar",null).setPositiveButton("Quitar",(d,w)->origin.database.execute(()->{try{origin.media.removeDownload(item);origin.library.offline(item,false);runOnUiThread(this::refresh);}catch(Exception error){runOnUiThread(()->Toast.makeText(this,"No se pudo quitar la descarga",Toast.LENGTH_SHORT).show());}})).show();}else DownloadService.start(this,origin.key,item);
        }).show();});});
    }
    private void storage(){startActivity(sourceIntent(StorageActivity.class));}
    private void help(){String connection=UiSource.isLocal(this)?"La galería copia los archivos que eliges al almacenamiento cifrado. Los archivos originales se conservan en su ubicación.":!UiSource.isTato(this)?"Conector TG mantiene una sesión propia de Telegram. Desde Ajustes puedes iniciar sesión y autorizar únicamente los chats elegidos. Abrir un archivo intencionadamente informa su lectura o vista a Telegram. El análisis visual no se ejecuta sobre esta fuente.":Profiles.owner()?"Conector Tato es una instalación independiente; usa la cuenta autorizada en esta instalación. No es la aplicación original. Desde Ajustes, abre el conector, inicia sesión manualmente y vuelve para Autorizar conexión.":"Esta instalación usa TestTes dentro del perfil de laboratorio autorizado; no necesita la autorización del usuario principal.";
        new SecureDialogs.Builder(this).setTitle("Solo en tus dispositivos").setMessage(connection+" La galería no tiene permiso de Internet.\n\nEl catálogo, las miniaturas y las descargas están cifrados. El análisis visual se hace en el dispositivo, a partir de miniaturas disponibles. No usa analítica ni servicios en la nube. Bloquea la interfaz al salir o apagar la pantalla. Las tareas en segundo plano conservan acceso al almacén cifrado.\n\nBusca palabras del nombre o filtra por tipo y fecha. Toca para abrir; mantén pulsado para seleccionar varios. Guarda tus consultas desde Filtrar y ordenar. Encuéntralas en Filtros guardados, en el selector de Biblioteca. Las similitudes son aproximadas; los duplicados exactos usan la identidad del contenido del catálogo.\n\nConserva un respaldo cifrado si necesitas recuperar tu biblioteca. No hay recuperación del PIN en la nube.\n\nComponentes abiertos: AOSP (Apache 2.0), SQLCipher (licencia BSD). Esta app no está afiliada a Tato ni a Ente.").setPositiveButton("Entendido",null).show();}
    @Override protected void onSaveInstanceState(Bundle out){rememberViewport();super.onSaveInstanceState(out);out.putString("authorization-source",authorizationSource);if(search==null)return;out.remove("query");out.putBoolean("downloads",downloads);out.putBoolean("channels",channelsMode);out.putInt("position",grid.getFirstVisiblePosition());out.putInt("top",grid.getChildCount()>0?grid.getChildAt(0).getTop():0);}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);recreate();}
    @Override protected void onDestroy(){revision++;handler.removeCallbacksAndMessages(null);if(sponsored!=null)sponsored.close();if(settingsDialog!=null)settingsDialog.dismiss();if(adapter!=null)adapter.close();super.onDestroy();}
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private Button button(String label){return GalleryUi.button(this,label,false);}
    private int color(int resource){return getColor(resource);}
    private android.graphics.drawable.Drawable icon(int resource){android.graphics.drawable.Drawable d=getDrawable(resource).mutate();d.setTint(MUTED);d.setBounds(0,0,dp(20),dp(20));return d;}
}
