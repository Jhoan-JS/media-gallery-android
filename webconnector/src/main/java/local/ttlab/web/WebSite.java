package local.ttlab.web;

import java.net.URI;

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

}
