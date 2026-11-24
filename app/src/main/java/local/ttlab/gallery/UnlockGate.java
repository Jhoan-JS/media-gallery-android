package local.ttlab.gallery;

final class UnlockGate {
    static boolean accept(long expected,long current,boolean resumed,boolean foreground,boolean finishing,boolean maintenance){
        return expected==current&&resumed&&foreground&&!finishing&&!maintenance;
    }
}
