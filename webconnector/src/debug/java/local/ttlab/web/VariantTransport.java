package local.ttlab.web;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

final class VariantTransport {
    private static final ConcurrentHashMap<String, byte[]> ASSETS = new ConcurrentHashMap<>();

    static HttpURLConnection open(Context context, URI uri) throws IOException {
        if (!BuildConfig.WEB_FIXTURE) return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        return new FixtureConnection(context, uri);
    }

    private static final class FixtureConnection extends HttpURLConnection {
        private final Context context;
        private final URI uri;
        private byte[] file;
        private int start, count, status;
        private String type, range, etag;

        FixtureConnection(Context context, URI uri) throws IOException {
            super(uri.toURL());
            this.context = context;
            this.uri = uri;
        }

        @Override public void connect() throws IOException {
            if (connected) return;
            if (isApi()) {
                file = json().getBytes(StandardCharsets.UTF_8);
                type = "application/json";
                status = 200;
                Log.i("GalleryWebFixture", "Synthetic search response");
            } else {
                String name = uri.getPath();
                String asset = name.endsWith(".mp4") ? "fixture.mp4" : "fixture.png";
                type = name.endsWith(".mp4") ? "video/mp4" : "image/png";
                file = ASSETS.get(asset);
                if (file == null) {
                    try (InputStream in = context.getAssets().open(asset)) { file = in.readAllBytes(); }
                    ASSETS.put(asset, file);
                }
                etag = "\"synthetic-" + asset + "-v1\"";
                String request = getRequestProperty("Range");
                status = 200;
                if (request != null) {
                    String[] parts = request.substring(6).split("-");
                    start = Integer.parseInt(parts[0]);
                    count = Integer.parseInt(parts[1]) - start + 1;
                    range = "bytes " + start + "-" + (start + count - 1) + "/" + file.length;
                    status = 206;
                    if (type.equals("video/mp4")) Log.i("GalleryWebFixture", "MP4 offset " + start + " length " + count);
                }
            }
            if (status == 200) count = file.length;
            connected = true;
        }

        private boolean isApi() { return "api.rule34.xxx".equals(uri.getHost()); }

        private String json() throws IOException {
            Uri target = Uri.parse(uri.toASCIIString());
            String id = target.getQueryParameter("id");
            String tags = target.getQueryParameter("tags");
            String page = target.getQueryParameter("pid");
            try {
                JSONArray rows = new JSONArray();
                if (id != null) rows.put(row(Integer.parseInt(id)));
                else if (tags != null && tags.contains("fixture_gallery")) {
                    int high = "0".equals(page) ? 100 : 70;
                    int low = "0".equals(page) ? 71 : 63;
                    for (int n = high; n >= low; n--) rows.put(row(n));
                }
                return rows.toString();
            } catch (Exception invalid) { throw new IOException("Fixture failed"); }
        }

        private static JSONObject row(int id) throws Exception {
            return new JSONObject().put("id", id).put("rating", "safe").put("change", 1780000000 + id)
                    .put("tags", "fixture_gallery blue_sky").put("width", 640).put("height", 360)
                    .put("file_url", "https://api-cdn.rule34.xxx/test/" + id + (id % 2 == 0 ? ".mp4" : ".png"))
                    .put("preview_url", "https://api-cdn.rule34.xxx/test/" + id + "-thumb.png");
        }

        @Override public int getResponseCode() throws IOException { connect(); return status; }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            return new ByteArrayInputStream(file, start, count);
        }
        @Override public long getContentLengthLong() { return count; }
        @Override public String getContentType() { return type; }
        @Override public String getHeaderField(String key) {
            if ("ETag".equalsIgnoreCase(key)) return etag;
            if ("Content-Range".equalsIgnoreCase(key)) return range;
            return null;
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { }
    }
}
