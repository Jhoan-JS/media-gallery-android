package local.ttlab.gallery;

import android.os.*;import android.view.*;import android.widget.*;

/** Transient one-level undo lives inside the PIN curtain and the secure activity window. */
final class UndoBar {
 private final SecureActivity activity;private final Handler handler=new Handler(Looper.getMainLooper());
 private View bar;private State origin;private PersonalLibrary.Change change;private long epoch;
 UndoBar(SecureActivity activity){this.activity=activity;}
 private boolean active(){return origin!=null&&activity.state==origin&&origin.unlocked&&!activity.isFinishing()&&!activity.isDestroyed()&&activity.biometricCurrent(epoch);}
 void show(State state,PersonalLibrary.Change value,String message,int bottom,Runnable refreshed){
  clear();origin=state;epoch=activity.unlockEpoch();if(value==null||value.applied==0||!active()){clear();return;}change=value;
  FrameLayout content=(FrameLayout)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
  LinearLayout row=new LinearLayout(activity);row.setTag("personal-undo");row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(activity.dp(14),activity.dp(4),activity.dp(4),activity.dp(4));row.setElevation(activity.dp(8));row.setBackground(GalleryUi.background(activity.getColor(R.color.gallery_surface_container),activity.dp(12)));
  TextView label=activity.text(message,14,activity.getColor(R.color.gallery_foreground));label.setMaxLines(2);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
  Button undo=GalleryUi.button(activity,"Deshacer",false);undo.setTag("personal-undo-action");undo.setContentDescription("Deshacer última acción");row.addView(undo,new LinearLayout.LayoutParams(-2,activity.dp(48)));bar=row;
  FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);params.leftMargin=params.rightMargin=activity.dp(16);params.bottomMargin=activity.dp(bottom);content.addView(row,params);
  undo.setOnClickListener(v->{if(!active()||change==null){clear();return;}final State s=origin;final PersonalLibrary.Change token=change;final long expected=epoch;clear();s.database.execute(()->{try{if(!s.unlocked||activity.state!=s||!activity.biometricCurrent(expected))return;int restored=token.undo();activity.runOnUiThread(()->{if(activity.state==s&&s.unlocked&&!activity.isDestroyed()&&activity.biometricCurrent(expected)){refreshed.run();Toast.makeText(activity,restored>0?"Acción deshecha":"La marca ya cambió; se conservó su estado actual",Toast.LENGTH_SHORT).show();}});}catch(Exception failure){activity.runOnUiThread(()->{if(activity.state==s&&s.unlocked&&!activity.isDestroyed()&&activity.biometricCurrent(expected))Toast.makeText(activity,"No se pudo deshacer. Tus marcas actuales se conservan.",Toast.LENGTH_LONG).show();});}});});
  handler.postDelayed(this::clear,10000);handler.post(check);
 }
 private final Runnable check=new Runnable(){public void run(){if(!active()){clear();return;}handler.postDelayed(this,250);}};
 void clear(){handler.removeCallbacksAndMessages(null);if(bar!=null&&bar.getParent() instanceof ViewGroup)((ViewGroup)bar.getParent()).removeView(bar);bar=null;origin=null;change=null;}
}
