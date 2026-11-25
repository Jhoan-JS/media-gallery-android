package local.ttlab.web;

import android.util.Xml;
import java.io.IOException;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Parses the provider's documented XML envelope or a JSON response. */
final class WebResponse {
    private static final String[] FIELDS = {"id", "rating", "width", "height", "tags", "change",
            "created_at", "file_url", "preview_url", "sample_url"};
    final JSONArray posts;
    private final long count, offset;

    private WebResponse(JSONArray posts, long count, long offset) throws IOException {
        if (posts.length() > WebPolicy.PAGE_SIZE) throw invalid();
        this.posts = posts;
        this.count = count;
        this.offset = offset;
    }

    boolean more() {
        if (posts.length() == 0) return false;
        return count >= 0 && offset >= 0 ? posts.length() < count - offset
                : posts.length() == WebPolicy.PAGE_SIZE;
    }

    static WebResponse parse(WebSite site, String body) throws IOException {
        String payload = body.startsWith("\ufeff") ? body.substring(1).trim() : body;
        try {
            if (payload.startsWith("[")) return new WebResponse(new JSONArray(payload), -1, -1);
            if (site == WebSite.GELBOORU && payload.startsWith("<")) return xml(payload);
            if (site == WebSite.GELBOORU && payload.startsWith("{")) {
                JSONObject root = new JSONObject(payload);
                if (root.has("success") && !root.optBoolean("success")) throw new IOException("E_WEB_CATALOGUE");
                JSONObject attributes = root.optJSONObject("@attributes");
                long count = attributes == null ? -1 : attributes.optLong("count", -1);
                long offset = attributes == null ? -1 : attributes.optLong("offset", -1);
                JSONArray posts = root.optJSONArray("post");
                if (posts == null) {
                    JSONObject single = root.optJSONObject("post");
                    if (single != null) posts = new JSONArray().put(single);
                    else if (count == 0 || (count > 0 && offset >= count)) posts = new JSONArray();
                }
                if (posts != null) return new WebResponse(posts, count, offset);
            }
        } catch (JSONException | XmlPullParserException failure) { throw invalid(); }
        throw invalid();
    }

    private static WebResponse xml(String payload) throws IOException, JSONException, XmlPullParserException {
        XmlPullParser reader = Xml.newPullParser();
        reader.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        reader.setInput(new StringReader(payload));
        boolean root = false;
        long count = -1, offset = -1;
        JSONArray posts = new JSONArray();
        int event;
        while ((event = reader.nextToken()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) throw invalid();
            if (event != XmlPullParser.START_TAG) continue;
            if (reader.getDepth() == 1 && !root) {
                if (!"posts".equals(reader.getName())) throw invalid();
                if ("false".equalsIgnoreCase(reader.getAttributeValue(null, "success"))) {
                    throw new IOException("E_WEB_CATALOGUE");
                }
                root = true;
                count = number(reader.getAttributeValue(null, "count"));
                offset = number(reader.getAttributeValue(null, "offset"));
            } else if (reader.getDepth() == 2 && root && "post".equals(reader.getName())) {
                if (posts.length() >= WebPolicy.PAGE_SIZE) throw invalid();
                posts.put(xmlPost(reader));
            } else throw invalid();
        }
        if (!root) throw invalid();
        return new WebResponse(posts, count, offset);
    }

    private static JSONObject xmlPost(XmlPullParser reader) throws IOException, JSONException, XmlPullParserException {
        JSONObject row = new JSONObject();
        for (String key : FIELDS) {
            String value = reader.getAttributeValue(null, key);
            if (value != null) row.put(key, value);
        }
        while (true) {
            int event = reader.nextToken();
            if (event == XmlPullParser.END_TAG && reader.getDepth() == 2) return row;
            if (event == XmlPullParser.END_DOCUMENT || event == XmlPullParser.DOCDECL) throw invalid();
            if (event != XmlPullParser.START_TAG) continue;
            if (reader.getDepth() != 3) throw invalid();
            String field = reader.getName();
            String value = reader.nextText();
            for (String key : FIELDS) {
                if (key.equals(field) && !row.has(key)) {
                    row.put(key, value);
                    break;
                }
            }
        }
    }

    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,18}")) return -1;
        return Long.parseLong(value);
    }

    private static IOException invalid() { return new IOException("E_WEB_CATALOGUE_FORMAT"); }
}
