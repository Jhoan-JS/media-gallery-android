package local.ttlab.isolated;

import android.os.ParcelFileDescriptor;

interface ITelegramSession {
    ParcelFileDescriptor request(String request);
    ParcelFileDescriptor password(in byte[] password);
    ParcelFileDescriptor readMedia(String request);
    oneway void close();
}
