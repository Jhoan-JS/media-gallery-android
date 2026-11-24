package local.ttlab.gallery;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official channel sponsorship is separate from media; impression requires actual visibility. */
final class SponsoredBanner extends LinearLayout {
    private final SecureActivity activity;private final Handler main=new Handler(Looper.getMainLooper());
    private State origin;private int channel,generation;private String adId;private boolean shown,closed;
    private final Runnable impression=this::recordImpression;
    private void recordImpression(){
        if(closed||origin==null||adId==null||shown)return;
        Rect visible=new Rect();boolean visibleEnough=getGlobalVisibleRect(visible)&&visible.height()>=getHeight()/2;
        if(!activity.isFinishing()&&!activity.isDestroyed()&&activity.hasWindowFocus()&&State.unlocked&&visibleEnough){shown=true;action("sponsored_view");}
        else if(isAttachedToWindow()&&activity.hasWindowFocus()&&State.unlocked)main.postDelayed(this.impression,1000);
    }
    SponsoredBanner(SecureActivity activity){super(activity);this.activity=activity;setTag("telegram-sponsored");setOrientation(VERTICAL);setVisibility(GONE);}
    void bind(State state,int selected){
        if(origin==state&&channel==selected)return;
        generation++;main.removeCallbacks(impression);origin=state;channel=selected;adId=null;shown=false;removeAllViews();setVisibility(GONE);
        if(closed||state==null||!"telegram".equals(state.key)||selected<=0)return;
        final int epoch=generation;
        state.thumbnails.execute(()->{try{JSONObject response=state.source.sponsored(selected);if(response==null)return;JSONArray messages=response.optJSONArray("messages");if(messages==null||messages.length()==0)return;JSONObject ad=messages.getJSONObject(0);main.post(()->{if(!closed&&epoch==generation&&origin==state&&!activity.isDestroyed())render(ad);});}catch(Exception ignored){}});
    }
    private void render(JSONObject ad){
        String id=ad.optString("id"),text=ad.optString("text");if(id.isEmpty()||id.length()>512||text.isEmpty()||text.length()>4096)return;
        adId=id;setPadding(dp(16),dp(10),dp(16),dp(10));setBackgroundColor(activity.getColor(R.color.gallery_surface_container));
        addView(GalleryUi.text(activity,"Patrocinado",12,activity.getColor(R.color.gallery_muted)));
        addView(GalleryUi.text(activity,text,14,activity.getColor(R.color.gallery_foreground)));
        for(String key:new String[]{"sponsor_info","additional_info"}){String note=ad.optString(key);if(!note.isEmpty()&&note.length()<=2048)addView(GalleryUi.text(activity,note,12,activity.getColor(R.color.gallery_muted)));}
        String url=SponsoredLink.browser(ad.optString("url"));
        if(url!=null){Button open=GalleryUi.button(activity,"Abrir anuncio",false);addView(open,new LayoutParams(-1,dp(48)));open.setOnClickListener(v->{try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));action("sponsored_click");}catch(android.content.ActivityNotFoundException unavailable){Toast.makeText(activity,"No hay una aplicación para abrir este enlace",Toast.LENGTH_SHORT).show();}});}
        setVisibility(VISIBLE);main.postDelayed(impression,1000);
    }
    private void action(String action){State state=origin;int selected=channel;String id=adId;if(state==null||id==null)return;state.thumbnails.execute(()->{try{state.source.sponsoredAction(action,selected,id);}catch(Exception ignored){}});}
    void close(){closed=true;generation++;main.removeCallbacksAndMessages(null);origin=null;removeAllViews();setVisibility(GONE);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);main.removeCallbacks(impression);if(focused&&!closed&&adId!=null&&!shown)main.postDelayed(impression,1000);}
    @Override protected void onDetachedFromWindow(){main.removeCallbacks(impression);super.onDetachedFromWindow();}
    private int dp(int value){return GalleryUi.dp(activity,value);}
}
