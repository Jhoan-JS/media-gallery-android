package local.ttlab.web;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.UserManager;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class WebSettings {
    static final String GALLERY = "local.ttlab.gallery";
    static final String PERMISSION = "local.ttlab.permission.WEB";
    private static final java.util.EnumMap<WebSite, WebSettings> INSTANCES = new java.util.EnumMap<>(WebSite.class);
    final WebSite site;
    final WebNetwork network;
    private final SecureStore store;
    private Config config;

    static final class Config {
        final String user, key, account;
        final boolean enabled;
        Config(String user, String key, String account, boolean enabled) {
            this.user = user;
            this.key = key;
            this.account = account;
            this.enabled = enabled;
        }
    }

    private WebSettings(Context context, WebSite site) throws Exception {
        this.site = site;
        network = new WebNetwork(context, site);
        store = new SecureStore(context, site.directory);
        byte[] bytes = store.get("settings");
        if (bytes != null) {
            try {
                JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                config = new Config(value.getString("user"), value.getString("key"),
                        value.getString("account"), value.getBoolean("enabled"));
            } finally { java.util.Arrays.fill(bytes, (byte) 0); }
        }
    }

    static synchronized WebSettings get(Context context, WebSite site) throws Exception {
        WebSettings settings = INSTANCES.get(site);
        if (settings == null) {
            settings = new WebSettings(context.getApplicationContext(), site);
            INSTANCES.put(site, settings);
        }
        return settings;
    }

    static void requireCaller(Context context, String name, int uid) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        if (!GALLERY.equals(name) || uid / 100000 != Process.myUid() / 100000 ||
                !context.getSystemService(UserManager.class).isUserForeground() ||
                pm.checkSignatures(context.getPackageName(), name) !=
                        android.content.pm.PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("E_WEB_CALLER");
        }
        try {
            if (pm.getPackageUid(name, 0) != uid) throw new SecurityException("E_WEB_CALLER");
        } catch (android.content.pm.PackageManager.NameNotFoundException unknown) {
            throw new SecurityException("E_WEB_CALLER");
        }
    }

    static void requireProvider(Context context, String name) {
        context.enforceCallingPermission(PERMISSION, "E_WEB_CALLER");
        requireCaller(context, name, Binder.getCallingUid());
    }

    synchronized Config config() { return config; }

    synchronized Config active(String account) {
        if (config == null || !config.enabled) throw new SecurityException("E_WEB_DISABLED");
        if (account != null && !config.account.equals(account)) throw new SecurityException("E_WEB_ACCOUNT");
        return config;
    }

    synchronized long generation(Config expected) {
        if (expected != config || !expected.enabled) throw new SecurityException("E_WEB_DISABLED");
        return network.generation();
    }

    synchronized void enable(String user, String key) throws Exception {
        if (!user.matches("[1-9][0-9]{0,18}") || !key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("E_WEB_CREDENTIALS");
        }
        if (config != null && !config.user.equals(user)) throw new IllegalArgumentException("E_WEB_ACCOUNT");
        save(new Config(user, key, WebNetwork.digest(site.id + ":" + user), true));
    }

    synchronized void disable() throws Exception {
        if (config != null) save(new Config(config.user, "", config.account, false));
    }

    private void save(Config next) throws Exception {
        byte[] bytes = new JSONObject().put("user", next.user).put("key", next.key)
                .put("account", next.account).put("enabled", next.enabled).toString()
                .getBytes(StandardCharsets.UTF_8);
        try {
            store.put("settings", bytes);
            config = next;
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
            network.revoke();
        }
    }
}
