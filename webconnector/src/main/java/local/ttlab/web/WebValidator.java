package local.ttlab.web;

import java.net.HttpURLConnection;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Validator included in every conditional media read and checked on the response. */
final class WebValidator {
    final String responseHeader, requestHeader, value;

    private WebValidator(String responseHeader, String requestHeader, String value) {
        this.responseHeader = responseHeader;
        this.requestHeader = requestHeader;
        this.value = value;
    }

    static WebValidator from(String etag, String modified, String serverDate, boolean allowDate) {
        if (etag != null && etag.matches("\"[^\"\\p{Cntrl}]{1,160}\"")) {
            return new WebValidator("ETag", "If-Match", etag);
        }
        if (allowDate && httpDate(modified) && httpDate(serverDate)) {
            try {
                long before = ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                long sent = ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                // Leave a margin for different clocks at the origin and CDN.
                if (sent - before >= 60) return new WebValidator("Last-Modified", "If-Unmodified-Since", modified);
            } catch (DateTimeParseException invalid) { /* A malformed date cannot identify a representation. */ }
        }
        return null;
    }

    void apply(HttpURLConnection connection) {
        connection.setRequestProperty(requestHeader, value);
    }

    boolean matches(HttpURLConnection connection) {
        return value.equals(connection.getHeaderField(responseHeader));
    }

    private static boolean httpDate(String value) {
        return value != null && value.matches("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4}"
                + " [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
    }
}
