package local.ttlab.gallery;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Searches the catalogues already on this device. Each result keeps its own source. */
public final class CombinedSearchActivity extends SecureActivity {
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<CombinedSearch.Key> anchors=new ArrayList<>();
    private final DateFormat dates=DateFormat.getDateInstance(DateFormat.MEDIUM);
    private final Runnable debounce=()->load(0);
    private List<CombinedSearch.Row> rows=Collections.emptyList();
    private PreviewRequests tatoPreviews,telegramPreviews;
    private Future<?> pending;
    private EditText search;
    private ListView results;
    private ResultRows adapter;
    private TextView status,pageNumber;
    private Button previous,next,retry;
    private LinearLayout pageBar;
    private int page,requested,generation;
    private boolean ready,loading;
    private volatile boolean visible;

    @Override protected void onCreate(Bundle saved){
        setTheme(R.style.GalleryLibraryTheme);
        super.onCreate(saved);
        if(state==null)return;
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.gallery_surface));
        root.setFocusableInTouchMode(true);

        LinearLayout top=line(8,4);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View back=GalleryUi.icon(this,R.drawable.ic_gallery_back,"Volver a Biblioteca");
        back.setOnClickListener(v->finish());top.addView(back);
        TextView title=text("Buscar en ambas",23,getColor(R.color.gallery_foreground));
        title.setAccessibilityHeading(true);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);

        LinearLayout searchBox=line(16,6);
        search=new EditText(this);
        search.setSingleLine(true);search.setTextSize(16);
        search.setTextColor(getColor(R.color.gallery_foreground));search.setHintTextColor(getColor(R.color.gallery_muted));
        search.setBackground(GalleryUi.background(getColor(R.color.gallery_surface_container),dp(12)));
        search.setPadding(dp(14),dp(9),dp(14),dp(9));search.setMinHeight(dp(48));
        search.setHint("Buscar por nombre");search.setContentDescription("Buscar nombres en Tato y Telegram");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);search.setSaveEnabled(false);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        search.setText(state.preferences.getString("query",""));
        searchBox.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        View clear=GalleryUi.icon(this,R.drawable.ic_gallery_close,"Borrar búsqueda");
        clear.setOnClickListener(v->search.setText(""));searchBox.addView(clear);root.addView(searchBox);
        status=text("",14,getColor(R.color.gallery_muted));
        status.setPadding(dp(20),dp(4),dp(20),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        retry=GalleryUi.button(this,"Reintentar",false);
        LinearLayout.LayoutParams retryLayout=new LinearLayout.LayoutParams(-2,-2);
        retryLayout.setMarginStart(dp(16));root.addView(retry,retryLayout);retry.setVisibility(View.GONE);
        retry.setOnClickListener(v->load(requested));

        results=new ListView(this);
        results.setPadding(dp(14),0,dp(14),0);results.setSaveEnabled(false);
        results.setDivider(new android.graphics.drawable.ColorDrawable(getColor(R.color.gallery_outline)));results.setDividerHeight(1);
        adapter=new ResultRows();results.setAdapter(adapter);results.setOnItemClickListener((parent,v,position,id)->open(rows.get(position)));
        root.addView(results,new LinearLayout.LayoutParams(-1,0,1));

        pageBar=line(16,8);pageBar.setGravity(Gravity.CENTER_VERTICAL);
        previous=GalleryUi.button(this,"Anterior",false);previous.setContentDescription("Página anterior");
        previous.setOnClickListener(v->load(page-1));pageBar.addView(previous);
        pageNumber=text("",14,getColor(R.color.gallery_muted));pageNumber.setGravity(Gravity.CENTER);
        pageBar.addView(pageNumber,new LinearLayout.LayoutParams(0,-2,1));
        next=GalleryUi.button(this,"Siguiente",false);next.setContentDescription("Página siguiente");
        next.setOnClickListener(v->load(page+1));pageBar.addView(next);
        pageBar.setVisibility(View.GONE);root.addView(pageBar);

        anchors.add(null);
        content(root);root.requestFocus();
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){resetSearch();}
            public void afterTextChanged(Editable text){}
        });
        search.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
            handler.removeCallbacks(debounce);load(0);
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(),0);
            return true;
        });
    }

    private LinearLayout line(int sides,int vertical){
        LinearLayout row=new LinearLayout(this);
        row.setPadding(dp(sides),dp(vertical),dp(sides),dp(vertical));
        return row;
    }
    @Override protected void onResume(){
        super.onResume();
        if(search!=null&&State.unlocked)onUnlocked();
    }
    @Override void onUnlocked(){
        visible=biometricCurrent(unlockEpoch());
        if(!visible)return;
        if(!ready){load(requested);return;}
        int first=results.getFirstVisiblePosition();
        int top=results.getChildCount()==0?0:results.getChildAt(0).getTop();
        adapter.notifyDataSetChanged();
        if(!rows.isEmpty())results.setSelectionFromTop(first,top);
    }
    private void resetSearch(){
        stopWork();
        ready=false;page=requested=0;anchors.clear();anchors.add(null);
        rows=Collections.emptyList();adapter.notifyDataSetChanged();pageBar.setVisibility(View.GONE);retry.setVisibility(View.GONE);
        status.setText(search.getText().toString().trim().isEmpty()?"Escribe un nombre para buscar en Tato y Telegram.":"Buscando en ambos catálogos…");
        handler.postDelayed(debounce,250);
    }
    private void load(int index){
        if(!visible||!State.unlocked||!biometricCurrent(unlockEpoch())||index<0||index>=anchors.size())return;
        stopWork();
        String query=search.getText().toString().trim();
        retry.setVisibility(View.GONE);
        if(query.isEmpty()){
            ready=true;status.setText("Escribe un nombre para buscar en Tato y Telegram.");return;
        }
        long epoch=unlockEpoch();
        int ticket=generation;
        requested=index;ready=false;loading=true;
        showNavigation();
        status.setText("Buscando en ambos catálogos…");
        CombinedSearch.Key after=anchors.get(index);
        pending=reader.submit(()->{
            String source="tato";
            try{
                State tato=State.get(getApplicationContext(),source);
                List<CombinedSearch.Row> left=tato.library.searchByName(source,query,after);
                if(Thread.currentThread().isInterrupted())return;
                source="telegram";
                State telegram=State.get(getApplicationContext(),source);
                List<CombinedSearch.Row> right=telegram.library.searchByName(source,query,after);
                CombinedSearch.Page found=CombinedSearch.merge(left,right);
                runOnUiThread(()->{
                    if(!current(ticket,epoch))return;
                    if(tatoPreviews==null){
                        tatoPreviews=previews(tato);telegramPreviews=previews(telegram);
                    }
                    pending=null;loading=false;ready=true;page=index;rows=found.rows;
                    anchors.subList(page+1,anchors.size()).clear();
                    if(found.next!=null)anchors.add(found.next);
                    adapter.notifyDataSetChanged();results.setSelection(0);showNavigation();
                    status.setText(rows.isEmpty()?(page==0?"Sin coincidencias en los catálogos de Tato y Telegram.":"Ya no hay resultados en esta página."):"Tato y Telegram · Archivos indexados");
                });
            }catch(Exception unavailable){
                String label=SourceId.label(source);
                runOnUiThread(()->{
                    if(!current(ticket,epoch))return;
                    pending=null;loading=false;ready=true;showNavigation();
                    status.setText("No se pudo consultar el catálogo de "+label+".");retry.setVisibility(View.VISIBLE);
                });
            }
        });
    }
    private PreviewRequests previews(State origin){
        return new PreviewRequests(origin.media,origin.gridPreviews,()->visible&&State.unlocked&&!State.maintenance,
            this::runOnUiThread,true);
    }
    private boolean current(int ticket,long epoch){
        return ticket==generation&&visible&&State.unlocked&&biometricCurrent(epoch);
    }
    private void showNavigation(){
        boolean hasPrevious=page>0,hasNext=page+1<anchors.size();
        pageBar.setVisibility(hasPrevious||hasNext?View.VISIBLE:View.GONE);
        pageNumber.setText(""+(page+1));
        pageNumber.setContentDescription("Página "+(page+1));
        previous.setEnabled(!loading&&hasPrevious);next.setEnabled(!loading&&hasNext);
    }
    private void open(CombinedSearch.Row row){
        if(loading||!visible||!State.unlocked||!biometricCurrent(unlockEpoch()))return;
        MediaFilter filter=new MediaFilter();filter.query=search.getText().toString().trim();
        Intent viewer=new Intent(this,ViewerActivity.class).putExtra("source",row.source)
            .putExtra("channel",row.item.channel).putExtra("id",row.item.id).putExtra("viewer-filter",filter.bundle());
        startActivity(viewer);
    }
    private void stopWork(){
        generation++;loading=false;handler.removeCallbacks(debounce);
        if(pending!=null){pending.cancel(true);pending=null;}
        if(tatoPreviews!=null){tatoPreviews.cancel();telegramPreviews.cancel();}
    }
    @Override void onCovered(){visible=false;stopWork();}
    @Override protected void onDestroy(){
        visible=false;stopWork();
        if(tatoPreviews!=null){tatoPreviews.close();telegramPreviews.close();}
        reader.shutdownNow();super.onDestroy();
    }

    private final class ResultRows extends BaseAdapter {
        @Override public int getCount(){return rows.size();}
        @Override public CombinedSearch.Row getItem(int position){return rows.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            ResultView cell=recycled instanceof ResultView?(ResultView)recycled:new ResultView();
            CombinedSearch.Row result=getItem(position);
            Item item=result.item;
            cell.bound=result;
            String source=SourceId.label(result.source);
            String detail=source+" · "+(result.chat.isEmpty()?"Chat sin nombre":result.chat);
            if(item.date>0&&item.date<=Long.MAX_VALUE/1000)detail+=" · "+dates.format(new Date(item.date*1000));
            cell.name.setText(item.label());cell.detail.setText(detail);
            cell.setContentDescription(item.label()+", "+(item.video()?"video. ":"imagen. ")+detail);
            cell.preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            cell.preview.setImageResource(item.video()?android.R.drawable.ic_media_play:R.drawable.ic_gallery_image);
            cell.preview.setColorFilter(getColor(R.color.gallery_muted));
            PreviewRequests requests="tato".equals(result.source)?tatoPreviews:telegramPreviews;
            if(requests!=null)requests.request(item,cell,bitmap->{if(cell.bound==result&&bitmap!=null)cell.show(bitmap);});
            return cell;
        }
    }
    private final class ResultView extends LinearLayout {
        final ImageView preview;
        final TextView name,detail;
        CombinedSearch.Row bound;
        ResultView(){
            super(CombinedSearchActivity.this);
            setPadding(dp(5),dp(9),dp(5),dp(9));setGravity(Gravity.CENTER_VERTICAL);
            preview=new ImageView(getContext());
            preview.setBackground(GalleryUi.background(getColor(R.color.gallery_surface_container),dp(6)));
            preview.setClipToOutline(true);
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(preview,new LinearLayout.LayoutParams(dp(56),dp(56)));
            LinearLayout copy=new LinearLayout(getContext());copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(12),0,0,0);
            name=text("",16,getColor(R.color.gallery_foreground));name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);copy.addView(name);
            detail=text("",13,getColor(R.color.gallery_muted));detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0,dp(3),0,0);copy.addView(detail);addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        }
        void show(Bitmap bitmap){
            preview.clearColorFilter();preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setImageBitmap(bitmap);
        }
    }
}
