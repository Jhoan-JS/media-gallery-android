package local.ttlab.gallery;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.UserManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WebSource extends Source {
    static final String KEY = "web-rule-thirty-four";
    static final String GELBOORU = "web-gelbooru";
    private static final String SETTINGS = "local.ttlab.web.SettingsActivity";
    private static final String PROVIDER = "local.ttlab.web.WebProvider";
    private static final Endpoint LEGACY = new Endpoint("local.ttlab.web", "local.ttlab.web.bridge",
            "local.ttlab.permission.WEB");
    private static final Endpoint COMMON = new Endpoint(BuildConfig.TELEGRAM_PACKAGE,
            BuildConfig.TELEGRAM_PACKAGE + ".web.bridge", "local.ttlab.permission.TELEGRAM_WEB");
    private static final int RANGE = 4 * CHUNK;
    private final Context context;
    private final Vault vault;
    private final ContentResolver resolver;
    private final PackageManager packages;
    private final boolean gelbooru;
    private final String liveId;
    private final ExecutorService cancellations = Executors.newSingleThreadExecutor();
    private volatile Endpoint selected;

    private static final class Endpoint {
        final String pkg, permission;
        final Uri bridge;

        Endpoint(String pkg, String authority, String permission) {
            this.pkg = pkg;
            this.permission = permission;
            this.bridge = Uri.parse("content://" + authority);
        }
    }

    WebSource(Context context, Vault vault, Library library, String source, String liveId, WebSource saved) {
        super(context, vault, library, LEGACY.pkg, LEGACY.bridge.getAuthority(), true, "Web");
        this.context = context.getApplicationContext();
        this.vault = vault;
        resolver = context.getContentResolver();
        packages = context.getPackageManager();
        gelbooru = GELBOORU.equals(source);
        this.liveId = liveId;
        if (saved != null) selected = saved.selected;
    }

    Intent configure() throws Exception {
        guardProfile();
        Endpoint endpoint = trusted(COMMON) ? COMMON : LEGACY;
        guard(endpoint);
        ComponentName component = new ComponentName(endpoint.pkg, SETTINGS);
        ActivityInfo info = packages.getActivityInfo(component, 0);
        if (internal(endpoint) ? info.exported : !info.exported ||
                (endpoint == COMMON && !endpoint.permission.equals(info.permission))) {
            throw new IOException("E_WEB_CONNECTOR");
        }
        Intent settings = new Intent().setComponent(component).putExtra("site", gelbooru ? "gelbooru" : "rule34");
        if (endpoint == COMMON) {
            byte[] binding = vault.secret("source-account");
            if (binding != null) {
                try { settings.putExtra("expected-account", new String(binding, StandardCharsets.US_ASCII)); }
                finally { Arrays.fill(binding, (byte) 0); }
            }
        }
        return settings;
    }

    private void guardProfile() {
        if (!Profiles.current() || !context.getSystemService(UserManager.class).isUserForeground()) {
            throw new SecurityException("Abre la instalación en su usuario Android activo");
        }
    }

    private boolean trusted(Endpoint endpoint) {
        ProviderInfo info = packages.resolveContentProvider(endpoint.bridge.getAuthority(), 0);
        if (info == null || !endpoint.pkg.equals(info.packageName) || !PROVIDER.equals(info.name) ||
                info.grantUriPermissions) return false;
        if (internal(endpoint)) return !info.exported && info.applicationInfo.uid == android.os.Process.myUid();
        return info.exported
                && endpoint.permission.equals(info.readPermission) && endpoint.permission.equals(info.writePermission)
                && packages.checkSignatures(context.getPackageName(), endpoint.pkg) == PackageManager.SIGNATURE_MATCH
                && packages.checkPermission(endpoint.permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean internal(Endpoint endpoint) {
        return BuildConfig.ISOLATED_CLIENT && endpoint == COMMON && endpoint.pkg.equals(context.getPackageName());
    }

    private boolean legacySupportsSite() throws PackageManager.NameNotFoundException {
        return !gelbooru || packages.getPackageInfo(LEGACY.pkg, 0).getLongVersionCode() >= 7;
    }

    private void guard(Endpoint endpoint) throws Exception {
        guardProfile();
        if (!trusted(endpoint)) throw new IOException("E_WEB_CONNECTOR");
        if (endpoint == LEGACY && !legacySupportsSite()) throw new IOException("E_WEB_UPDATE");
    }

    // Only refresh on library entry or after explicit setup. Active viewers and live sessions stay pinned.
    synchronized void refreshEndpoint() throws Exception {
        guardProfile();
        if (liveId != null && selected != null) return;
        if (!trusted(COMMON)) {
            selected = LEGACY;
            return;
        }
        boolean configured;
        try (Cursor status = status(COMMON)) {
            configured = status.getInt(0) == 1 || status.getExtras().getBoolean("configured");
        }
        if (!configured && trusted(LEGACY) && legacySupportsSite()) {
            try (Cursor status = status(LEGACY)) {
                if (status.getInt(0) == 1) {
                    selected = LEGACY;
                    return;
                }
            } catch (IOException | SecurityException unavailable) { /* Use the common settings to reconnect. */ }
        }
        selected = COMMON;
    }

    private synchronized Endpoint endpoint() throws Exception {
        if (selected == null) refreshEndpoint();
        return selected;
    }

    @Override void guard() throws Exception { guard(endpoint()); }

    private Cursor status(Endpoint endpoint) throws Exception {
        Cursor row = query(endpoint.bridge.buildUpon().appendPath("status").build(), null, endpoint);
        if (row.moveToFirst()) return row;
        row.close();
        throw new IOException("E_WEB_RESPONSE");
    }

    boolean enabled() throws Exception {
        try (Cursor row = status(endpoint())) {
            return row.getInt(0) == 1;
        }
    }

    static final class Result {
        final List<Item> posts = new ArrayList<>();
        final List<String> tags = new ArrayList<>();
        int next;
    }

    Result search(String tags, int page, CancellationSignal cancel) throws Exception {
        Uri target = uri("search").buildUpon().appendQueryParameter("tags", tags)
                .appendQueryParameter("page", Integer.toString(page)).build();
        Result result = new Result();
        try (Cursor rows = query(target, cancel)) {
            while (rows.moveToNext()) {
                if (result.posts.size() >= 30) throw new IOException("E_WEB_RESPONSE");
                Item item = Item.cursor(rows);
                if (item.channel != 1 || item.id < 1 || !item.revision.matches("[0-9a-f]{64}")) {
                    throw new IOException("E_WEB_RESPONSE");
                }
                result.posts.add(item);
                result.tags.add(rows.getString(11));
            }
            result.next = rows.getExtras().getInt("next", -1);
        }
        if (result.next != -1 && (result.next != page + 1 || result.next >= 40)) {
            throw new IOException("E_WEB_RESPONSE");
        }
        return result;
    }

    Item prepare(Item item, CancellationSignal cancel) throws Exception {
        Uri target = uri("describe/" + item.id).buildUpon().appendQueryParameter("revision", item.revision).build();
        try (Cursor row = query(target, cancel)) {
            if (!row.moveToFirst()) throw new IOException("E_WEB_MISSING");
            long size = row.getLong(0);
            String version = row.getString(1);
            if (size < 1 || size > 4L * 1024 * 1024 * 1024 || !version.matches("[0-9a-f]{64}")) {
                throw new IOException("E_WEB_RESPONSE");
            }
            return item.webRepresentation(size, version);
        }
    }

    @Override protected Cursor query(Uri target) throws Exception { return query(target, null); }

    private Cursor query(Uri target, CancellationSignal cancel) throws Exception {
        return query(target, cancel, endpoint());
    }

    private Cursor query(Uri target, CancellationSignal cancel, Endpoint endpoint) throws Exception {
        guard(endpoint);
        Cursor row = resolver.query(siteUri(target, endpoint), null, null, null, null, cancel);
        if (row == null) throw new IOException("E_WEB_CONNECTOR");
        try {
            String code = row.getExtras().getString("error");
            if (code != null) throw new IOException(code);
            return row;
        } catch (Exception error) {
            row.close();
            throw error;
        }
    }

    @Override int playbackBatch() { return 4; }
    @Override int playbackReaders() { return 2; }
    @Override synchronized int rangeSize() { return RANGE; }

    @Override byte[] thumbnail(Item item) throws Exception {
        return read(item, 0, -1, new CancellationSignal());
    }

    @Override byte[] chunk(Item item, int index) throws Exception {
        long offset = (long) index * CHUNK;
        if (index < 0 || offset >= item.size || item.webVersion.isEmpty()) throw new IOException("E_WEB_RANGE");
        return read(item, offset, (int) Math.min(CHUNK, item.size - offset), new CancellationSignal());
    }

    @Override void rangeBlocks(Item item, int first, int count, CancellationSignal cancel,
                               BlockConsumer consumer) throws Exception {
        long offset = (long) first * CHUNK;
        int length = (int) Math.min((long) count * CHUNK, item.size - offset);
        if (first < 0 || count < 1 || count > 4 || length < 1) throw new IOException("E_WEB_RANGE");
        byte[] bytes = read(item, offset, length, cancel);
        for (int start = 0; start < length; start += CHUNK) {
            cancel.throwIfCanceled();
            consumer.accept(first + start / CHUNK,
                    java.util.Arrays.copyOfRange(bytes, start, Math.min(start + CHUNK, length)));
        }
    }

    private byte[] read(Item item, long offset, int length, CancellationSignal cancel) throws Exception {
        boolean thumbnail = length == -1 && offset == 0;
        if (!thumbnail && (offset < 0 || length < 1 || length > RANGE || item.webVersion.isEmpty())) {
            throw new IOException("E_WEB_RANGE");
        }
        final String token = UUID.randomUUID().toString();
        Uri.Builder builder = uri((thumbnail ? "thumb/" : "range/") + item.id).buildUpon()
                .appendQueryParameter("revision", item.revision).appendQueryParameter("request", token);
        if (!thumbnail) builder.appendQueryParameter("version", item.webVersion)
                .appendQueryParameter("offset", Long.toString(offset))
                .appendQueryParameter("length", Integer.toString(length));
        int max = thumbnail ? CHUNK : length;
        Endpoint endpoint = endpoint();
        guard(endpoint);
        cancel.throwIfCanceled();
        try (InputStream input = openStream(siteUri(builder.build(), endpoint), cancel)) {
            if (input == null) throw new IOException("E_WEB_CONNECTOR");
            cancel.setOnCancelListener(() -> cancellations.execute(() -> {
                try { resolver.call(endpoint.bridge, "cancel", token, null); } catch (RuntimeException ignored) { }
                try { input.close(); } catch (IOException ignored) { }
            }));
            cancel.throwIfCanceled();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(max, 8192));
            byte[] block = new byte[8192];
            int read;
            while ((read = input.read(block)) != -1) {
                cancel.throwIfCanceled();
                if (output.size() + read > max) throw new IOException("E_WEB_RANGE");
                output.write(block, 0, read);
            }
            if (output.size() == 0 || (!thumbnail && output.size() != length)) {
                throw new IOException("E_WEB_RESPONSE");
            }
            return output.toByteArray();
        } finally { cancel.setOnCancelListener(null); }
    }

    void beginLive() throws Exception {
        Endpoint endpoint = endpoint();
        guard(endpoint);
        Bundle reply = resolver.call(endpoint.bridge, "beginGelbooru", liveId, null);
        if (reply == null || !reply.getBoolean("active")) throw new IOException("E_WEB_DISABLED");
    }

    void endLive() {
        Endpoint endpoint = selected;
        try { if (endpoint != null) resolver.call(endpoint.bridge, "endGelbooru", liveId, null); }
        catch (RuntimeException ignored) { }
        cancellations.shutdown();
    }

    private Uri siteUri(Uri target, Endpoint endpoint) {
        Uri.Builder builder = target.buildUpon().authority(endpoint.bridge.getAuthority());
        if (!gelbooru) return builder.build();
        builder.appendQueryParameter("site", "gelbooru");
        if (liveId != null) builder.appendQueryParameter("live", liveId);
        return builder.build();
    }

    private static String errorCode(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String message = failure.getMessage();
            if (message == null) continue;
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("\\bE_WEB_[A-Z0-9_]{1,30}\\b").matcher(message);
            if (code.find()) return code.group();
        }
        return "";
    }

    static String message(Throwable failure) {
        String code = errorCode(failure);
        if (code.equals("E_WEB_MEDIA_GATE")) return "Gelbooru no entregó el archivo para mostrarlo aquí. Código WEB-MEDIOS.";
        if (code.equals("E_WEB_MEDIA_REDIRECT")) return "El servidor redirigió el archivo fuera de las rutas de medios admitidas.";
        if (code.equals("E_WEB_API_REDIRECT")) return "El sitio redirigió la consulta del catálogo. Comprueba la conexión de la fuente.";
        if (code.equals("E_WEB_CATALOGUE_FORMAT")) return "No se reconoció la respuesta del catálogo. Código WEB-FORMATO.";
        if (code.equals("E_WEB_CATALOGUE_MEDIA")) return "El catálogo respondió, pero esta página no incluye enlaces de medios compatibles. Código WEB-ENLACES.";
        if (code.equals("E_WEB_CATALOGUE")) return "El sitio rechazó la consulta del catálogo. Código WEB-CATÁLOGO.";
        if (code.startsWith("E_WEB_HTTP_")) return "El sitio respondió con HTTP " + code.substring("E_WEB_HTTP_".length()) + ".";
        if (code.equals("E_WEB_MISSING")) return "El sitio no encontró la publicación o el archivo.";
        if (code.equals("E_WEB_MEDIA_TYPE")) return "El enlace del archivo no devolvió un formato multimedia compatible.";
        if (code.equals("E_WEB_SIZE") || code.equals("E_WEB_IMAGE_SIZE")) return "El archivo supera el límite de lectura en memoria.";
        if (code.equals("E_WEB_CHANGED") || code.equals("E_WEB_REOPEN")) return "El archivo cambió o la vista caducó. Regresa a la lista y ábrelo de nuevo.";
        if (code.equals("E_WEB_NETWORK")) return "No se pudo conectar al sitio. Código WEB-RED.";
        if (code.equals("E_WEB_RANGE")) return "El servidor no entregó el tramo solicitado del archivo. Código WEB-TRAMOS.";
        if (code.contains("E_WEB_UPDATE")) return "Actualiza Conector Web a la versión 1.6 o posterior para usar Gelbooru.";
        if (code.contains("E_WEB_NO_RANGES")) return "El servidor no permite adelantar este video.";
        if (code.contains("E_WEB_LIMIT") || code.contains("E_WEB_BUSY")) return "El sitio limitó las solicitudes. Espera y reintenta.";
        if (code.contains("E_WEB_LIVE_CLIENT")) return "Vuelve a abrir Gelbooru para renovar la sesión.";
        if (code.contains("E_WEB_NO_STORE")) return "La respuesta del sitio no permite guardar estos datos en caché.";
        if (code.equals("E_WEB_VIDEO_UNVERIFIED_SIZE")) return "Gelbooru no confirmó los tramos de este video y es demasiado grande para cargarlo completo en memoria.";
        if (code.equals("E_WEB_MEDIA_VERSION")) return "El servidor no confirmó la versión del archivo.";
        if (code.contains("E_WEB_ACCESS")) return "El sitio rechazó el acceso. Revisa la autorización.";
        if (code.equals("E_WEB_CONNECTOR")) return "Instala Conector de fuentes con la misma firma en este perfil.";
        if (code.equals("E_WEB_DISABLED")) return BuildConfig.ISOLATED_CLIENT
                ? "Habilita la fuente desde Configurar conexión."
                : "Habilita la fuente en Conector de fuentes.";
        if (code.contains("E_WEB_TAGS")) return "Usa de una a ocho etiquetas. Separa los términos con espacios.";
        if (code.contains("E_WEB_ACCOUNT")) return "La fuente vinculada cambió. Revisa la configuración.";
        return code.isEmpty() ? "No se pudo abrir el archivo web. Código WEB-LECTURA."
                : "No se pudo leer la respuesta del sitio. Código " + code + ".";
    }
}
