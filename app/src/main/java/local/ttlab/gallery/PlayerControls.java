package local.ttlab.gallery;
final class PlayerControls {
 static float speed(float value){return Float.isFinite(value)?Math.max(.5f,Math.min(2,value)):1;}
 static float level(float start,float delta){return Math.max(0,Math.min(1,start+delta));}
 static int side(float x){return x<.4f?-1:x>.6f?1:0;}
}
