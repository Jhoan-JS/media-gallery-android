package local.ttlab.gallery;
import android.content.Context;import android.graphics.*;import android.os.SystemClock;import android.view.*;import android.widget.*;
/** A narrow passive thumb becomes an indexed month navigator only after a deliberate hold. */
final class GalleryGridView extends GridView {
 private boolean dates,pending,dragging;private float x,y;private long down;private int section=-1;private Object[] months=new Object[0];private int[] positions=new int[0];private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 GalleryGridView(Context c){super(c);setVerticalScrollBarEnabled(false);}
 void setDateNavigationEnabled(boolean enabled){dates=enabled;if(!enabled)end();invalidate();}
 boolean dateNavigationEnabled(){return dates;}boolean dateNavigationActive(){return dragging;}boolean dateGesturePending(){return pending||dragging;}
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private final Runnable activate=()->{if(!pending||!hasWindowFocus()||!(getAdapter() instanceof SectionIndexer))return;pending=false;MotionEvent cancel=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x,y,0);super.dispatchTouchEvent(cancel);cancel.recycle();SectionIndexer index=(SectionIndexer)getAdapter();months=index.getSections();positions=new int[months.length];for(int n=0;n<months.length;n++)positions[n]=index.getPositionForSection(n);dragging=months.length>0;if(dragging){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);jump(y);}};
 private void jump(float at){y=at;if(months.length==0)return;float fraction=Math.max(0,Math.min(1,(at-getPaddingTop())/Math.max(1,getHeight()-getPaddingTop()-getPaddingBottom())));int chosen=Math.min(months.length-1,(int)(fraction*months.length));if(chosen!=section){section=chosen;setSelection(Math.min(Math.max(0,getCount()-1),positions[section]));}invalidate();}
 @Override public boolean dispatchTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){end();x=e.getX();y=e.getY();down=e.getDownTime();pending=dates&&x>=getWidth()-dp(14);if(pending)postDelayed(activate,ViewConfiguration.getLongPressTimeout());}
  if(dragging){if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)jump(e.getY());if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)end();return true;}
  if(action==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-x,e.getY()-y)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){pending=false;removeCallbacks(activate);}
  boolean handled=super.dispatchTouchEvent(e);if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)end();return handled;
 }
 private void end(){pending=false;removeCallbacks(activate);if(dragging&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);dragging=false;section=-1;months=new Object[0];positions=new int[0];invalidate();}
 @Override public void cancelLongPress(){end();super.cancelLongPress();}
 @Override public void onWindowFocusChanged(boolean focused){if(!focused)end();super.onWindowFocusChanged(focused);}
 @Override protected void onDetachedFromWindow(){end();super.onDetachedFromWindow();}
 @Override protected void dispatchDraw(Canvas canvas){super.dispatchDraw(canvas);if(!dates)return;int range=computeVerticalScrollRange()-computeVerticalScrollExtent();if(range<=0)return;float height=dp(28),travel=Math.max(0,getHeight()-getPaddingTop()-getPaddingBottom()-height);float top=getPaddingTop()+travel*Math.max(0,Math.min(1,computeVerticalScrollOffset()/(float)range));paint.setColor(0xffa4afa3);canvas.drawRoundRect(getWidth()-dp(5),top,getWidth()-dp(2),top+height,dp(2),dp(2),paint);
  if(dragging&&section>=0){String label=months[section].toString();paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);float width=paint.measureText(label)+dp(24),right=getWidth()-dp(20),center=Math.max(dp(24),Math.min(getHeight()-dp(24),y));paint.setColor(getContext().getColor(R.color.gallery_surface_container));canvas.drawRoundRect(right-width,center-dp(22),right,center+dp(22),dp(10),dp(10),paint);paint.setColor(getContext().getColor(R.color.gallery_foreground));canvas.drawText(label,right-width+dp(12),center-(paint.ascent()+paint.descent())/2,paint);}
 }
}
