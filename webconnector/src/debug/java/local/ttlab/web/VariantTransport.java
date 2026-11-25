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
        JSONObject[] livePosts = {
                readLivePost(context, "live-general.json", 15335274),
                readLivePost(context, "live-general-video.json", 15334168)
        };
        for (JSONObject row : livePosts) {
            if (row == null) continue;
            for (String field : new String[]{"file_url", "sample_url", "preview_url"}) {
                if (uri.toASCIIString().equals(row.optString(field))) {
                    Log.i("GalleryWebFixture", "Known General post requested: " + field);
                    return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
                }
            }
        }
        return new FixtureConnection(context, uri, livePosts);
    }

    private static JSONObject readLivePost(Context context, String asset, int expectedId) throws IOException {
        try (InputStream input = context.getAssets().open(asset)) {
            byte[] metadata = input.readNBytes(8193);
            if (metadata.length > 8192) throw new IOException("Oversized live fixture");
            JSONObject row = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
            if (row.getInt("id") != expectedId || !"general".equals(row.getString("rating"))) {
                throw new IOException("Unexpected live fixture");
            }
            return row;
        } catch (java.io.FileNotFoundException absent) {
            return null;
        } catch (org.json.JSONException invalid) {
            throw new IOException("Invalid live fixture");
        }
    }

    private static final class FixtureConnection extends HttpURLConnection {
        private final Context context;
        private final URI uri;
        private final JSONObject[] livePosts;
        private byte[] file;
        private int start, count, status;
        private String type, range, etag, location, modified;
        private static final String FIXTURE_MODIFIED = "Sun, 06 Nov 1994 08:49:37 GMT";

        FixtureConnection(Context context, URI uri, JSONObject[] livePosts) throws IOException {
            super(uri.toURL());
            this.context = context;
            this.uri = uri;
            this.livePosts = livePosts;
        }

        @Override public void connect() throws IOException {
            if (connected) return;
            if (isApi()) {
                if (getRequestProperty("Referer") != null) throw new IOException("Referrer was sent to the API");
                file = catalogue().getBytes(StandardCharsets.UTF_8);
                type = "gelbooru.com".equals(uri.getHost()) && uri.getRawQuery().indexOf("json=1") < 0
                        ? "text/xml" : "application/json";
                status = 200;
                Log.i("GalleryWebFixture", "Synthetic search response");
            } else {
                String name = uri.getPath();
                boolean gelbooru = uri.getHost().endsWith(".gelbooru.com");
                String referrer = getRequestProperty("Referer");
                if (gelbooru ? !"https://gelbooru.com/".equals(referrer) : referrer != null) {
                    throw new IOException("Wrong referrer for media fixture");
                }
                if (name.endsWith("/297.png") || name.endsWith("/297-thumb.png")) {
                    status = 302;
                    location = "https://gelbooru.com/hotlink.php?hash=synthetic";
                    connected = true;
                    return;
                }
                if ("img3.gelbooru.com".equals(uri.getHost()) && name.endsWith("/299.png")) {
                    status = 302;
                    location = "https://img4.gelbooru.com/images/299.png";
                    connected = true;
                    return;
                }
                if ("/hotlink.php".equals(name)) {
                    Log.e("GalleryWebFixture", "Unexpected request to hotlink page");
                    throw new IOException("Protected redirect was followed");
                }
                if (name.endsWith("/299-preview.png")) Log.i("GalleryWebFixture", "Sample preview received");
                if ("img4.gelbooru.com".equals(uri.getHost()) && name.endsWith("/299.png")) {
                    Log.i("GalleryWebFixture", "Allowed CDN redirect reached original image");
                }
                String asset = name.endsWith(".mp4") ? "fixture.mp4" : "fixture.png";
                type = name.endsWith(".mp4") ? "video/mp4" : "image/png";
                file = ASSETS.get(asset);
                if (file == null) {
                    try (InputStream in = context.getAssets().open(asset)) { file = in.readAllBytes(); }
                    ASSETS.put(asset, file);
                }
                boolean dateVideo = gelbooru && (name.endsWith("/298.mp4") || name.endsWith("/294.mp4"));
                boolean snapshotVideo = gelbooru && name.endsWith("/296.mp4");
                if (!dateVideo && !snapshotVideo && (asset.equals("fixture.mp4") || !gelbooru)) {
                    etag = "\"synthetic-" + asset + "-v1\"";
                }
                String request = getRequestProperty("Range");
                status = 200;
                if (dateVideo) {
                    modified = FIXTURE_MODIFIED;
                    if (request != null && !request.equals("bytes=0-0")) {
                        if (!FIXTURE_MODIFIED.equals(getRequestProperty("If-Unmodified-Since"))
                                || getRequestProperty("If-Match") != null) {
                            throw new IOException("Invalid date condition for MP4 fixture");
                        }
                        if (name.endsWith("/294.mp4")) modified = "Sun, 06 Nov 1994 08:50:00 GMT";
                        else Log.i("GalleryWebFixture", "Dated MP4 conditional range");
                    }
                }
                if (snapshotVideo && request == null) Log.i("GalleryWebFixture", "Unversioned MP4 complete response");
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

        private boolean isApi() {
            return ("api.rule34.xxx".equals(uri.getHost()) || "gelbooru.com".equals(uri.getHost()))
                    && "/index.php".equals(uri.getPath());
        }

        private String catalogue() throws IOException {
            Uri target = Uri.parse(uri.toASCIIString());
            String id = target.getQueryParameter("id");
            String tags = target.getQueryParameter("tags");
            String page = target.getQueryParameter("pid");
            try {
                boolean gelbooru = "gelbooru.com".equals(uri.getHost());
                String expectedTag = gelbooru ? "fixture_gelbooru" : "fixture_gallery";
                if (gelbooru) {
                    if ((id == null && (tags == null || tags.contains("rating:") || tags.contains("sort:")))
                            || (id != null && tags != null)
                            || ((id != null) != "1".equals(target.getQueryParameter("json")))
                            || !"gel_fixture_key".equals(target.getQueryParameter("api_key"))) {
                        throw new IOException("Unexpected Gelbooru fixture parameters");
                    }
                } else if (tags == null || !tags.endsWith("rating:safe")) {
                    throw new IOException("Missing Rule34 fixture rating");
                }
                JSONArray rows = new JSONArray();
                boolean viewerFeed = gelbooru && "fixture_viewer".equals(tags) && id == null;
                JSONObject matching = null;
                if (gelbooru) {
                    for (JSONObject known : livePosts) {
                        if (known != null && (known.optString("id").equals(id) || known.optString("tags").equals(tags))) {
                            matching = known;
                            break;
                        }
                    }
                }
                if (id != null) {
                    rows.put(matching == null ? row(Integer.parseInt(id), gelbooru) : matching);
                } else if (matching != null) {
                    if ("0".equals(page)) rows.put(matching);
                } else if (viewerFeed) {
                    int number = Integer.parseInt(page);
                    Log.i("GalleryWebFixture", "Viewer fixture page " + number);
                    if (number == 0) {
                        for (int n = 449; n >= 423; n -= 2) rows.put(row(n, true));
                    } else if (number == 1) {
                        rows.put(row(422, true)).put(row(421, true));
                    }
                } else if (gelbooru || tags.contains(expectedTag)) {
                    int queryOffset = gelbooru && tags.startsWith("fixture_home") ? 100
                            : gelbooru && tags.startsWith("fixture_search") ? 200 : 0;
                    int high = "0".equals(page) ? 100 : 70;
                    int low = "0".equals(page) ? 71 : 63;
                    for (int n = high; n >= low; n--) rows.put(row(n + queryOffset, gelbooru));
                }
                if (!gelbooru) return rows.toString();
                if (id != null) return new JSONObject().put("post", rows).toString();
                StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><posts count=\"")
                        .append(rows.length() == 0 ? 0 : viewerFeed ? 16 : matching != null ? 1 : 38)
                        .append("\" offset=\"")
                        .append("1".equals(page) ? viewerFeed ? 14 : 30 : 0).append("\">");
                for (int index = 0; index < rows.length(); index++) {
                    JSONObject post = rows.getJSONObject(index);
                    boolean elements = index % 2 != 0;
                    xml.append(elements ? "<post>" : "<post");
                    java.util.Iterator<String> fields = post.keys();
                    while (fields.hasNext()) {
                        String field = fields.next();
                        String value = android.text.TextUtils.htmlEncode(post.getString(field));
                        if (elements) xml.append("<").append(field).append(">").append(value).append("</").append(field).append(">");
                        else xml.append(" ").append(field).append("=\"").append(value).append("\"");
                    }
                    xml.append(elements ? "</post>" : "/>");
                }
                return xml.append("</posts>").toString();
            } catch (Exception invalid) { throw new IOException("Fixture failed"); }
        }

        private static JSONObject row(int id, boolean gelbooru) throws Exception {
            String origin = gelbooru ? "https://img3.gelbooru.com//images/" : "https://api-cdn.rule34.xxx/test/";
            String[] labels = {"general", "sensitive", "questionable", "explicit"};
            String rating = gelbooru ? labels[(100 - id) & 3] : "safe";
            String tags = gelbooru ? "fixture_gelbooru blue_sky" : "fixture_gallery blue_sky";
            return new JSONObject().put("id", id).put("rating", rating).put("change", 1780000000 + id)
                    .put("tags", tags).put("width", 640).put("height", 360)
                    .put("file_url", origin + id + (id % 2 == 0 ? ".mp4" : ".png"))
                    .put("preview_url", gelbooru && id == 299 ? "" : origin + id + "-thumb.png")
                    .put("sample_url", gelbooru && id == 299 ? "https://img3.gelbooru.com//samples/299-preview.png" : "");
        }

        @Override public int getResponseCode() throws IOException { connect(); return status; }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            return new ByteArrayInputStream(file, start, count);
        }
        @Override public long getContentLengthLong() { return count; }
        @Override public String getContentType() { return type; }
        @Override public String getHeaderField(String key) {
            if ("Cache-Control".equalsIgnoreCase(key) && uri.getHost().endsWith(".gelbooru.com")) return "private, no-store";
            if ("Cache-Control".equalsIgnoreCase(key) && "gelbooru.com".equals(uri.getHost())) return "no-store";
            if ("Location".equalsIgnoreCase(key)) return location;
            if ("Date".equalsIgnoreCase(key) && modified != null) return "Sun, 06 Nov 1994 08:51:38 GMT";
            if ("Last-Modified".equalsIgnoreCase(key)) return modified;
            if ("ETag".equalsIgnoreCase(key)) return etag;
            if ("Content-Range".equalsIgnoreCase(key)) return range;
            return null;
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { }
    }
}
