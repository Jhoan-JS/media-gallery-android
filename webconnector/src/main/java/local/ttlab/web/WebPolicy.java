package local.ttlab.web;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebPolicy {
    static final int PAGE_SIZE = 30;
    static final int MAX_PAGES = 40;
    static final int RANGE = 4 * 131072;
    static final int THUMB = 131072;
    static final long MAX_MEDIA = 4L * 1024 * 1024 * 1024;
    private static final Set<String> MEDIA_HOSTS = Set.of(
            "api-cdn.rule34.xxx", "api-cdn-mp4.rule34.xxx", "api-cdn-us.rule34.xxx",
            "api-cdn-us-mp4.rule34.xxx");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

    static URI media(WebSite site, String address) {
        if (address.length() > 2048) throw new IllegalArgumentException("E_WEB_MEDIA_URL");
        URI uri = URI.create(address);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                !mediaHost(site, uri.getHost()) || uri.getPort() != -1 ||
                uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
                uri.getRawQuery() != null || uri.getRawPath().length() < 2) {
            throw new IllegalArgumentException("E_WEB_MEDIA_URL");
        }
        if (site == WebSite.GELBOORU && !uri.getRawPath().matches("/(?:images|thumbnails|samples|videos)/[^?#]+")) {
            throw new IllegalArgumentException("E_WEB_MEDIA_URL");
        }
        return uri;
    }

    private static boolean mediaHost(WebSite site, String host) {
        if (site == WebSite.RULE34) return MEDIA_HOSTS.contains(host);
        return host.matches("(?:s?img[1-9][0-9]?|s?img|video|cdn)\\.gelbooru\\.com");
    }

    static String query(WebSite site, String input) {
        if (input == null) throw new IllegalArgumentException("E_WEB_TAGS");
        String tags = input.trim();
        if (tags.length() > 320 || tags.isEmpty()) throw new IllegalArgumentException("E_WEB_TAGS");
        String[] words = tags.split("\\s+");
        if (words.length > 8) throw new IllegalArgumentException("E_WEB_TAGS");
        boolean positive = false;
        for (String token : words) {
            if (!token.startsWith("-")) positive = true;
            if (!token.matches("-?[\\p{L}\\p{N}_().+'!?-]{1,80}") || token.equals("-")) {
                throw new IllegalArgumentException("E_WEB_TAGS");
            }
        }
        if (!positive) throw new IllegalArgumentException("E_WEB_TAGS");
        return String.join(" ", words) + " rating:" + site.rating;
    }

    static String mime(URI uri) {
        String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".webm")) return "video/webm";
        return "";
    }

    static long contentRange(int status, String value, long offset, int length) throws IOException {
        if (status == 200) throw new IOException("E_WEB_NO_RANGES");
        if (status != 206 || value == null || value.length() > 100 || offset < 0 ||
                offset >= MAX_MEDIA || length < 1 || length > RANGE) throw new IOException("E_WEB_RANGE");
        Matcher fields = CONTENT_RANGE.matcher(value);
        if (!fields.matches()) throw new IOException("E_WEB_RANGE");
        try {
            long start = Long.parseLong(fields.group(1));
            long end = Long.parseLong(fields.group(2));
            long size = Long.parseLong(fields.group(3));
            if (start != offset || end != offset + length - 1 || end >= size || size > MAX_MEDIA) {
                throw new IOException("E_WEB_RANGE");
            }
            return size;
        } catch (NumberFormatException invalid) {
            throw new IOException("E_WEB_RANGE");
        }
    }
}
