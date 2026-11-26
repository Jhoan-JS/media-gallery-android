package local.ttlab.isolated;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import local.ttlab.telegram.BuildConfig;
import local.ttlab.telegram.BridgePolicy;
import local.ttlab.web.DelegatedHttpsConnection;
import tgengine.Client;
import tgengine.Store;
import tgengine.Tgengine;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SourceWorkerService extends Service {
    private static final int LIMIT_SESSION = 1 << 20;
    private static final int LIMIT_JSON = 8 << 20;
    private static final int LIMIT_REQUEST = 32 << 10;

    @Override public IBinder onBind(Intent intent) {
        int gallery = getApplicationInfo().uid;
        if (!BuildConfig.GALLERY_PACKAGE.equals(getPackageName()) ||
                Process.isApplicationUid(Process.myUid()) || !Process.isApplicationUid(gallery)) return null;
        return new ISourceWorker.Stub() {
            @Override public int isolatedUid() {
                verify(gallery);
                return Process.myUid();
            }

            @Override public ITelegramSession openTelegram(long apiId, String apiHash,
                                                           ISessionStore storage, IConnectorSockets sockets) {
                verify(gallery);
                if (storage == null || sockets == null || apiHash == null) throw new IllegalArgumentException("E_ISOLATED_REQUEST");
                try {
                    Store guardedStore = new Store() {
                        @Override public byte[] loadSession() throws Exception {
                            return IsolatedPipes.read(storage.readSession(), LIMIT_SESSION);
                        }
                        @Override public void saveSession(byte[] data) throws Exception {
                            if (data == null || data.length > LIMIT_SESSION) throw new IOException("E_ISOLATED_LIMIT");
                            try (ParcelFileDescriptor pipe = IsolatedPipes.send(data.clone())) {
                                storage.saveSession(pipe);
                            }
                        }
                    };
                    Client client = Tgengine.newClientWithDialer(apiId, apiHash, guardedStore, address -> {
                        try (ParcelFileDescriptor fd = sockets.openTelegramSocket(address)) {
                            if (fd == null) throw new IOException("E_ISOLATED_SOCKET");
                            return fd.detachFd();
                        }
                    });
                    return telegram(gallery, client);
                } catch (Exception | LinkageError failure) {
                    throw new IllegalStateException("E_TELEGRAM_ENGINE_UNAVAILABLE");
                }
            }

            @Override public IWebExchange openWeb(String uri, IConnectorSockets sockets) {
                verify(gallery);
                if (sockets == null || uri == null || uri.length() > LIMIT_REQUEST)
                    throw new IllegalArgumentException("E_ISOLATED_REQUEST");
                try {
                    DelegatedHttpsConnection connection = new DelegatedHttpsConnection(URI.create(uri), host -> {
                        try {
                            ParcelFileDescriptor fd = sockets.openWebSocket(host);
                            if (fd == null) throw new IOException("E_ISOLATED_SOCKET");
                            return fd;
                        } catch (Exception unavailable) {
                            throw new IOException("E_WEB_NETWORK");
                        }
                    });
                    return web(gallery, connection);
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("E_WEB_REQUEST");
                }
            }
        };
    }

    private static ITelegramSession telegram(int gallery, Client client) {
        return new ITelegramSession.Stub() {
            @Override public ParcelFileDescriptor request(String json) {
                verify(gallery);
                checkRequest(json);
                try { return output(client.request(json), LIMIT_JSON); }
                catch (Exception failure) { throw new IllegalStateException(BridgePolicy.error(failure)); }
            }

            @Override public ParcelFileDescriptor password(byte[] password) {
                verify(gallery);
                if (password == null) throw new IllegalArgumentException("E_ISOLATED_REQUEST");
                try {
                    if (password.length > 4096) throw new IllegalArgumentException("E_ISOLATED_REQUEST");
                    return output(client.password(password), LIMIT_JSON);
                } catch (Exception failure) {
                    throw new IllegalStateException(BridgePolicy.error(failure));
                } finally { Arrays.fill(password, (byte) 0); }
            }

            @Override public ParcelFileDescriptor readMedia(String json) {
                verify(gallery);
                checkRequest(json);
                try { return output(client.read(json), LIMIT_SESSION); }
                catch (Exception failure) { throw new IllegalStateException(BridgePolicy.error(failure)); }
            }

            @Override public void close() {
                verify(gallery);
                client.close();
            }
        };
    }

    private static IWebExchange web(int gallery, DelegatedHttpsConnection connection) {
        AtomicBoolean requested = new AtomicBoolean(), streaming = new AtomicBoolean();
        return new IWebExchange.Stub() {
            @Override public Bundle request(String method, Bundle headers, int connectTimeout, int readTimeout) {
                verify(gallery);
                if (!requested.compareAndSet(false, true)) throw new IllegalStateException("E_WEB_REQUEST");
                try {
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(Math.max(1, Math.min(15_000, connectTimeout)));
                    connection.setReadTimeout(Math.max(1, Math.min(15_000, readTimeout)));
                    connection.setInstanceFollowRedirects(false);
                    for (String header : headers.keySet()) connection.setRequestProperty(header, headers.getString(header));
                    Bundle response = new Bundle();
                    response.putInt("status", connection.getResponseCode());
                    Bundle fields = new Bundle();
                    for (Map.Entry<String, java.util.List<String>> field : connection.getHeaderFields().entrySet()) {
                        if (field.getKey() != null) fields.putStringArrayList(field.getKey(), new ArrayList<>(field.getValue()));
                    }
                    response.putBundle("headers", fields);
                    return response;
                } catch (Exception failure) {
                    connection.disconnect();
                    throw new IllegalStateException("E_WEB_NETWORK");
                }
            }

            @Override public ParcelFileDescriptor body() {
                verify(gallery);
                if (!requested.get() || !streaming.compareAndSet(false, true))
                    throw new IllegalStateException("E_WEB_REQUEST");
                try { return IsolatedPipes.send(connection.getInputStream(), connection::disconnect); }
                catch (IOException failure) { throw new IllegalStateException("E_WEB_NETWORK"); }
            }

            @Override public void close() {
                verify(gallery);
                connection.disconnect();
            }
        };
    }

    private static ParcelFileDescriptor output(String text, int limit) throws IOException {
        if (text == null || text.length() > limit) throw new IOException("E_ISOLATED_LIMIT");
        return output(text.getBytes(StandardCharsets.UTF_8), limit);
    }

    private static ParcelFileDescriptor output(byte[] data, int limit) throws IOException {
        if (data == null) throw new IOException("E_ISOLATED_PIPE");
        if (data.length > limit) {
            Arrays.fill(data, (byte) 0);
            throw new IOException("E_ISOLATED_LIMIT");
        }
        return IsolatedPipes.send(data);
    }

    private static void checkRequest(String json) {
        if (json == null || json.length() > LIMIT_REQUEST) throw new IllegalArgumentException("E_ISOLATED_REQUEST");
    }

    private static void verify(int gallery) {
        if (Binder.getCallingUid() != gallery) throw new SecurityException("E_ISOLATED_CALLER");
    }
}
