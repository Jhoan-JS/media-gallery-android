package local.ttlab.gallery;

/** Resume policy shared by the player and its persisted checkpoints. */
final class PlaybackProgress {
    static int checkpoint(int position,int duration){
        return duration<=0||position<3000||(long)duration-position<=2000||(long)position*100>=(long)duration*95?0:position;
    }
    static int resume(int position,int duration,boolean completed){return completed?0:checkpoint(position,duration);}
}
