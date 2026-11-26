package local.ttlab.gallery;
import android.content.Context;
import android.os.UserManager;

final class Profiles {
    static boolean active(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isUserForeground();
    }
}
