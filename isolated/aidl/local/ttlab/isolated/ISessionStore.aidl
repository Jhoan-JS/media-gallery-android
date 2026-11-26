package local.ttlab.isolated;

import android.os.ParcelFileDescriptor;

interface ISessionStore {
    ParcelFileDescriptor readSession();
    void saveSession(in ParcelFileDescriptor data);
}
