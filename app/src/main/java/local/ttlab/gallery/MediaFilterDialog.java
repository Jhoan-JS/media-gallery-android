package local.ttlab.gallery;

import android.app.*;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.math.*;
import java.text.DateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Metadata-only options; applying never starts a download or model. */
final class MediaFilterDialog {
    static void show(SecureActivity a,MediaFilter current,boolean savedGrid,Consumer<MediaFilter> apply,Consumer<MediaFilter> save){
        MediaFilter chosen=current.copy();LinearLayout fields=new LinearLayout(a);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(a.dp(24),a.dp(8),a.dp(24),a.dp(16));
        TextView error=a.text("",14,a.getColor(R.color.gallery_error));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);error.setVisibility(View.GONE);fields.addView(error);
        Spinner type=choice(a,fields,"Tipo",new String[]{"Todos los archivos","Imágenes","Videos"},chosen.type);
        Spinner order=choice(a,fields,"Orden",new String[]{"Más recientes primero","Más antiguos primero","Mayor tamaño primero","Menor tamaño primero","Videos más largos primero","Nombre · A–Z","Última apertura"},chosen.sort);
        Spinner archive=choice(a,fields,"Archivo",new String[]{"Biblioteca","Solo archivados","Incluir archivados"},chosen.archive);
        Spinner watched=choice(a,fields,"Visualización",new String[]{"Todos","Videos sin ver","Videos vistos"},chosen.watched);
        Spinner storage=choice(a,fields,"Disponibilidad",new String[]{"Todos","Guardados sin conexión","Sin guardar"},chosen.availability);storage.setEnabled(!savedGrid);
        CheckBox duplicates=new CheckBox(a);duplicates.setText("Solo duplicados exactos");duplicates.setMinHeight(a.dp(48));duplicates.setChecked(chosen.duplicates);fields.addView(duplicates);
        Spinner dates=choice(a,fields,"Fecha",new String[]{"Cualquier fecha","Últimos 7 días","Últimos 30 días","Este año","Personalizado"},chosen.from>0||chosen.until>0?4:0);
        long[] range={chosen.from,chosen.until};Button start=GalleryUi.button(a,dateLabel("Desde",range[0],false),false),end=GalleryUi.button(a,dateLabel("Hasta",range[1],true),false);fields.addView(start,new LinearLayout.LayoutParams(-1,a.dp(48)));fields.addView(end,new LinearLayout.LayoutParams(-1,a.dp(48)));
        final int initial=dates.getSelectedItemPosition();final boolean[] first={true};dates.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){if(first[0]){first[0]=false;if(n==initial)return;}if(n==4)return;range[0]=range[1]=0;if(n>0){Calendar from=Calendar.getInstance();from.set(Calendar.HOUR_OF_DAY,0);from.set(Calendar.MINUTE,0);from.set(Calendar.SECOND,0);from.set(Calendar.MILLISECOND,0);Calendar until=(Calendar)from.clone();until.add(Calendar.DAY_OF_MONTH,1);if(n==3){from.set(Calendar.DAY_OF_YEAR,1);}else from.add(Calendar.DAY_OF_MONTH,n==1?-6:-29);range[0]=from.getTimeInMillis()/1000;range[1]=until.getTimeInMillis()/1000;}start.setText(dateLabel("Desde",range[0],false));end.setText(dateLabel("Hasta",range[1],true));}});
        start.setOnClickListener(v->pickDate(a,start,"Desde",range,0,dates));end.setOnClickListener(v->pickDate(a,end,"Hasta",range,1,dates));
        LinearLayout advanced=new LinearLayout(a);advanced.setOrientation(LinearLayout.VERTICAL);
        Spinner shape=choice(a,advanced,"Orientación conocida",new String[]{"Cualquiera","Horizontal","Vertical","Cuadrada"},chosen.shape);
        Spinner format=choice(a,advanced,"Formato",new String[]{"Cualquiera","JPEG","PNG","WebP","GIF","MP4","WebM","MKV"},chosen.format);
        EditText[] bytes=numbers(a,advanced,"Tamaño en MiB",chosen.minBytes,chosen.maxBytes,1048576);
        EditText[] seconds=numbers(a,advanced,"Duración del video en segundos",chosen.minSeconds,chosen.maxSeconds,1);
        Button more=GalleryUi.button(a,"Más opciones",false);fields.addView(more,new LinearLayout.LayoutParams(-1,a.dp(48)));fields.addView(advanced);boolean expanded=chosen.shape>0||chosen.format>0||chosen.minBytes>0||chosen.maxBytes>0||chosen.minSeconds>0||chosen.maxSeconds>0;advanced.setVisibility(expanded?View.VISIBLE:View.GONE);more.setText(expanded?"Menos opciones":"Más opciones");more.setOnClickListener(v->{boolean show=advanced.getVisibility()!=View.VISIBLE;advanced.setVisibility(show?View.VISIBLE:View.GONE);more.setText(show?"Menos opciones":"Más opciones");});
        Button saveFilters=GalleryUi.button(a,"Guardar filtros",false);fields.addView(saveFilters,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(a);scroll.addView(fields);
        AlertDialog dialog=new SecureDialogs.Builder(a).setTitle("Filtrar y ordenar").setView(scroll)
            .setNegativeButton("Cancelar",null).setNeutralButton("Restablecer",(d,w)->apply.accept(reset(current,savedGrid)))
            .setPositiveButton("Aplicar",null).create();
        Supplier<MediaFilter> read=()->{
            chosen.archive=archive.getSelectedItemPosition();chosen.type=type.getSelectedItemPosition();chosen.sort=order.getSelectedItemPosition();
            chosen.watched=watched.getSelectedItemPosition();chosen.availability=savedGrid?1:storage.getSelectedItemPosition();chosen.duplicates=duplicates.isChecked();
            chosen.from=range[0];chosen.until=range[1];chosen.shape=shape.getSelectedItemPosition();chosen.format=format.getSelectedItemPosition();
            chosen.minBytes=number(bytes[0],1048576);chosen.maxBytes=number(bytes[1],1048576);chosen.minSeconds=number(seconds[0],1);chosen.maxSeconds=number(seconds[1],1);
            return chosen.copy();
        };
        saveFilters.setOnClickListener(v->submit(dialog,scroll,error,read,save));
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submit(dialog,scroll,error,read,apply)));
        a.trackPrivateDialog(dialog);dialog.show();
    }
    private static MediaFilter reset(MediaFilter current,boolean savedGrid){
        MediaFilter reset=new MediaFilter();
        reset.channel=current.channel;reset.query=current.query;reset.collection=current.collection;
        reset.albumId=current.albumId;reset.tagId=current.tagId;reset.recent=current.recent;
        reset.archive=current.archive==1?1:0;
        if(current.recent)reset.sort=6;
        if(savedGrid)reset.availability=1;
        return reset;
    }
    private static void submit(AlertDialog dialog,ScrollView scroll,TextView error,Supplier<MediaFilter> read,Consumer<MediaFilter> action){
        MediaFilter filter=null;
        String message;
        try{
            filter=read.get();filter.check();
            message=conflict(filter);
        }catch(IllegalArgumentException failure){
            message="Revisa las fechas y los rangos. El máximo debe ser igual o mayor que el mínimo.";
        }
        if(message==null){dialog.dismiss();action.accept(filter);return;}
        error.setText(message);error.setVisibility(View.VISIBLE);scroll.smoothScrollTo(0,0);
    }
    private static String conflict(MediaFilter filter){
        boolean imageFormat=filter.format>0&&filter.format<=4,videoFormat=filter.format>=5;
        if((filter.type==1&&videoFormat)||(filter.type==2&&imageFormat))return "El tipo de archivo no se puede combinar con el formato elegido. Revisa Tipo y Formato.";
        if(filter.type!=1&&!imageFormat)return null;
        if(filter.watched>0)return "El filtro de videos vistos o sin ver no se puede combinar con imágenes. Revisa Tipo, Formato y Visualización.";
        if(filter.minSeconds>0||filter.maxSeconds>0)return "La duración del video no se puede combinar con imágenes. Revisa Tipo, Formato y Duración.";
        return null;
    }
    static Spinner choice(SecureActivity a,LinearLayout group,String label,String[] options,int selected){TextView title=a.text(label,14,a.getColor(R.color.gallery_muted));title.setPadding(0,a.dp(14),0,a.dp(2));group.addView(title);Spinner spinner=new Spinner(a);spinner.setContentDescription(label);spinner.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,options));spinner.setSelection(selected);group.addView(spinner,new LinearLayout.LayoutParams(-1,a.dp(48)));return spinner;}
    private static EditText[] numbers(SecureActivity a,LinearLayout group,String label,long min,long max,long unit){TextView title=a.text(label,14,a.getColor(R.color.gallery_muted));title.setPadding(0,a.dp(16),0,0);group.addView(title);LinearLayout row=new LinearLayout(a);EditText[] inputs=new EditText[2];for(int n=0;n<2;n++){EditText input=new EditText(a);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(unit>1?android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:0));input.setHint(n==0?"Mínimo":"Máximo");input.setContentDescription(label+(n==0?", mínimo":", máximo"));input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);long value=n==0?min:max;if(value>0)input.setText(BigDecimal.valueOf(value).divide(BigDecimal.valueOf(unit),6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());row.addView(input,new LinearLayout.LayoutParams(0,a.dp(56),1));inputs[n]=input;}group.addView(row);return inputs;}
    private static long number(EditText input,long unit){String text=input.getText().toString().trim().replace(',','.');if(text.isEmpty())return 0;try{return new BigDecimal(text).multiply(BigDecimal.valueOf(unit)).setScale(0,RoundingMode.HALF_UP).longValueExact();}catch(ArithmeticException failure){throw new IllegalArgumentException(failure);}}
    private static String dateLabel(String label,long seconds,boolean exclusive){return label+": "+(seconds==0?"Cualquier fecha":DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(seconds*1000-(exclusive?1:0))));}
    private static void pickDate(SecureActivity a,Button button,String label,long[] range,int index,Spinner presets){Calendar calendar=Calendar.getInstance();if(range[index]>0)calendar.setTimeInMillis(range[index]*1000-(index==1?1:0));DatePickerDialog picker=new SecureDialogs.DatePicker(a,(view,year,month,day)->{Calendar selected=Calendar.getInstance();selected.clear();selected.set(year,month,day);if(index==1)selected.add(Calendar.DAY_OF_MONTH,1);range[index]=selected.getTimeInMillis()/1000;presets.setSelection(4);button.setText(dateLabel(label,range[index],index==1));},calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH));Calendar first=Calendar.getInstance();first.clear();first.set(1970,0,1);picker.getDatePicker().setMinDate(first.getTimeInMillis());picker.setButton(AlertDialog.BUTTON_NEUTRAL,"Quitar",(dialog,which)->{range[index]=0;presets.setSelection(4);button.setText(dateLabel(label,0,index==1));});picker.show();}
}
