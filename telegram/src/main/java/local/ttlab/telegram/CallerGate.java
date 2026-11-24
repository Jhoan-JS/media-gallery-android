package local.ttlab.telegram;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserManager;
import java.security.MessageDigest;

final class CallerGate {
    static void foreground(Context context) {
        if(!((UserManager)context.getSystemService(Context.USER_SERVICE)).isUserForeground())throw new SecurityException("E_FOREGROUND_PROFILE");
    }
    static Caller provider(Context context,String packageName) {
        if(context.checkCallingPermission("local.ttlab.permission.TELEGRAM")!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("E_GALLERY_CALLER");
        return verify(context,packageName,Binder.getCallingUid());
    }
    static Caller activity(Context context,String packageName)throws Exception {
        if(packageName==null)throw new SecurityException("E_GALLERY_CALLER");
        return verify(context,packageName,context.getPackageManager().getPackageUid(packageName,0));
    }
    static Caller verify(Context context,String packageName,int uid) {
        foreground(context);PackageManager pm=context.getPackageManager();
        if(uid/100000!=Process.myUid()/100000||!BuildConfig.GALLERY_PACKAGE.equals(packageName)
            ||pm.checkSignatures(context.getPackageName(),packageName)!=PackageManager.SIGNATURE_MATCH)throw new SecurityException("E_GALLERY_CALLER");
        try {
            if(pm.getPackageUid(packageName,0)!=uid)throw new SecurityException("E_GALLERY_CALLER");
            PackageInfo info=pm.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
            StringBuilder signer=new StringBuilder();for(byte b:digest)signer.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return new Caller(packageName,uid,signer.toString());
        }catch(Exception failure){throw new SecurityException("E_GALLERY_CALLER");}
    }
    static final class Caller {
        final String packageName,signer;final int uid;
        Caller(String packageName,int uid,String signer){this.packageName=packageName;this.uid=uid;this.signer=signer;}
    }
}
