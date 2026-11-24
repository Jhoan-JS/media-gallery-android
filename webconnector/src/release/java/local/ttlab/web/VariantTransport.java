package local.ttlab.web;

import android.content.Context;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

final class VariantTransport {
    static HttpURLConnection open(Context context, URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
    }
}
