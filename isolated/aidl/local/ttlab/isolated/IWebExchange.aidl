package local.ttlab.isolated;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IWebExchange {
    Bundle request(String method, in Bundle headers, int connectTimeout, int readTimeout);
    ParcelFileDescriptor body();
    oneway void close();
}
