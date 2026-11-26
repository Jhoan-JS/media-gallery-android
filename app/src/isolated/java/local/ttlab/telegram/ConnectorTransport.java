package local.ttlab.telegram;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import local.ttlab.isolated.ISessionStore;
import local.ttlab.isolated.ITelegramSession;
import local.ttlab.isolated.IsolatedPipes;
import local.ttlab.isolated.SourceWorkerConnection;

final class ConnectorTransport {
    private static final int MAX_SESSION = 1 << 20;
    private static final int MAX_JSON = 8 << 20;

    private final SourceWorkerConnection connection;
    private final ITelegramSession session;
    private final SessionStorage storage;

    private ConnectorTransport(SourceWorkerConnection connection, ITelegramSession session, SessionStorage storage) {
        this.connection = connection;
        this.session = session;
        this.storage = storage;
    }

    static ConnectorTransport open(Context context, long apiId, String apiHash, SecureStore store) throws Exception {
        SourceWorkerConnection connection = SourceWorkerConnection.get(context);
        SessionStorage storage = new SessionStorage(connection, store);
        try {
            ITelegramSession session = connection.worker.openTelegram(apiId, apiHash, storage, connection.sockets);
            if (session == null) throw new IllegalStateException("E_TELEGRAM_ENGINE_UNAVAILABLE");
            return new ConnectorTransport(connection, session, storage);
        } catch (RemoteException disconnected) {
            storage.stop();
            throw new IOException("E_TELEGRAM_NETWORK_ERROR");
        } catch (RuntimeException failed) {
            storage.stop();
            throw failed;
        }
    }

    boolean available() {
        return storage.active && connection.isAlive() && session.asBinder().isBinderAlive();
    }

    String request(String request) throws Exception { return json(() -> session.request(request)); }
    String password(byte[] password) throws Exception { return json(() -> session.password(password)); }
    byte[] read(String request) throws Exception { return receive(() -> session.readMedia(request), BridgePolicy.MAX_RANGE); }

    void close() {
        storage.stop();
        try { session.close(); } catch (RemoteException disconnected) { /* The worker lost this session. */ }
    }

    private String json(Reply reply) throws Exception {
        byte[] data = receive(reply, MAX_JSON);
        try { return new String(data, StandardCharsets.UTF_8); }
        finally { Arrays.fill(data, (byte) 0); }
    }

    private byte[] receive(Reply reply, int limit) throws Exception {
        try {
            return IsolatedPipes.read(reply.open(), limit);
        } catch (RemoteException disconnected) {
            storage.stop();
            throw new IOException("E_TELEGRAM_NETWORK_ERROR");
        } catch (IOException failed) {
            if (available()) throw failed;
            storage.stop();
            throw new IOException("E_TELEGRAM_NETWORK_ERROR");
        }
    }

    private interface Reply {
        ParcelFileDescriptor open() throws RemoteException;
    }

    private static final class SessionStorage extends ISessionStore.Stub {
        private final SourceWorkerConnection connection;
        private final SecureStore store;
        private volatile boolean active = true;

        SessionStorage(SourceWorkerConnection connection, SecureStore store) {
            this.connection = connection;
            this.store = store;
        }

        synchronized void stop() { active = false; }

        private void verify() {
            connection.verifyWorker();
            if (!active) throw new SecurityException("E_ISOLATED_CALLER");
        }

        @Override public synchronized ParcelFileDescriptor readSession() {
            verify();
            try {
                byte[] data = store.loadSession();
                if (data.length > MAX_SESSION) {
                    Arrays.fill(data, (byte) 0);
                    throw new IllegalStateException("E_TELEGRAM_STORAGE");
                }
                return IsolatedPipes.send(data);
            } catch (Exception failed) {
                throw new IllegalStateException("E_TELEGRAM_STORAGE");
            }
        }

        @Override public void saveSession(ParcelFileDescriptor pipe) {
            byte[] data = null;
            try (ParcelFileDescriptor input = pipe) {
                verify();
                data = IsolatedPipes.read(input, MAX_SESSION);
                synchronized (this) {
                    verify();
                    store.saveSession(data);
                }
            } catch (SecurityException denied) {
                throw new SecurityException("E_ISOLATED_CALLER");
            } catch (Exception failed) {
                throw new IllegalStateException("E_TELEGRAM_STORAGE");
            } finally {
                if (data != null) Arrays.fill(data, (byte) 0);
            }
        }
    }
}
