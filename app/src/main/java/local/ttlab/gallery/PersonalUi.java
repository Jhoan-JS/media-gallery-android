package local.ttlab.gallery;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Private organization only; all database work stays on the source's executor. */
final class PersonalUi {
    private PersonalUi(){}

    static void groups(SecureActivity a,State s,int kind,Consumer<PersonalLibrary.Group> choose) {
        long epoch=a.unlockEpoch();
        if(!active(a,s,epoch)||!kind(kind))return;
        background(a,s,epoch,()->s.library.personal.groups(kind),rows->{
            String[] labels=new String[rows.size()+1];
            for(int n=0;n<rows.size();n++)labels[n]=rows.get(n).name+" · "+rows.get(n).count;
            labels[rows.size()]="Crear "+single(kind);
            menu(a,s,epoch,plural(kind)+" · "+SourceId.label(s.key),labels,n->{
                if(n==rows.size()){groupName(a,s,epoch,kind,0,"",choose);return;}
                PersonalLibrary.Group row=rows.get(n);
                menu(a,s,epoch,row.name,new String[]{"Usar "+single(kind),"Renombrar","Eliminar "+single(kind)},action->{
                    if(action==0){if(choose!=null)choose.accept(row);}
                    else if(action==1)groupName(a,s,epoch,kind,row.id,row.name,choose);
                    else confirm(a,s,epoch,kind==PersonalLibrary.ALBUM?"¿Eliminar este álbum?":"¿Eliminar esta etiqueta?","Se elimina sólo la organización y sus referencias. Tus archivos, descargas y marcas se conservan.","Eliminar",()->{
                        background(a,s,epoch,()->{s.library.personal.deleteGroup(kind,row.id);return true;},done->{if(choose!=null)choose.accept(null);},failure->message(a,"No se pudo eliminar. Reintenta."));
                    });
                });
            });
        },failure->message(a,"No se pudieron leer "+plural(kind).toLowerCase(Locale.ROOT)+". Reintenta."));
    }

