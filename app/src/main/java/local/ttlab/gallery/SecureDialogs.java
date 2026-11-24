package local.ttlab.gallery;
import android.content.Context;import android.content.ContextWrapper;
/** Dialogs have separate windows and must be dismissed with the activity's private UI. */
final class SecureDialogs {
 private static void track(Context c,android.app.Dialog dialog){while(c instanceof ContextWrapper&&!(c instanceof SecureActivity)){Context next=((ContextWrapper)c).getBaseContext();if(next==c)break;c=next;}if(c instanceof SecureActivity)((SecureActivity)c).trackDialog(dialog);}
 static final class Dialog extends android.app.Dialog {
  Dialog(Context c){super(c);track(c,this);}Dialog(Context c,int theme){super(c,theme);track(c,this);}
 }
 static final class Builder extends android.app.AlertDialog.Builder {
  Builder(Context c){super(c);}Builder(Context c,int theme){super(c,theme);}
  @Override public android.app.AlertDialog create(){android.app.AlertDialog dialog=super.create();track(getContext(),dialog);return dialog;}
 }
 static final class DatePicker extends android.app.DatePickerDialog {
  DatePicker(Context c,OnDateSetListener listener,int year,int month,int day){super(c,listener,year,month,day);track(c,this);}
 }
}
