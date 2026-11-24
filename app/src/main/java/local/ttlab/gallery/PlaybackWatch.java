package local.ttlab.gallery;

/** Counts actual advancing playback, never time spent opening, seeking or paused. */
final class PlaybackWatch {
    private long played,lastElapsed=-1;private int lastPosition,duration;
    void duration(int milliseconds){duration=milliseconds;}
    void resetSample(){lastElapsed=-1;}
    boolean sample(long elapsed,int position,boolean playing){return sample(elapsed,position,playing,1);}
    boolean sample(long elapsed,int position,boolean playing,float rate){
        rate=PlayerControls.speed(rate);int threshold=Math.min(5000,Math.max(1,(int)(duration*.9/rate)));
        long wall=lastElapsed<0?0:elapsed-lastElapsed,advance=(long)position-lastPosition;
        if(playing&&wall>0&&advance>0&&advance<=wall*rate+500)played+=Math.min(wall,(long)Math.ceil(advance/rate));
        lastElapsed=elapsed;lastPosition=position;return played>=threshold;
    }
}
