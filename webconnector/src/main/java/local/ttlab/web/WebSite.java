package local.ttlab.web;

import java.io.IOException;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

enum WebSite {
    RULE34("rule34", "Rule34.xxx", "rating:safe", "web", "https://api.rule34.xxx/index.php"),
    GELBOORU("gelbooru", "Gelbooru", "", "web-gelbooru", "https://gelbooru.com/index.php");

    final String id, label, requiredRatingTag, directory;
    final URI api;

    WebSite(String id, String label, String requiredRatingTag, String directory, String api) {
        this.id = id;
        this.label = label;
        this.requiredRatingTag = requiredRatingTag;
        this.directory = directory;
        this.api = URI.create(api);
    }

    static WebSite require(String id) {
        if (id == null || RULE34.id.equals(id)) return RULE34;
        if (GELBOORU.id.equals(id)) return GELBOORU;
        throw new IllegalArgumentException("E_WEB_REQUEST");
    }

    boolean acceptsRating(String value) {
        return this == GELBOORU || "safe".equals(value) || "s".equals(value);
    }

    JSONArray posts(String payload) throws IOException, JSONException {
        if (this == RULE34 && payload.startsWith("[")) return new JSONArray(payload);
        if (this == GELBOORU && payload.startsWith("{")) {
            JSONObject response = new JSONObject(payload);
            if (response.has("success") && !response.optBoolean("success")) throw new IOException("E_WEB_ACCESS");
            JSONArray rows = response.optJSONArray("post");
            if (rows != null) return rows;
            JSONObject single = response.optJSONObject("post");
            if (single != null) return new JSONArray().put(single);
            JSONObject attributes = response.optJSONObject("@attributes");
            if (attributes != null) {
                long count = attributes.optLong("count", -1);
                if (count >= 0 && (count == 0 || attributes.optLong("offset", -1) >= count)) return new JSONArray();
            }
        }
        throw new IOException("E_WEB_API");
    }
}