    static void memberships(SecureActivity a,State s,List<Item> items,int kind,Runnable changed) {
        long epoch=a.unlockEpoch();
        if(!active(a,s,epoch)||!kind(kind))return;
        if(items==null||items.isEmpty()||items.size()>1000||items.contains(null)){message(a,"Elige entre 1 y 1000 archivos.");return;}
        List<Item> snapshot=new ArrayList<>(items);
        Item opened=a instanceof ViewerActivity?snapshot.get(0):null;
        if(opened!=null)for(Item item:snapshot)if(!itemCurrent(a,item))return;
        background(a,s,epoch,opened,()->{
            List<PersonalLibrary.Group> rows=s.library.personal.groups(kind);
            Map<Long,Integer> counts=new HashMap<>();
            for(Item item:snapshot)for(Long id:s.library.personal.groupIds(item,kind))counts.put(id,counts.getOrDefault(id,0)+1);
            String[] labels=new String[rows.size()];boolean[] checked=new boolean[rows.size()];
            for(int n=0;n<rows.size();n++){
                int count=counts.getOrDefault(rows.get(n).id,0);
                checked[n]=count==snapshot.size();
                labels[n]=rows.get(n).name+(count>0&&count<snapshot.size()?" · algunos ("+count+"/"+snapshot.size()+")":"");
            }
            return new Memberships(rows,labels,checked);
        },data->{
            if(data.rows.isEmpty()){
                String path="En Biblioteca, toca el título y elige Álbumes y etiquetas → "+plural(kind)+" → Crear "+single(kind)+".";
                AlertDialog empty=new SecureDialogs.Builder(a).setTitle(plural(kind)).setMessage("Aún no hay "+plural(kind).toLowerCase(Locale.ROOT)+". "+path).setPositiveButton("Cerrar",null).create();
                show(a,empty);return;
            }
            boolean[] touched=new boolean[data.rows.size()];
            AlertDialog dialog=new SecureDialogs.Builder(a).setTitle(plural(kind)+" · "+snapshot.size()+" archivos")
                .setMultiChoiceItems(data.labels,data.checked,(d,n,value)->{if(active(a,s,epoch)&&itemCurrent(a,opened)){data.checked[n]=value;touched[n]=true;}})
                .setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(!active(a,s,epoch)||!itemCurrent(a,opened))return;
                boolean[] savedTouched=touched.clone(),savedChecked=data.checked.clone();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                background(a,s,epoch,opened,()->{
                    int applied=0;s.library.db.beginTransaction();
                    try {
                        for(int n=0;n<savedTouched.length;n++)if(savedTouched[n]&&!savedChecked[n])applied+=s.library.personal.setMembers(kind,data.rows.get(n).id,snapshot,false);
                        for(int n=0;n<savedTouched.length;n++)if(savedTouched[n]&&savedChecked[n])applied+=s.library.personal.setMembers(kind,data.rows.get(n).id,snapshot,true);
                        s.library.db.setTransactionSuccessful();
                    } finally {s.library.db.endTransaction();}
                    return applied;
                },applied->{dialog.dismiss();if(changed!=null)changed.run();},failure->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);message(a,"No se pudieron guardar los grupos. Reintenta.");});
            }));
            show(a,dialog);
        },failure->message(a,"No se pudieron leer los grupos. Reintenta."));
    }

    static void historySettings(SecureActivity a,State s,Runnable changed) {
        long epoch=a.unlockEpoch();
        if(!active(a,s,epoch))return;
        background(a,s,epoch,()->s.library.personal.historyEnabled(),enabled->{
            menu(a,s,epoch,"Historial · "+(enabled?"Activo":"Pausado"),new String[]{enabled?"Pausar historial":"Activar historial","Borrar historial"},action->{
                if(action==0)background(a,s,epoch,()->{s.library.personal.setHistoryEnabled(!enabled);return true;},done->{if(changed!=null)changed.run();},failure->message(a,"No se pudo cambiar el historial. Reintenta."));
                else confirm(a,s,epoch,"¿Borrar el historial?","Se borran las aperturas recientes de "+SourceId.label(s.key)+". Tus archivos y otras marcas se conservan.","Borrar",()->{
                    background(a,s,epoch,()->{s.library.personal.clearHistory();return true;},done->{if(changed!=null)changed.run();},failure->message(a,"No se pudo borrar el historial. Reintenta."));
                });
            });
        },failure->message(a,"No se pudo leer el historial. Reintenta."));
    }

    static void bookmarks(SecureActivity a,State s,Item item,int position,int duration,IntConsumer seek) {
        long epoch=a.unlockEpoch();
        if(!active(a,s,epoch)||item==null||!item.video()||!itemCurrent(a,item))return;
        background(a,s,epoch,item,()->s.library.personal.bookmarks(item),rows->{
            boolean canSave=duration>0&&position>=0&&position<=duration;
            String[] labels=new String[rows.size()+(canSave?1:0)];
            for(int n=0;n<rows.size();n++)labels[n]=time(rows.get(n).position)+" · "+rows.get(n).name;
            if(canSave)labels[rows.size()]="Guardar momento actual";
            if(labels.length==0){message(a,"No hay marcadores. Espera a que el video esté listo para guardar un momento.");return;}
            menu(a,s,epoch,item,"Marcadores",labels,n->{
                if(n==rows.size()){bookmarkName(a,s,epoch,item,position,duration,"");return;}
                PersonalLibrary.Bookmark row=rows.get(n);
                menu(a,s,epoch,item,time(row.position)+" · "+row.name,new String[]{"Saltar al momento","Renombrar","Eliminar marcador"},action->{
                    if(action==0)background(a,s,epoch,item,()->{
                        Item current=s.library.get(item.channel,item.id);
                        return current!=null&&current.revision.equals(item.revision);
                    },current->{if(current&&seek!=null)seek.accept(row.position);},failure->message(a,"No se pudo abrir este momento. Reintenta."));
                    else if(action==1)bookmarkName(a,s,epoch,item,row.position,row.duration,row.name);
                    else confirm(a,s,epoch,item,"¿Eliminar este marcador?","Se elimina sólo este momento guardado. El video y su progreso se conservan.","Eliminar",()->{
                        background(a,s,epoch,item,()->{s.library.personal.deleteBookmark(item,row.id);return true;},done->message(a,"Marcador eliminado"),failure->message(a,"No se pudo eliminar el marcador. Reintenta."));
                    });
                });
            });
        },failure->message(a,"No se pudieron leer los marcadores. Reintenta."));
    }

    private static void groupName(SecureActivity a,State s,long epoch,int kind,long id,String original,Consumer<PersonalLibrary.Group> choose) {
        name(a,s,epoch,null,id==0?"Crear "+single(kind):"Renombrar "+single(kind),kind==PersonalLibrary.ALBUM?"Nombre del álbum":"Nombre de la etiqueta",original,(value,done,failed)->{
            background(a,s,epoch,()->s.library.personal.saveGroup(kind,id,value),saved->{done.run();if(choose!=null)choose.accept(null);},failed);
        });
    }

    private static void bookmarkName(SecureActivity a,State s,long epoch,Item item,int position,int duration,String original) {
        name(a,s,epoch,item,original.isEmpty()?"Guardar momento actual":"Renombrar marcador","Nombre del marcador",original,(value,done,failed)->{
            background(a,s,epoch,item,()->s.library.personal.saveBookmark(item,position,duration,value),saved->{done.run();message(a,"Marcador guardado");},failed);
        });
    }

    private interface SaveName {void save(String value,Runnable done,Consumer<Exception> failed);}
    private static void name(SecureActivity a,State s,long epoch,Item opened,String title,String hint,String original,SaveName save) {
        if(!active(a,s,epoch)||!itemCurrent(a,opened))return;
        EditText input=new EditText(a);input.setHint(hint);input.setContentDescription(hint);input.setSingleLine(true);input.setText(original);input.setSelectAllOnFocus(true);
        input.setSaveEnabled(false);input.setFilterTouchesWhenObscured(true);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        LinearLayout box=new LinearLayout(a);box.setPadding(a.dp(24),a.dp(8),a.dp(24),0);box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new SecureDialogs.Builder(a).setTitle(title).setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!active(a,s,epoch)||!itemCurrent(a,opened))return;
            String value;
            try {value=SavedCollection.checkedName(input.getText().toString());}
            catch(IllegalArgumentException invalid){input.setError(invalid.getMessage());return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            save.save(value,dialog::dismiss,failure->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);input.setError(failure instanceof IllegalArgumentException||failure instanceof IllegalStateException?failure.getMessage():"No se pudo guardar. Reintenta.");});
        }));
        show(a,dialog);
    }

    private static boolean active(SecureActivity a,State s,long epoch) {return s!=null&&a.state==s&&s.unlocked&&!a.isFinishing()&&!a.isDestroyed()&&a.biometricCurrent(epoch);}
    private static boolean itemCurrent(SecureActivity a,Item opened) {return opened==null||!(a instanceof ViewerActivity)||((ViewerActivity)a).personalItemCurrent(opened);}
    private static boolean kind(int kind) {return kind==PersonalLibrary.ALBUM||kind==PersonalLibrary.TAG;}
    private static String single(int kind) {return kind==PersonalLibrary.ALBUM?"álbum":"etiqueta";}
    private static String plural(int kind) {return kind==PersonalLibrary.ALBUM?"Álbumes":"Etiquetas";}
    private static String time(int milliseconds) {int seconds=milliseconds/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    private static void message(SecureActivity a,String value) {Toast.makeText(a,value,Toast.LENGTH_LONG).show();}
    private static void show(SecureActivity a,AlertDialog dialog) {a.trackPrivateDialog(dialog);dialog.show();}
    private static void menu(SecureActivity a,State s,long epoch,String title,String[] labels,IntConsumer selected) {menu(a,s,epoch,null,title,labels,selected);}
    private static void menu(SecureActivity a,State s,long epoch,Item opened,String title,String[] labels,IntConsumer selected) {if(active(a,s,epoch)&&itemCurrent(a,opened))GalleryUi.menu(a,title,labels,n->{if(active(a,s,epoch)&&itemCurrent(a,opened))selected.accept(n);});}
    private static void confirm(SecureActivity a,State s,long epoch,String title,String message,String action,Runnable confirmed) {confirm(a,s,epoch,null,title,message,action,confirmed);}
    private static void confirm(SecureActivity a,State s,long epoch,Item opened,String title,String message,String action,Runnable confirmed) {if(!active(a,s,epoch)||!itemCurrent(a,opened))return;AlertDialog dialog=new SecureDialogs.Builder(a).setTitle(title).setMessage(message).setNegativeButton("Conservar",null).setPositiveButton(action,(d,w)->{if(active(a,s,epoch)&&itemCurrent(a,opened))confirmed.run();}).create();show(a,dialog);}
    private interface Work<T> {T run()throws Exception;}
    private static <T> void background(SecureActivity a,State s,long epoch,Work<T> work,Consumer<T> done,Consumer<Exception> failed) {
        background(a,s,epoch,null,work,done,failed);
    }
    private static <T> void background(SecureActivity a,State s,long epoch,Item opened,Work<T> work,Consumer<T> done,Consumer<Exception> failed) {
        s.database.execute(()->{
            if(!active(a,s,epoch)||!itemCurrent(a,opened))return;
            try {T value=work.run();a.runOnUiThread(()->{if(active(a,s,epoch)&&itemCurrent(a,opened))done.accept(value);});}
            catch(Exception failure){a.runOnUiThread(()->{if(active(a,s,epoch)&&itemCurrent(a,opened))failed.accept(failure);});}
        });
    }
    private static final class Memberships {
        final List<PersonalLibrary.Group> rows;final String[] labels;final boolean[] checked;
        Memberships(List<PersonalLibrary.Group> rows,String[] labels,boolean[] checked){this.rows=rows;this.labels=labels;this.checked=checked;}
    }
}
