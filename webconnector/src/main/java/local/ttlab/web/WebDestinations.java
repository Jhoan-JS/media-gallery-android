package local.ttlab.web;

import java.util.Set;

public final class WebDestinations {
    private static final Set<String> RULE34_MEDIA = Set.of(
            "api-cdn.rule34.xxx", "api-cdn-mp4.rule34.xxx", "api-cdn-us.rule34.xxx",
            "api-cdn-us-mp4.rule34.xxx");

    private WebDestinations() {}

    static boolean mediaHost(WebSite site, String host) {
        if (site == WebSite.RULE34) return RULE34_MEDIA.contains(host);
        return host.matches("(?:s?img[1-9][0-9]?|s?img|video|cdn)\\.gelbooru\\.com");
    }

    public static boolean permitsTransportHost(String host) {
        return host != null && host.length() <= 100 && (
                WebSite.RULE34.api.getHost().equals(host) || WebSite.GELBOORU.api.getHost().equals(host) ||
                mediaHost(WebSite.RULE34, host) || mediaHost(WebSite.GELBOORU, host));
    }
}
