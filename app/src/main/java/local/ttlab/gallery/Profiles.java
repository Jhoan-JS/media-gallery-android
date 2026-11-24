package local.ttlab.gallery;
/** Owner uses explicit on-device connector consent; lab users retain TestTes restrictions. */
final class Profiles {
    static boolean allowed(int user){return user==0||user==14||user==15;}
    static boolean owner(){return android.os.Process.myUid()/100000==0;}
    static boolean current(){return allowed(android.os.Process.myUid()/100000);}
}
