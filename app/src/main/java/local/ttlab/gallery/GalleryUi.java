package local.ttlab.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.function.Consumer;

/** Shared visual roles; no storage, account or media behavior. */
final class GalleryUi {
    static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable background(int color,int radiusPx){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radiusPx);return d;}
    static TextView text(Context c,String value,int sp,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setFontFeatureSettings("tnum");return t;}
    static Button button(Activity a,String label,boolean filled){
        Button b=new Button(a);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(a,48));b.setMinimumHeight(dp(a,48));b.setMinWidth(dp(a,48));b.setMinimumWidth(dp(a,48));b.setPadding(dp(a,16),0,dp(a,16),0);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{a.getColor(R.color.gallery_muted),a.getColor(filled?R.color.gallery_on_primary:R.color.gallery_primary)}));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.gallery_ripple)),background(a.getColor(filled?R.color.gallery_primary:R.color.gallery_surface_container),dp(a,12)),null));return b;
    }
    static ImageButton icon(Activity a,int drawable,String description){
        ImageButton b=new ImageButton(a);b.setImageResource(drawable);b.setImageTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{a.getColor(R.color.gallery_outline),a.getColor(R.color.gallery_foreground)}));b.setContentDescription(description);b.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));
        b.setMinimumWidth(dp(a,48));b.setMinimumHeight(dp(a,48));b.setLayoutParams(new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.gallery_ripple)),null,background(0xffffffff,dp(a,24))));return b;
    }
    static String sourceLabel(Context c){return UiSource.label(c);}
    static void restoreGrid(android.widget.GridView grid,int position,int top){
        if(grid.getCount()==0)return;final int row=Math.max(0,Math.min(position,grid.getCount()-1))/Math.max(1,grid.getNumColumns())*Math.max(1,grid.getNumColumns());
        // Update GridView's pending adapter-sync row before its touch-mode selection.
        grid.setSelectionFromTop(row,top);grid.setSelection(row);
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(grid.getViewTreeObserver().isAlive())grid.getViewTreeObserver().removeOnPreDrawListener(this);if(grid.getFirstVisiblePosition()==row&&grid.getChildCount()>0)grid.scrollListBy(grid.getChildAt(0).getTop()-top);return true;}});
    }
    static void menu(SecureActivity a,String title,String[] labels,java.util.function.IntConsumer selected){
        if(a.state==null||!a.state.unlocked||!a.biometricCurrent(a.unlockEpoch()))return;
        android.app.AlertDialog dialog=new SecureDialogs.Builder(a).setTitle(title).setItems(labels,(d,n)->selected.accept(n)).setNegativeButton("Cerrar",null).create();
        a.trackPrivateDialog(dialog);dialog.show();dialog.getWindow().setGravity(Gravity.BOTTOM);dialog.getWindow().setLayout(-1,-2);
    }
    static View navigation(Activity a,String selected,boolean rail,Consumer<String> select){
        LinearLayout nav=new LinearLayout(a);nav.setOrientation(rail?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);nav.setGravity(rail?Gravity.TOP:Gravity.CENTER);nav.setBackgroundColor(a.getColor(R.color.gallery_surface));nav.setPadding(dp(a,4),dp(a,8),dp(a,4),dp(a,8));
        boolean local=UiSource.isLocal(a);
        String[] ids=local?new String[]{"library","import"}:new String[]{"library","channels","visual","downloads"};
        String[] labels=local?new String[]{"Biblioteca","Importar"}:new String[]{"Biblioteca",UiSource.isTato(a)?"Canales":"Chats","Visual","Descargas"};
        int[] icons=local?new int[]{R.drawable.ic_gallery_library,R.drawable.ic_gallery_download}:new int[]{R.drawable.ic_gallery_library,R.drawable.ic_gallery_channels,R.drawable.ic_gallery_visual,R.drawable.ic_gallery_download};
        for(int i=0;i<ids.length;i++){
            String id=ids[i];boolean active=selected.equals(id);LinearLayout item=new LinearLayout(a);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setContentDescription(labels[i]+(active?", seleccionado":""));item.setSelected(active);item.setClickable(true);item.setFocusable(true);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.gallery_ripple)),null,background(0xffffffff,dp(a,12))));
            ImageView image=new ImageView(a);image.setImageResource(icons[i]);image.setImageTintList(ColorStateList.valueOf(a.getColor(active?R.color.gallery_primary:R.color.gallery_muted)));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setPadding(dp(a,12),dp(a,3),dp(a,12),dp(a,3));
            if(active)image.setBackground(background(a.getColor(R.color.gallery_primary_container),dp(a,16)));item.addView(image,new LinearLayout.LayoutParams(dp(a,56),dp(a,30)));
            TextView label=text(a,labels[i],11,a.getColor(active?R.color.gallery_foreground:R.color.gallery_muted));label.setSingleLine(true);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(a,5),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LinearLayout.LayoutParams(-1,-2));
            nav.addView(item,rail?new LinearLayout.LayoutParams(-1,dp(a,76)):new LinearLayout.LayoutParams(0,dp(a,56),1));item.setOnClickListener(v->select.accept(id));
        }return nav;
    }
}
