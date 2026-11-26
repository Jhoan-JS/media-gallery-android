package local.ttlab.web;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import local.ttlab.isolated.IWebExchange;
import local.ttlab.isolated.SourceWorkerConnection;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class VariantTransport {
    static HttpURLConnection open(Context context, URI uri) throws IOException {
        return new IsolatedConnection(context, uri);
    }

    private static final class IsolatedConnection extends HttpURLConnection {
        private static final int REQUEST_LIMIT = 8192;
        private final Context context;
        private final String target;
        private final Object lifetime = new Object();
        private volatile boolean closed;
        private IWebExchange exchange;
        private InputStream localPipe;
        private InputStream responseBody;
        private Map<String, List<String>> responseHeaders;

        IsolatedConnection(Context context, URI uri) throws IOException {
            super(uri.toURL());
            this.context = context.getApplicationContext();
            target = uri.toString();
            setConnectTimeout(7000);
            setReadTimeout(9000);
            setInstanceFollowRedirects(false);
        }

        @Override public synchronized void connect() throws IOException {
            checkOpen();
            if (connected) return;
            Bundle headers = requestHeaders();
            try {
                SourceWorkerConnection link = SourceWorkerConnection.get(context);
                checkOpen();
                IWebExchange remote = link.worker.openWeb(target, link.sockets);
                if (remote == null) throw unavailable();
                boolean keep;
                synchronized (lifetime) {
                    keep = !closed;
                    if (keep) exchange = remote;
                }
                if (!keep) closeRemote(remote);
                checkOpen();
                Bundle response = remote.request(method, headers, getConnectTimeout(), getReadTimeout());
                checkOpen();
                if (response == null) throw unavailable();
                int status = response.getInt("status");
                Bundle fields = response.getBundle("headers");
                if (status < 200 || status > 599 || fields == null) throw unavailable();
                Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (String name : fields.keySet()) {
                    List<String> values = fields.getStringArrayList(name);
                    if (values == null || values.isEmpty() || copy.put(name, List.copyOf(values)) != null) throw unavailable();
                }
                responseCode = status;
                responseHeaders = Collections.unmodifiableMap(copy);
                connected = true;
            } catch (IOException | RemoteException | RuntimeException failure) {
                checkOpen();
                if ("E_ISOLATED_CONNECTOR".equals(failure.getMessage())) throw new IOException("E_WEB_CONNECTOR");
                throw unavailable();
            } finally {
                if (!connected) disconnect();
            }
        }

        private Bundle requestHeaders() throws IOException {
            if (getDoOutput() || !("GET".equals(method) || "HEAD".equals(method))) {
                throw new IOException("E_WEB_REQUEST");
            }
            int size = target.length();
            if (size > REQUEST_LIMIT) throw new IOException("E_WEB_REQUEST");
            Bundle headers = new Bundle();
            for (Map.Entry<String, List<String>> field : getRequestProperties().entrySet()) {
                List<String> values = field.getValue();
                if (values.size() != 1 || values.get(0) == null) throw new IOException("E_WEB_REQUEST");
                String name = field.getKey();
                String value = values.get(0);
                if ((long) name.length() + value.length() + 4 > REQUEST_LIMIT - size) throw new IOException("E_WEB_REQUEST");
                size += name.length() + value.length() + 4;
                headers.putString(name, value);
            }
            return headers;
        }

        @Override public synchronized int getResponseCode() throws IOException {
            if (!connected) connect();
            return responseCode;
        }

        @Override public Map<String, List<String>> getHeaderFields() {
            try {
                getResponseCode();
                return responseHeaders;
            } catch (IOException unavailable) {
                return Collections.emptyMap();
            }
        }

        @Override public String getHeaderField(String name) {
            if (name == null) return null;
            List<String> values = getHeaderFields().get(name);
            return values == null ? null : String.join(", ", values);
        }

        @Override public synchronized InputStream getInputStream() throws IOException {
            checkOpen();
            if (getResponseCode() >= 400) throw new IOException("E_WEB_HTTP");
            if (responseBody != null) return responseBody;
            IWebExchange remote;
            synchronized (lifetime) {
                checkOpen();
                remote = exchange;
            }
            ParcelFileDescriptor body;
            try {
                body = remote.body();
            } catch (RemoteException | RuntimeException failure) {
                checkOpen();
                throw unavailable();
            }
            if (body == null) {
                checkOpen();
                throw unavailable();
            }
            InputStream pipe = new ParcelFileDescriptor.AutoCloseInputStream(body);
            synchronized (lifetime) {
                if (closed) closePipe(pipe);
                checkOpen();
                localPipe = pipe;
            }
            responseBody = new FilterInputStream(pipe) {
                @Override public int read() throws IOException {
                    checkOpen();
                    try { return in.read(); } catch (IOException failure) { throw unavailable(); }
                    finally { checkOpen(); }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    checkOpen();
                    try { return in.read(bytes, offset, length); } catch (IOException failure) { throw unavailable(); }
                    finally { checkOpen(); }
                }
                @Override public void close() { disconnect(); }
            };
            return responseBody;
        }

        @Override public boolean usingProxy() { return false; }
        @Override public String toString() { return "IsolatedWebConnection"; }

        @Override public void disconnect() {
            IWebExchange remote;
            InputStream pipe;
            synchronized (lifetime) {
                if (closed) return;
                closed = true;
                remote = exchange;
                pipe = localPipe;
                exchange = null;
                localPipe = null;
            }
            closePipe(pipe);
            closeRemote(remote);
        }

        private void checkOpen() throws IOException {
            if (closed) throw new IOException("E_WEB_CANCELED");
        }

        private static IOException unavailable() { return new IOException("E_WEB_NETWORK"); }

        private static void closePipe(InputStream pipe) {
            if (pipe != null) try { pipe.close(); } catch (IOException ignored) { }
        }

        private static void closeRemote(IWebExchange remote) {
            if (remote != null) try { remote.close(); } catch (RemoteException | RuntimeException ignored) { }
        }
    }
}
