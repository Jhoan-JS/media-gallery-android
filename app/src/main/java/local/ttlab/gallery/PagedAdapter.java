package local.ttlab.gallery;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

final class PagedAdapter extends BaseAdapter implements SectionIndexer {
    private DateSections sections=DateSections.EMPTY;private Map<String,Item> selected=Collections.emptyMap();
    private final Activity activity;private final State state;private int count,generation;private final MediaFilter filter;
    private final LinkedHashMap<Integer,List<Item>> pages=new LinkedHashMap<Integer,List<Item>>(4,0.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,List<Item>> e){return size()>4;}};
    private final Set<Integer> pending=new HashSet<>();private volatile boolean closed;private boolean paused;private final PreviewRequests previews;
    PagedAdapter(Activity a,State s,int c,String q,int t,boolean o,int n){this(a,s,c,q,t,o,0,0,false,n);}
    PagedAdapter(Activity a,State s,int c,String q,int t,boolean o,long f,long u,boolean d,int n){this(a,s,new MediaFilter(c,q,t,o,f,u,d),n);}
    PagedAdapter(Activity a,State s,MediaFilter f,int n){activity=a;state=s;filter=f.copy();count=n;previews=new PreviewRequests(s.media,s.gridPreviews,()->!closed&&!paused&&s.unlocked,a::runOnUiThread);}
    void refreshCount(int n){previews.cancel();generation++;pending.clear();if(n==count&&!pages.isEmpty()){for(int page:new ArrayList<>(pages.keySet()))load(page);}else{count=n;pages.clear();notifyDataSetChanged();}}
    MediaFilter snapshot(){return filter.copy();}
    void sections(DateSections index){sections=index;}
    void selection(Map<String,Item> values){selected=values;notifyDataSetChanged();}
    @Override public Object[] getSections(){return sections.labels.clone();}
    @Override public int getPositionForSection(int section){return sections.position(section);}
    @Override public int getSectionForPosition(int position){return sections.section(position);}
    @Override public int getCount(){return count;}
    Item item(int position){List<Item> page=pages.get(position/96);int offset=position%96;return page!=null&&offset<page.size()?page.get(offset):null;}
    @Override public Object getItem(int p){return item(p);}
    @Override public long getItemId(int p){Item item=item(p);return item==null?p:((long)item.channel<<32)|(item.id&0xffffffffL);}
    @Override public View getView(int position,View recycled,ViewGroup parent){
        Tile tile=recycled instanceof Tile?(Tile)recycled:new Tile(activity);
        int columns=Math.max(1,((GridView)parent).getNumColumns());
        tile.setLayoutParams(new AbsListView.LayoutParams(-1,Math.max(dp(48),(parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight()-dp(5)*(columns-1))/columns)));
        Item item=item(position);String bound=item==null?"pending-"+position:item.cacheKey();if(!bound.equals(tile.bound))tile.placeholder();tile.bound=bound;tile.setTag(tile.bound);tile.caption.setText(item==null?(pages.containsKey(position/96)?"No disponible":"Cargando…"):item.label());tile.caption.setVisibility(View.GONE);tile.video.setVisibility(item!=null&&item.video()?View.VISIBLE:View.GONE);tile.seen.setVisibility(item!=null&&item.video()&&item.viewed?View.VISIBLE:View.GONE);
        tile.stack.setText(item!=null&&item.stackCount>1?"×"+item.stackCount:"");tile.stack.setVisibility(item!=null&&item.stackCount>1?View.VISIBLE:View.GONE);
        boolean checked=item!=null&&selected.containsKey(item.key())&&selected.get(item.key()).revision.equals(item.revision);tile.setSelected(checked);tile.choice.setVisibility(checked?View.VISIBLE:View.GONE);tile.image.setAlpha(checked?.65f:1f);
        tile.setContentDescription(item==null?(pages.containsKey(position/96)?"No se pudo cargar el archivo. Pulsa Actualizar para reintentar.":"Cargando archivo"):item.label()+(item.video()?", video":", imagen")+(item.viewed?", visto":"")+(item.stackCount>1?", pila de "+item.stackCount+" reenvíos. Toca para ver sus archivos":""));
        tile.marks.setText(item!=null&&item.watchLater?"Ver después":"");android.graphics.drawable.Drawable star=item!=null&&item.favorite?activity.getDrawable(R.drawable.ic_gallery_star).mutate():null;if(star!=null){star.setBounds(0,0,dp(14),dp(14));star.setTint(activity.getColor(R.color.gallery_foreground));}tile.marks.setCompoundDrawablesRelative(star,null,null,null);tile.marks.setCompoundDrawablePadding(dp(3));tile.marks.setVisibility(item!=null&&(item.favorite||item.watchLater)?View.VISIBLE:View.GONE);
        boolean continuing=item!=null&&item.video()&&item.positionMs>0&&item.durationMs>0&&!item.completed;tile.progress.setVisibility(continuing?View.VISIBLE:View.GONE);tile.progress.setProgress(continuing?(int)Math.min(100,100L*item.positionMs/item.durationMs):0);
        if(item!=null)tile.setContentDescription(tile.getContentDescription()+(item.favorite?", favorito":"")+(item.watchLater?", ver después":"")+(continuing?", en progreso":""));
        if(item==null){if(!pages.containsKey(position/96))load(position/96);else tile.caption.setVisibility(View.VISIBLE);return tile;}
        Bitmap cached=state.media.memoryPreview(item);if(cached!=null)tile.preview(cached);else previews.request(item,tile,preview->{if(!closed&&!paused&&item.cacheKey().equals(tile.getTag())){if(preview!=null)tile.preview(preview);else{tile.caption.setText(item.label()+"\nSin miniatura");tile.caption.setVisibility(View.VISIBLE);tile.setContentDescription(item.label()+", sin miniatura"+(item.stackCount>1?", pila de "+item.stackCount+" reenvíos":"")+". Toca para abrir.");}}});return tile;
    }
    private void load(int number){
        if(closed||pending.contains(number)||pending.size()>=4)return;pending.add(number);final int requested=generation;
        state.database.execute(()->{List<Item> result;
            try{if(closed||requested!=generation)return;result=state.library.page(filter,number*96,96);}catch(Exception error){result=Collections.emptyList();}
            final List<Item> found=result;activity.runOnUiThread(()->{if(requested!=generation)return;pending.remove(number);if(!closed){pages.put(number,found);notifyDataSetChanged();}});
        });
    }
    void viewport(int first,int visible,boolean idle){
        if(closed||paused)return;Set<String> retained=new HashSet<>();for(int n=Math.max(0,first);n<Math.min(count,first+visible);n++){Item row=item(n);if(row!=null)retained.add(row.cacheKey());}
        if(idle)for(int n=first+visible;n<Math.min(count,first+visible+6);n++){Item row=item(n);if(row==null){load(n/96);continue;}retained.add(row.cacheKey());if(state.media.memoryPreview(row)==null)previews.request(row,null,null);}
        previews.retain(retained);
    }
    void suspend(){paused=true;previews.cancel();}
    void resume(){paused=false;}
    void close(){closed=true;previews.close();pages.clear();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    static final class Tile extends FrameLayout {
        final ImageView image,video,choice;final TextView caption,seen,marks,stack;final ProgressBar progress;volatile String bound;
        Tile(Activity c){super(c);setBackground(GalleryUi.background(c.getColor(R.color.gallery_surface_container),dp(c,5)));setClipToOutline(true);image=new ImageView(c);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(image,new FrameLayout.LayoutParams(-1,-1));
            marks=new TextView(c);marks.setTextSize(11);marks.setTextColor(c.getColor(R.color.gallery_foreground));marks.setPadding(dp(c,5),dp(c,2),dp(c,5),dp(c,2));marks.setBackgroundColor(0xdd141715);marks.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(marks,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));
            progress=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(c.getColor(R.color.gallery_primary)));progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(progress,new FrameLayout.LayoutParams(-1,dp(c,3),Gravity.BOTTOM));
            caption=new TextView(c);caption.setTextSize(11);caption.setTextColor(0xfff2f7f4);caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);caption.setPadding(dp(c,6),dp(c,3),dp(c,6),dp(c,3));caption.setBackgroundColor(0xe6101717);caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(caption,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            stack=new TextView(c);stack.setTextSize(12);stack.setTextColor(c.getColor(R.color.gallery_foreground));stack.setPadding(dp(c,6),dp(c,3),dp(c,6),dp(c,3));stack.setBackground(GalleryUi.background(0xdd141715,dp(c,4)));stack.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams stackBadge=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);stackBadge.setMargins(0,0,dp(c,5),dp(c,6));addView(stack,stackBadge);
            seen=new TextView(c);seen.setText("Visto");seen.setTextSize(11);seen.setTextColor(c.getColor(R.color.gallery_foreground));seen.setPadding(dp(c,5),dp(c,2),dp(c,5),dp(c,2));seen.setBackground(GalleryUi.background(0xdd141715,dp(c,4)));seen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);marker.setMargins(dp(c,5),dp(c,5),0,0);addView(seen,marker);
            choice=new ImageView(c);choice.setImageResource(R.drawable.ic_gallery_check);choice.setImageTintList(android.content.res.ColorStateList.valueOf(c.getColor(R.color.gallery_on_primary)));choice.setPadding(dp(c,4),dp(c,4),dp(c,4),dp(c,4));android.graphics.drawable.GradientDrawable circle=new android.graphics.drawable.GradientDrawable();circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);circle.setColor(c.getColor(R.color.gallery_primary));choice.setBackground(circle);choice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams selected=new FrameLayout.LayoutParams(dp(c,28),dp(c,28),Gravity.CENTER);addView(choice,selected);
            video=new ImageView(c);video.setImageResource(android.R.drawable.ic_media_play);video.setColorFilter(0xffeef6f3);android.graphics.drawable.GradientDrawable scrim=new android.graphics.drawable.GradientDrawable();scrim.setShape(android.graphics.drawable.GradientDrawable.OVAL);scrim.setColor(0xd9101717);video.setBackground(scrim);video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(dp(c,28),dp(c,28),Gravity.TOP|Gravity.END);badge.setMargins(dp(c,4),dp(c,4),dp(c,4),0);addView(video,badge);
        }
        void placeholder(){image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);image.setImageResource(R.drawable.ic_gallery_image);image.setColorFilter(getContext().getColor(R.color.gallery_muted));}
        void preview(Bitmap bitmap){image.clearColorFilter();image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(bitmap);}
        private static int dp(Activity activity,int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    }
}
