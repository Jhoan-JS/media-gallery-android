package local.ttlab.isolated;

import android.os.ParcelFileDescriptor;

interface IConnectorSockets {
    ParcelFileDescriptor openTelegramSocket(String address);
    ParcelFileDescriptor openWebSocket(String host);
}
