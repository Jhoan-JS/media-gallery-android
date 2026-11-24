package local.ttlab.gallery;

import android.content.*;import android.database.Cursor;import android.os.Bundle;import android.view.*;import android.widget.*;import java.util.*;

/** A bounded source-local list of confirmed references; originals still use the existing viewer. */
public final class StackActivity extends SecureActivity {
 private static final int PAGE=48;
 private TextView summary,pageLabel;private ListView list;private Button previous,next;private final Rows adapter=new Rows();
 private MediaFilter filter;private int channel,id,page;private String revision;private volatile long request;private volatile boolean resumed;
 @Override public void onCreate(Bundle saved){
  setTheme(R.style.GalleryLibraryTheme);super.onCreate(saved);if(state==null)return;
  try{filter=MediaFilter.from(getIntent().getBundleExtra("viewer-filter"));filter.stacked=false;channel=getIntent().getIntExtra("channel",0);id=getIntent().getIntExtra("id",0);revision=getIntent().getStringExtra("revision");if(channel<=0||id<=0||revision==null)throw new IllegalArgumentException();}
  catch(IllegalArgumentException invalid){finish();return;}
  page=saved==null?0:Math.max(0,saved.getInt("stack-page",0));
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.gallery_surface));
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(8),dp(8),dp(16),dp(4));Button back=GalleryUi.button(this,"Volver",false);back.setOnClickListener(v->finish());top.addView(back);
  TextView title=text("Reenvíos · "+SourceId.label(state.key),24,getColor(R.color.gallery_foreground));title.setAccessibilityHeading(true);title.setPadding(dp(12),0,0,0);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
  summary=text("Consultando referencias cifradas…",14,getColor(R.color.gallery_muted));summary.setPadding(dp(20),dp(12),dp(20),dp(12));root.addView(summary);
  list=new ListView(this);list.setDividerHeight(0);list.setPadding(dp(16),0,dp(16),0);list.setClipToPadding(false);list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  list.setOnItemClickListener((parent,view,position,rowId)->{if(!state.unlocked||position<0||position>=adapter.rows.size())return;Item row=adapter.rows.get(position).item;startActivity(sourceIntent(ViewerActivity.class).putExtra("channel",row.channel).putExtra("id",row.id).putExtra("viewer-filter",filter.bundle()));});
  LinearLayout pages=new LinearLayout(this);pages.setGravity(Gravity.CENTER_VERTICAL);pages.setPadding(dp(16),dp(8),dp(16),dp(12));previous=GalleryUi.button(this,"Anterior",false);previous.setEnabled(false);previous.setOnClickListener(v->{if(page>0){page--;refresh(true);}});pages.addView(previous);pageLabel=text("",12,getColor(R.color.gallery_muted));pageLabel.setGravity(Gravity.CENTER);pages.addView(pageLabel,new LinearLayout.LayoutParams(0,-2,1));next=GalleryUi.button(this,"Siguiente",false);next.setEnabled(false);next.setOnClickListener(v->{page++;refresh(true);});pages.addView(next);root.addView(pages);content(root);
 }
 @Override protected void onResume(){super.onResume();resumed=true;refresh();}
 @Override protected void onPause(){resumed=false;request++;super.onPause();}
 @Override void onCovered(){request++;}
 @Override void onUnlocked(){refresh();}
 private boolean current(State origin,long epoch,long expected){return resumed&&state==origin&&origin.unlocked&&!isFinishing()&&!isDestroyed()&&request==epoch&&biometricCurrent(expected);}
 private void refresh(){refresh(false);}
 private void refresh(boolean reset){
  if(!resumed||state==null||!state.unlocked||list==null||filter==null)return;
  State origin=state;long epoch=++request,expected=unlockEpoch();MediaFilter snapshot=filter.copy();int wanted=page,first=reset?0:list.getFirstVisiblePosition(),top=reset||list.getChildCount()==0?0:list.getChildAt(0).getTop();previous.setEnabled(false);next.setEnabled(false);
  origin.database.execute(()->{
   if(!current(origin,epoch,expected))return;
   try{
    if(snapshot.stackKey.isEmpty()){Item selected=origin.library.get(channel,id);if(selected==null||!revision.equals(selected.revision)||!selected.contentKey.matches("[0-9a-f]{64}"))throw new IllegalStateException();snapshot.stackKey=selected.contentKey;snapshot.stackMime=selected.mime;snapshot.stackSize=selected.size;snapshot.check();}
    int count=origin.library.count(snapshot),safe=Math.max(0,Math.min(wanted,Math.max(0,(count-1)/PAGE)));List<Row> rows=new ArrayList<>();
    for(Item item:origin.library.page(snapshot,safe*PAGE,PAGE)){String chat="Chat sin información";try(Cursor c=origin.library.db.rawQuery("SELECT title FROM channels WHERE id=?",new String[]{""+item.channel})){if(c.moveToFirst())chat=c.getString(0);}rows.add(new Row(item,chat));}
    runOnUiThread(()->{if(!current(origin,epoch,expected))return;filter=snapshot;getIntent().putExtra("viewer-filter",snapshot.bundle());page=safe;adapter.rows=rows;adapter.notifyDataSetChanged();if(!rows.isEmpty())list.setSelectionFromTop(Math.min(first,rows.size()-1),top);summary.setText(count==0?"No quedan referencias en estos filtros.":count+" reenvíos confirmados en esta vista. Sus marcas y descargas se conservan por separado.");pageLabel.setText(count==0?"Sin archivos":(page+1)+" / "+((count+PAGE-1)/PAGE));previous.setEnabled(page>0);next.setEnabled((page+1)*PAGE<count);});
   }catch(Exception unavailable){runOnUiThread(()->{if(!current(origin,epoch,expected))return;adapter.rows=Collections.emptyList();adapter.notifyDataSetChanged();summary.setText("La pila cambió o no pudo consultarse. Vuelve a Biblioteca para actualizarla.");pageLabel.setText("");});}
  });
 }
 @Override protected void onSaveInstanceState(Bundle out){out.putInt("stack-page",page);super.onSaveInstanceState(out);}
 private static final class Row {final Item item;final String chat;Row(Item item,String chat){this.item=item;this.chat=chat;}}
 private final class Rows extends BaseAdapter {
  List<Row> rows=Collections.emptyList();public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p).item;}public long getItemId(int p){Item i=rows.get(p).item;return ((long)i.channel<<32)|(i.id&0xffffffffL);}
  public View getView(int p,View recycled,ViewGroup parent){Row row=rows.get(p);LinearLayout box=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(StackActivity.this);box.removeAllViews();box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(4),dp(16),dp(4),dp(16));TextView name=text(row.item.label(),16,getColor(R.color.gallery_foreground));name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(name);String date=row.item.date>0&&row.item.date<=Long.MAX_VALUE/1000?java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(row.item.date*1000)):"Sin fecha";TextView detail=text(row.chat+" · "+date,13,getColor(R.color.gallery_muted));detail.setPadding(0,dp(6),0,0);detail.setMaxLines(2);box.addView(detail);box.setContentDescription(row.item.label()+", "+row.chat+", "+date);return box;}
 }
}
