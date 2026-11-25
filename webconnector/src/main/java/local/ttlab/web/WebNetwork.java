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
    private static final int MAX_VIDEO_SNAPSHOT = 24 * 1024 * 1024;
    private final Object wholeLock = new Object();
    private volatile WholeMedia lastWhole;

    private static final class WholeMedia {
        final long generation;
        final String version;
        final byte[] bytes;
        WholeMedia(long generation, String version, byte[] bytes) {
            this.generation=generation; this.version=version; this.bytes=bytes;
        }
    }

    static final class Post {
        final int id, width, height;
        final long date;
        final String tags, mime, revision;
        final URI file, thumb, sample;
        Post(WebSite site, JSONObject row) throws Exception {
            id = row.getInt("id");
            if (id <= 0 || !site.acceptsRating(row.optString("rating"))) {
                throw new IllegalArgumentException("E_WEB_POST");
            }
            file = WebPolicy.media(site, row.getString("file_url"));
            mime = WebPolicy.mime(file);
            if (mime.isEmpty()) throw new IllegalArgumentException("E_WEB_MEDIA_TYPE");
            thumb = imageLink(site, row.optString("preview_url"));
            sample = site == WebSite.GELBOORU ? imageLink(site, row.optString("sample_url")) : null;
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

        private static URI imageLink(WebSite site, String address) {
            try {
                URI candidate = WebPolicy.media(site, address);
                return WebPolicy.mime(candidate).startsWith("image/") ? candidate : null;
            } catch (IllegalArgumentException unused) { return null; }
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
        final WebValidator validator;
        final String version;
        final boolean whole;
        Representation(long length, WebValidator validator, String version, boolean whole) {
            this.length = length;
            this.validator = validator;
            this.version = version;
            this.whole = whole;
        }
    }

    synchronized long generation() { return epoch; }

    void revoke() {
        List<HttpURLConnection> pending;
        synchronized (this) {
            epoch++;
            known.clear();
            lastWhole = null;
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
        String query = WebPolicy.query(site, tags);
        String parameters = "pid=" + page + "&limit=" + WebPolicy.PAGE_SIZE;
        // Gelbooru supports an empty tag query and already orders it newest-first.
        parameters += "&tags=" + encode(query);
        WebResponse response;
        try {
            response = request(config, generation, parameters, site != WebSite.GELBOORU, cancel);
        } catch (IOException format) {
            if (site != WebSite.GELBOORU || !"E_WEB_CATALOGUE_FORMAT".equals(format.getMessage())) throw format;
            response = request(config, generation, parameters, true, cancel);
        }
        JSONArray rows = response.posts;
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
        if (site == WebSite.GELBOORU && rows.length() > 0 && posts.isEmpty()) {
            throw new IOException("E_WEB_CATALOGUE_MEDIA");
        }
        synchronized (this) {
            current(generation);
            if (site == WebSite.GELBOORU && page == 0) {
                known.clear();
                lastWhole = null;
            }
            for (Post post : posts) remember(post);
        }
        return new Page(posts, response.more() && page + 1 < WebPolicy.MAX_PAGES);
    }

    Post lookup(WebSettings.Config config, long generation, int id, String revision,
                CancellationSignal cancel) throws Exception {
        Post post;
        synchronized (this) {
            current(generation);
            post = known.get(id);
        }
        if (post == null) {
            String parameters = "id=" + id + "&limit=1";
            if (!site.requiredRatingTag.isEmpty()) parameters += "&tags=" + encode(site.requiredRatingTag);
            JSONArray rows = request(config, generation, parameters, true, cancel).posts;
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

    private WebResponse request(WebSettings.Config config, long generation, String parameters,
                                boolean preferJson, CancellationSignal cancel) throws Exception {
        awaitSlot(generation, cancel, true);
        String format = preferJson ? "&json=1" : "";
        URI url = URI.create(site.api + "?page=dapi&s=post&q=index" + format + "&" + parameters
                + "&user_id=" + encode(config.user) + "&api_key=" + encode(config.key));
        try (Exchange exchange = open(url, generation, cancel)) {
            HttpURLConnection connection = exchange.connection;
            connection.setRequestProperty("Accept", preferJson ? "application/json" : "application/xml, text/xml, application/json");
            int status = connection.getResponseCode();
            checkStatus(connection, status, true);
            if (status != 200) throw new IOException("E_WEB_HTTP_" + status);
            checkCachePolicy(connection);
            String payload = new String(read(connection, 2 * 1024 * 1024, -1, cancel),
                    StandardCharsets.UTF_8).trim();
            return WebResponse.parse(site, payload);
        }
    }

    Representation describe(Post post, long generation, CancellationSignal cancel) throws Exception {
        boolean gelbooru = site == WebSite.GELBOORU;
        if (gelbooru && post.mime.startsWith("image/")) {
            synchronized (wholeLock) { return loadGelbooruImage(post, generation, cancel); }
        }
        long length;
        try (Exchange exchange = openMedia(post.file, generation, cancel, "bytes=0-0", null)) {
            HttpURLConnection connection = exchange.connection;
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, post.mime);
            if (gelbooru && status == 200) {
                synchronized (wholeLock) {
                    return snapshotVideo(post, connection, generation, -1, cancel);
                }
            }
            boolean full = status == 200 && post.mime.startsWith("image/");
            if (full) {
                length = connection.getContentLengthLong();
                if (length < 1 || length > 16 * 1024 * 1024) throw new IOException("E_WEB_IMAGE_SIZE");
            } else {
                length = WebPolicy.contentRange(status, connection.getHeaderField("Content-Range"), 0, 1);
            }
            WebValidator validator = WebValidator.from(connection.getHeaderField("ETag"),
                    connection.getHeaderField("Last-Modified"), connection.getHeaderField("Date"), gelbooru);
            if (validator != null) {
                String identity = "ETag".equals(validator.responseHeader)
                        ? validator.value : validator.responseHeader + "|" + validator.value;
                String version = digest(post.revision + "|" + identity + "|" + length);
                return new Representation(length, validator, version, full);
            }
            if (!gelbooru) throw new IOException("E_WEB_MEDIA_VERSION");
            if (length > MAX_VIDEO_SNAPSHOT) throw new IOException("E_WEB_VIDEO_UNVERIFIED_SIZE");
        }
        synchronized (wholeLock) { return loadGelbooruVideo(post, generation, length, cancel); }
    }

    byte[] range(Post post, Representation rep, long generation, long offset, int length,
                 CancellationSignal cancel) throws Exception {
        if (offset < 0 || length < 1 || length > WebPolicy.RANGE || offset > rep.length - length) {
            throw new IOException("E_WEB_RANGE");
        }
        if (rep.whole) return wholeRange(post, rep, generation, offset, length, cancel);
        String requestedRange = "bytes=" + offset + "-" + (offset + length - 1);
        try (Exchange exchange = openMedia(post.file, generation, cancel, requestedRange, rep.validator)) {
            HttpURLConnection connection = exchange.connection;
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, post.mime);
            if (!rep.validator.matches(connection)) throw new IOException("E_WEB_CHANGED");
            long total = WebPolicy.contentRange(status, connection.getHeaderField("Content-Range"), offset, length);
            if (total != rep.length) throw new IOException("E_WEB_CHANGED");
            return read(connection, length, length, cancel);
        }
    }

    private byte[] wholeRange(Post post, Representation rep, long generation, long offset, int length,
                              CancellationSignal cancel) throws Exception {
        synchronized (wholeLock) {
            current(generation);
            WholeMedia media = lastWhole;
            if (media == null || media.generation != generation || !media.version.equals(rep.version)) {
                if (site == WebSite.GELBOORU) {
                    Representation fresh = post.mime.startsWith("video/")
                            ? loadGelbooruVideo(post, generation, rep.length, cancel)
                            : loadGelbooruImage(post, generation, cancel);
                    media = lastWhole;
                    if (!rep.version.equals(fresh.version) || media == null || !rep.version.equals(media.version)) {
                        throw new IOException("E_WEB_CHANGED");
                    }
                } else {
                    try (Exchange exchange = openMedia(post.file, generation, cancel, null, rep.validator)) {
                        HttpURLConnection connection = exchange.connection;
                        int status = connection.getResponseCode();
                        checkStatus(connection, status, false);
                        validateType(connection, post.mime);
                        if (status != 200 || !rep.validator.matches(connection)
                                || connection.getContentLengthLong() != rep.length) throw new IOException("E_WEB_CHANGED");
                        byte[] bytes = read(connection, (int) rep.length, (int) rep.length, cancel);
                        media = new WholeMedia(generation, rep.version, bytes);
                        synchronized (this) { current(generation); lastWhole = media; }
                    }
                }
            }
            cancel.throwIfCanceled();
            return java.util.Arrays.copyOfRange(media.bytes, (int) offset, (int) offset + length);
        }
    }

    private Representation loadGelbooruVideo(Post post, long generation, long expected, CancellationSignal cancel)
            throws Exception {
        try (Exchange exchange = openMedia(post.file, generation, cancel, null, null)) {
            HttpURLConnection connection = exchange.connection;
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            validateType(connection, post.mime);
            if (status != 200) throw new IOException("E_WEB_HTTP_" + status);
            return snapshotVideo(post, connection, generation, expected, cancel);
        }
    }

    private Representation snapshotVideo(Post post, HttpURLConnection connection, long generation,
                                         long expected, CancellationSignal cancel) throws Exception {
        long declared = connection.getContentLengthLong();
        if (declared > MAX_VIDEO_SNAPSHOT || expected > MAX_VIDEO_SNAPSHOT) {
            throw new IOException("E_WEB_VIDEO_UNVERIFIED_SIZE");
        }
        if (declared >= 0 && expected >= 0 && declared != expected) throw new IOException("E_WEB_CHANGED");
        long length = expected >= 0 ? expected : declared;
        if (length == 0) throw new IOException("E_WEB_SIZE");
        byte[] bytes;
        if (length < 0) bytes = read(connection, MAX_VIDEO_SNAPSHOT, -1, cancel);
        else {
            bytes = new byte[(int) length];
            try (InputStream input = connection.getInputStream()) {
                int offset = 0;
                while (offset < bytes.length) {
                    cancel.throwIfCanceled();
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) throw new IOException("E_WEB_SIZE");
                    offset += count;
                }
                if (input.read() != -1) throw new IOException("E_WEB_SIZE");
            }
        }
        return rememberWhole(post, generation, bytes);
    }

    private Representation loadGelbooruImage(Post post, long generation, CancellationSignal cancel) throws Exception {
        byte[] bytes;
        try {
            bytes = fetchImage(post.file, generation, cancel, 16 * 1024 * 1024);
        } catch (IOException failure) {
            if (post.sample == null || post.sample.equals(post.file) || !mayUseAlternative(failure)) throw failure;
            bytes = fetchImage(post.sample, generation, cancel, 16 * 1024 * 1024);
        }
        return rememberWhole(post, generation, bytes);
    }

    private Representation rememberWhole(Post post, long generation, byte[] bytes) throws Exception {
        String kind = post.mime.startsWith("video/") ? "video" : "image";
        String version = digest(post.revision + "|" + kind + "|" + digest(bytes));
        synchronized (this) {
            current(generation);
            lastWhole = new WholeMedia(generation, version, bytes);
        }
        return new Representation(bytes.length, null, version, true);
    }

    private static boolean mayUseAlternative(IOException failure) {
        return "E_WEB_MISSING".equals(failure.getMessage()) || "E_WEB_SIZE".equals(failure.getMessage())
                || "E_WEB_MEDIA_TYPE".equals(failure.getMessage());
    }

    byte[] thumbnail(Post post, long generation, CancellationSignal cancel) throws Exception {
        if (post.thumb != null) {
            try {
                return fetchPreview(post.thumb, generation, cancel);
            } catch (IOException failure) {
                if (post.sample == null || post.sample.equals(post.thumb) || !mayUseAlternative(failure)) throw failure;
            }
        }
        if (post.sample == null) throw new IOException("E_WEB_MISSING");
        return fetchPreview(post.sample, generation, cancel);
    }

    private byte[] fetchPreview(URI address, long generation, CancellationSignal cancel) throws Exception {
        int downloadLimit = site == WebSite.GELBOORU ? 512 * 1024 : WebPolicy.THUMB;
        byte[] bytes = fetchImage(address, generation, cancel, downloadLimit);
        if (bytes.length <= WebPolicy.THUMB) return bytes;

        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int edge = Math.max(options.outWidth, options.outHeight);
        if (edge < 1 || edge > 16384) throw new IOException("E_WEB_MEDIA_TYPE");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (edge / options.inSampleSize > 512) options.inSampleSize *= 2;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("E_WEB_MEDIA_TYPE");
        try {
            ByteArrayOutputStream preview = new ByteArrayOutputStream(WebPolicy.THUMB);
            for (int quality : new int[]{80, 60}) {
                cancel.throwIfCanceled();
                preview.reset();
                if (bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, preview)
                        && preview.size() > 0 && preview.size() <= WebPolicy.THUMB) return preview.toByteArray();
            }
            throw new IOException("E_WEB_SIZE");
        } finally { bitmap.recycle(); }
    }

    private byte[] fetchImage(URI address, long generation, CancellationSignal cancel, int maximum) throws Exception {
        try (Exchange exchange = openMedia(address, generation, cancel, null, null)) {
            HttpURLConnection connection = exchange.connection;
            int status = connection.getResponseCode();
            checkStatus(connection, status, false);
            if (status != 200) throw new IOException("E_WEB_HTTP_" + status);
            validateType(connection, WebPolicy.mime(address));
            return read(connection, maximum, -1, cancel);
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
        if (status >= 300 && status < 400) {
            if (!api && site == WebSite.GELBOORU) {
                String location = connection.getHeaderField("Location");
                if (location != null) {
                    try {
                        URI target = URI.create(connection.getURL().toString()).resolve(location);
                        if (WebPolicy.gelbooruGate(target)) throw new IOException("E_WEB_MEDIA_GATE");
                    } catch (IllegalArgumentException malformed) { /* Report a generic redirect. */ }
                }
            }
            throw new IOException(api ? "E_WEB_API_REDIRECT" : "E_WEB_MEDIA_REDIRECT");
        }
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

    private Exchange openMedia(URI address, long generation, CancellationSignal cancel, String range, WebValidator validator)
            throws Exception {
        URI target = address;
        for (int hop = 0; hop <= 2; hop++) {
            awaitSlot(generation, cancel, false);
            Exchange exchange = open(target, generation, cancel);
            boolean keep = false;
            try {
                HttpURLConnection connection = exchange.connection;
                // Report the API origin that supplied the link, without its credential-bearing query.
                if (site == WebSite.GELBOORU) {
                    connection.setRequestProperty("Referer", site.api.resolve("/").toASCIIString());
                }
                if (range != null) connection.setRequestProperty("Range", range);
                if (validator != null) validator.apply(connection);
                int status = connection.getResponseCode();
                if (site != WebSite.GELBOORU || status < 300 || status >= 400) {
                    keep = true;
                    return exchange;
                }
                String location = connection.getHeaderField("Location");
                if (location == null || hop == 2) throw new IOException("E_WEB_MEDIA_REDIRECT");
                try {
                    URI redirected = target.resolve(location);
                    if (WebPolicy.gelbooruGate(redirected)) throw new IOException("E_WEB_MEDIA_GATE");
                    target = WebPolicy.media(site, redirected.toASCIIString());
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("E_WEB_MEDIA_REDIRECT");
                }
            } finally {
                if (!keep) exchange.close();
            }
        }
        throw new IOException("E_WEB_MEDIA_REDIRECT");
    }

    private Exchange open(URI uri, long generation, CancellationSignal cancel) throws Exception {
        cancel.throwIfCanceled();
        HttpURLConnection connection = VariantTransport.open(context, uri);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "MediaGallery-Android/2.25 (optional web connector)");
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

    private void checkCachePolicy(HttpURLConnection connection) throws IOException {
        if (site == WebSite.GELBOORU) return; // The bridge admits Gelbooru only for a memory-only viewer session.
        String policy = connection.getHeaderField("Cache-Control");
        if (policy != null && policy.toLowerCase(Locale.ROOT).contains("no-store")) throw new IOException("E_WEB_NO_STORE");
    }

    private void validateType(HttpURLConnection connection, String expected) throws IOException {
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
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
