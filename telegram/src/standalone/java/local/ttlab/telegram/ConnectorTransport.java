package local.ttlab.telegram;

import android.content.Context;
import tgengine.Client;
import tgengine.Store;
import tgengine.Tgengine;

final class ConnectorTransport {
    private final Client client;

    private ConnectorTransport(Client client) {
        this.client = client;
    }

    static ConnectorTransport open(Context context, long apiId, String apiHash, SecureStore store) throws Exception {
        Store storage = new Store() {
            @Override public byte[] loadSession() throws Exception { return store.loadSession(); }
            @Override public void saveSession(byte[] session) throws Exception { store.saveSession(session); }
        };
        return new ConnectorTransport(Tgengine.newClient(apiId, apiHash, storage));
    }

    boolean available() { return true; }
    String request(String request) throws Exception { return client.request(request); }
    String password(byte[] password) throws Exception { return client.password(password); }
    byte[] read(String request) throws Exception { return client.read(request); }
    void close() { client.close(); }
}
