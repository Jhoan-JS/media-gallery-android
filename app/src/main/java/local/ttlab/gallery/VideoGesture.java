package local.ttlab.gallery;
/** The center belongs to zoom; the outer thirds retain10second seeks. */
final class VideoGesture {
 static int doubleTap(float fraction){return fraction<1f/3?-1:fraction>2f/3?1:0;}
 static float scale(float current,float factor){if(!Float.isFinite(factor)||factor<=0)return current;return Math.max(1,Math.min(4,current*factor));}
}
