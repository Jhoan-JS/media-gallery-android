package local.ttlab.web;

import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class WebNetwork {
    private final android.content.Context context;

    private final WebSite site;

    WebNetwork(android.content.Context context, WebSite site) {
        this.context = context;
        this.site = site;
    }
    private final Set<HttpURLConnection> active = new HashSet<>();
    private final LinkedHashMap<Integer, Post> known = new LinkedHashMap<>(32, .75f, true);
    private long epoch;
    private long nextApi;
    private long nextMedia;
    private final Object imageLock = new Object();
    private volatile FullImage lastImage;

    private static final class FullImage {
        final long generation;
        final String version;
        final byte[] bytes;
        FullImage(long generation, String version, byte[] bytes) {
            this.generation=generation; this.version=version; this.bytes=bytes;
        }
    }

    static final class Post {
        final int id, width, height;
        final long date;
        final String tags, mime, revision;
        final URI file, thumb;
        Post(WebSite site, JSONObject row) throws Exception {
            id = row.getInt("id");
            if (id <= 0 || !site.acceptsRating(row.optString("rating"))) {
                throw new IllegalArgumentException("E_WEB_POST");
            }
            file = WebPolicy.media(site, row.getString("file_url"));
            mime = WebPolicy.mime(file);
            if (mime.isEmpty()) throw new IllegalArgumentException("E_WEB_MEDIA_TYPE");
            URI preview = null;
            try {
                URI candidate = WebPolicy.media(site, row.optString("preview_url"));
                if (WebPolicy.mime(candidate).startsWith("image/")) preview = candidate;
            } catch (IllegalArgumentException missing) { /* A preview is optional. */ }
            thumb = preview;
            width = Math.max(0, Math.min(16384, row.optInt("width")));
            height = Math.max(0, Math.min(16384, row.optInt("height")));
            long timestamp = 0;
            JSONObject change = row.optJSONObject("change");
            if (change == null) timestamp = row.optLong("change");
            if (timestamp == 0 && site == WebSite.GELBOORU) {
                try {
                    timestamp = java.time.ZonedDateTime.parse(row.optString("created_at"),
                            java.time.format.DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z uuuu", Locale.US))
                            .toEpochSecond();
                } catch (java.time.DateTimeException ignored) { /* Date is optional. */ }
            }
            date = timestamp >= 1_000_000_000L && timestamp <= 4_102_444_800L ? timestamp : 0;
            String text = row.optString("tags").replaceAll("[\\p{Cntrl}]", " ");
            tags = text.substring(0, Math.min(2048, text.length()));
            revision = digest(file.toASCIIString());
        }
    }

    static final class Page {
        final List<Post> posts;
        final boolean more;
        Page(List<Post> posts, boolean more) {
            this.posts = posts;
            this.more = more;
        }
    }

    static final class Representation {
        final long length;
        final String validator, version;
        final boolean fullImage;
        Representation(long length, String validator, String version, boolean fullImage) {
            this.length = length;
            this.validator = validator;
            this.version = version;
            this.fullImage = fullImage;
        }
    }

    synchronized long generation() { return epoch; }

    void revoke() {
        List<HttpURLConnection> pending;
        synchronized (this) {
            epoch++;
            known.clear();
            lastImage = null;
            pending = new ArrayList<>(active);
            active.clear();
        }
        for (HttpURLConnection connection : pending) connection.disconnect();
    }

    synchronized void current(long generation) throws IOException {
        if (epoch != generation) throw new IOException("E_WEB_DISABLED");
    }

    Page search(WebSettings.Config config, long generation, String tags, int page,
                CancellationSignal cancel) throws Exception {
        if (page < 0 || page >= WebPolicy.MAX_PAGES) throw new IllegalArgumentException("E_WEB_PAGE");
        JSONArray rows = request(config, generation, "tags=" + encode(WebPolicy.query(site, tags)) +
                "&pid=" + page + "&limit=" + WebPolicy.PAGE_SIZE, cancel);
        ArrayList<Post> posts = new ArrayList<>();
        Set<Integer> encountered = new HashSet<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            try {
                Post post = new Post(site, row);
                if (encountered.add(post.id)) posts.add(post);
            } catch (IllegalArgumentException | org.json.JSONException ignored) {
                // Unsupported or unclassified entries do not leave the connector.
            }
        }
        synchronized (this) {
            current(generation);
            for (Post post : posts) remember(post);
        }
        return new Page(posts, rows.length() == WebPolicy.PAGE_SIZE && page + 1 < WebPolicy.MAX_PAGES);
    }

    Post lookup(WebSettings.Config config, long generation, int id, String revision,
                CancellationSignal cancel) throws Exception {
        Post post;
        synchronized (this) {
            current(generation);
            post = known.get(id);
        }
        if (post == null) {
            JSONArray rows = request(config, generation, "id=" + id + "&limit=1&tags=" + encode("rating:" + site.rating), cancel);
            if (rows.length() != 1) throw new IOException("E_WEB_MISSING");
            post = new Post(site, rows.getJSONObject(0));
            if (post.id != id) throw new IOException("E_WEB_MISSING");
            synchronized (this) {
                current(generation);
                remember(post);
            }
        }
        if (!post.revision.equals(revision)) throw new IOException("E_WEB_CHANGED");
        return post;
    }

    private void remember(Post post) {
        known.put(post.id, post);
        if (known.size() > 512) known.remove(known.keySet().iterator().next());
    }

    private JSONArray request(WebSettings.Config config, long generation, String parameters,
                              CancellationSignal cancel) throws Exception {
        awaitSlot(generation, cancel, true);
        URI url = URI.create(site.api + "?page=dapi&s=post&q=index&json=1&" + parameters
                + "&user_id=" + encode(config.user) + "&api_key=" + encode(config.key));
        try (Exchange exchange = open(url, generation, cancel)) {
            HttpURLConnection connection = exchange.connection;
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            checkStatus(connection, status, true);
            if (status != 200) throw new IOException("E_WEB_API");
            checkCachePolicy(connection);
            String payload = new String(read(connection, 2 * 1024 * 1024, -1, cancel),
                    StandardCharsets.UTF_8).trim();
            JSONArray rows = site.posts(payload);
            if (rows.length() > WebPolicy.PAGE_SIZE) throw new IOException("E_WEB_API");
            return rows;
        }
    }

    Representation describe(Post post, long generation, CancellationSignal cancel) throws Exception {
        awaitSlot(generation, cancel, false);
        try (Exchange exchange = open(post.file, generation, cancel)) {
            HttpURLConnection connection = exchange.connection;
            connection.setRequestProperty("Range", "bytes=0-0");
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, post.mime);
            boolean full = status == 200 && post.mime.startsWith("image/");
            long length;
            if (full) {
                length = connection.getContentLengthLong();
                if (length < 1 || length > 16 * 1024 * 1024) {
                    throw new IOException("E_WEB_IMAGE_SIZE");
                }
            } else {
                length = WebPolicy.contentRange(status, connection.getHeaderField("Content-Range"), 0, 1);
            }
            String etag = connection.getHeaderField("ETag");
            if (etag == null || !etag.matches("\"[^\"\\p{Cntrl}]{1,160}\"")) {
                throw new IOException("E_WEB_MEDIA_VERSION");
            }
            return new Representation(length, etag, digest(post.revision + "|" + etag + "|" + length), full);
        }
    }

    byte[] range(Post post, Representation rep, long generation, long offset, int length,
                 CancellationSignal cancel) throws Exception {
        if (offset < 0 || length < 1 || length > WebPolicy.RANGE || offset > rep.length - length) {
            throw new IOException("E_WEB_RANGE");
        }
        if (rep.fullImage) return imageRange(post, rep, generation, offset, length, cancel);
        awaitSlot(generation, cancel, false);
        try (Exchange exchange = open(post.file, generation, cancel)) {
            HttpURLConnection connection = exchange.connection;
            connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
            connection.setRequestProperty("If-Match", rep.validator);
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, post.mime);
            if (!rep.validator.equals(connection.getHeaderField("ETag"))) throw new IOException("E_WEB_CHANGED");
            long total = WebPolicy.contentRange(status, connection.getHeaderField("Content-Range"), offset, length);
            if (total != rep.length) throw new IOException("E_WEB_CHANGED");
            return read(connection, length, length, cancel);
        }
    }

    private byte[] imageRange(Post post, Representation rep, long generation, long offset, int length,
                              CancellationSignal cancel) throws Exception {
        synchronized (imageLock) {
            current(generation);
            FullImage image = lastImage;
            if (image == null || image.generation != generation || !image.version.equals(rep.version)) {
                awaitSlot(generation, cancel, false);
                try (Exchange exchange = open(post.file, generation, cancel)) {
                    HttpURLConnection connection = exchange.connection;
                    connection.setRequestProperty("If-Match", rep.validator);
                    int status = connection.getResponseCode();
                    checkStatus(connection, status, false);
                    validateType(connection, post.mime);
                    if (status != 200 || !rep.validator.equals(connection.getHeaderField("ETag")) ||
                            connection.getContentLengthLong() != rep.length) throw new IOException("E_WEB_CHANGED");
                    byte[] bytes = read(connection, (int) rep.length, (int) rep.length, cancel);
                    image = new FullImage(generation, rep.version, bytes);
                    synchronized (this) { current(generation); lastImage = image; }
                }
            }
            cancel.throwIfCanceled();
            return java.util.Arrays.copyOfRange(image.bytes, (int) offset, (int) offset + length);
        }
    }

    byte[] thumbnail(Post post, long generation, CancellationSignal cancel) throws Exception {
        if (post.thumb == null) throw new IOException("E_WEB_MISSING");
        awaitSlot(generation, cancel, false);
        try (Exchange exchange = open(post.thumb, generation, cancel)) {
            HttpURLConnection connection = exchange.connection;
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, WebPolicy.mime(post.thumb));
            if (status != 200) throw new IOException("E_WEB_THUMBNAIL");
            return read(connection, WebPolicy.THUMB, -1, cancel);
        }
    }

    private void checkStatus(HttpURLConnection connection, int status, boolean api) throws IOException {
        if (status == 401 || status == 403) throw new IOException("E_WEB_ACCESS");
        if (status == 404) throw new IOException("E_WEB_MISSING");
        if (status == 412) throw new IOException("E_WEB_CHANGED");
        if (status == 429 || status == 503) {
            long pause = 60_000;
            String retry = connection.getHeaderField("Retry-After");
            if (retry != null) {
                try {
                    long seconds = retry.matches("[0-9]{1,10}") ? Long.parseLong(retry)
                            : (java.time.ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - System.currentTimeMillis() + 999) / 1000;
                    pause = Math.max(1, Math.min(seconds, 86400L * 365)) * 1000;
                } catch (RuntimeException ignored) { /* Default cooldown. */ }
            }
            synchronized (this) {
                long retryAt = SystemClock.elapsedRealtime() + pause;
                if (api) nextApi = Math.max(nextApi, retryAt);
                else nextMedia = Math.max(nextMedia, retryAt);
            }
            throw new IOException("E_WEB_LIMIT");
        }
        if (status >= 300 && status < 400) throw new IOException("E_WEB_REDIRECT");
    }

    private void awaitSlot(long generation, CancellationSignal cancel, boolean api) throws IOException {
        for (;;) {
            cancel.throwIfCanceled();
            synchronized (this) {
                current(generation);
                long now = SystemClock.elapsedRealtime();
                long next = api ? nextApi : nextMedia;
                if (next <= now) {
                    if (api) nextApi = now + 1250;
                    else nextMedia = now + 180;
                    return;
                }
                if (next - now > 5000) throw new IOException("E_WEB_LIMIT");
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("E_WEB_CANCELED");
            SystemClock.sleep(90);
        }
    }

    private Exchange open(URI uri, long generation, CancellationSignal cancel) throws Exception {
        cancel.throwIfCanceled();
        HttpURLConnection connection = VariantTransport.open(context, uri);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "MediaGallery-Android/2.20 (optional web connector)");
        synchronized (this) {
            current(generation);
            if (active.size() >= 6) throw new IOException("E_WEB_BUSY");
            active.add(connection);
        }
        cancel.setOnCancelListener(connection::disconnect);
        return new Exchange(connection, cancel);
    }

    private final class Exchange implements AutoCloseable {
        final HttpURLConnection connection;
        final CancellationSignal cancel;
        Exchange(HttpURLConnection connection, CancellationSignal cancel) {
            this.connection = connection;
            this.cancel = cancel;
        }
        @Override public void close() {
            cancel.setOnCancelListener(null);
            connection.disconnect();
            synchronized (WebNetwork.this) { active.remove(connection); }
        }
    }

    private static void checkCachePolicy(HttpURLConnection connection) throws IOException {
        String policy = connection.getHeaderField("Cache-Control");
        if (policy != null && policy.toLowerCase(Locale.ROOT).contains("no-store")) throw new IOException("E_WEB_NO_STORE");
    }

    private static void validateType(HttpURLConnection connection, String expected) throws IOException {
        checkCachePolicy(connection);
        String type = connection.getContentType();
        String encoding = connection.getContentEncoding();
        if (type == null || !expected.equals(type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                || (encoding != null && !"identity".equalsIgnoreCase(encoding))) {
            throw new IOException("E_WEB_MEDIA_TYPE");
        }
    }

    private static byte[] read(HttpURLConnection connection, int max, int expected,
                               CancellationSignal cancel) throws IOException {
        long length = connection.getContentLengthLong();
        if (length > max || (length >= 0 && expected >= 0 && length != expected)) {
            throw new IOException("E_WEB_SIZE");
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(max, 8192))) {
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) {
                cancel.throwIfCanceled();
                if (buffer.size() + count > max) throw new IOException("E_WEB_SIZE");
                buffer.write(block, 0, count);
            }
            if (buffer.size() == 0 || (expected >= 0 && buffer.size() != expected)) throw new IOException("E_WEB_SIZE");
            return buffer.toByteArray();
        }
    }

    private static String encode(String value) {
        return android.net.Uri.encode(value);
    }

    static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
