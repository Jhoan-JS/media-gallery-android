package local.ttlab.gallery;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.*;

/** Selected source is a preference; screens and jobs capture their own State. */
final class UiSource {
    static String key(Context c){String key=c.getSharedPreferences("gallery-appearance",Context.MODE_PRIVATE).getString("source","tato");try{return SourceId.require(key);}catch(IllegalArgumentException invalid){return "tato";}}
    static String screenKey(Context c){return c instanceof SecureActivity&&((SecureActivity)c).state!=null?((SecureActivity)c).state.key:key(c);}
    static boolean available(Context c){return true;}
    static boolean isTato(Context c){return "tato".equals(screenKey(c));}
    static boolean isLocal(Context c){return SourceId.local(screenKey(c));}
    static String label(Context c){return SourceId.label(screenKey(c));}
    static Button selector(Activity a,Runnable changed){Button b=GalleryUi.button(a,label(a),false);b.setContentDescription("Fuente: "+label(a)+". Cambiar fuente");b.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,R.drawable.ic_gallery_chevron_down,0);b.setCompoundDrawablePadding(GalleryUi.dp(a,6));b.setOnClickListener(v->choose(a,changed));return b;}
    static void choose(Activity a,Runnable changed){
        Dialog dialog=new SecureDialogs.Dialog(a);dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);int pad=GalleryUi.dp(a,20);panel.setPadding(pad,pad,pad,pad);panel.setBackground(GalleryUi.background(a.getColor(R.color.gallery_surface_container),GalleryUi.dp(a,24)));
        TextView heading=GalleryUi.text(a,"Fuentes",22,a.getColor(R.color.gallery_foreground));heading.setPadding(GalleryUi.dp(a,8),0,0,GalleryUi.dp(a,16));panel.addView(heading);
        String[] keys={"tato","telegram","local"},notes={"Sesión independiente","Conector TG · sesión independiente","Copias cifradas en este teléfono"};
        for(int i=0;i<keys.length;i++){
            String key=keys[i],name=SourceId.label(key);LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(GalleryUi.dp(a,8),0,GalleryUi.dp(a,8),0);row.setMinimumHeight(GalleryUi.dp(a,72));row.setClickable(true);row.setFocusable(true);row.setContentDescription(name+", "+notes[i]);
            LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);copy.addView(GalleryUi.text(a,name,17,a.getColor(R.color.gallery_foreground)));copy.addView(GalleryUi.text(a,notes[i],13,a.getColor(R.color.gallery_muted)));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            RadioButton radio=new RadioButton(a);radio.setChecked(key.equals(screenKey(a)));radio.setClickable(false);radio.setFocusable(false);radio.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(radio);panel.addView(row,new LinearLayout.LayoutParams(-1,GalleryUi.dp(a,72)));
            row.setOnClickListener(v->{a.getSharedPreferences("gallery-appearance",Context.MODE_PRIVATE).edit().putString("source",key).apply();dialog.dismiss();changed.run();});
        }
        Button close=GalleryUi.button(a,"Cerrar",false);close.setOnClickListener(v->dialog.dismiss());panel.addView(close,new LinearLayout.LayoutParams(-1,GalleryUi.dp(a,48)));dialog.setContentView(panel);dialog.show();
        android.view.Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);}
    }
}
