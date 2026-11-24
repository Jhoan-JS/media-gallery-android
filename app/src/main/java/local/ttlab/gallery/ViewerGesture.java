package local.ttlab.gallery;

/** Horizontal page flings must not consume image pans or vertical gestures. */
final class ViewerGesture {
    static int direction(float dx,float dy,float velocityX,float density,boolean atFit){
        if(!atFit||Math.abs(dx)<72*density||Math.abs(dx)<2*Math.abs(dy)||Math.abs(velocityX)<240*density)return 0;
        return dx<0?1:-1;
    }
}
