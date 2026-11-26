package local.ttlab.web;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DelegatedHttpsConnectionTest {
    @Test public void sendsOnlyOriginFormAndExpectedHeaders() throws Exception {
        DelegatedHttpsConnection connection = connection("https://example.test:443//media/a%20b.mp4?v=2");
        connection.setRequestProperty("Range", "bytes=5-7");
        connection.setRequestProperty("If-Match", "\"fixture-v1\"");
        String wire = new String(connection.requestBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.startsWith("GET //media/a%20b.mp4?v=2 HTTP/1.1\r\n"));
        assertTrue(wire.contains("Host: example.test\r\n"));
        assertTrue(wire.contains("Accept-Encoding: identity\r\n"));
        assertTrue(wire.contains("Connection: close\r\n"));
        assertTrue(wire.contains("Range: bytes=5-7\r\n"));
        assertTrue(wire.contains("If-Match: \"fixture-v1\"\r\n"));
        assertTrue(wire.endsWith("\r\n\r\n"));
        assertFalse(connection.toString().contains("v=2"));
    }

    @Test public void rejectsUnapprovedRequestFormsBeforeOpeningSocket() throws Exception {
        assertThrows(IOException.class, () -> connection("http://example.test/path"));
        assertThrows(IOException.class, () -> connection("https://example.test:8080/path"));
        assertThrows(IOException.class, () -> connection("https://name@example.test/path"));
        DelegatedHttpsConnection unsafe = connection("https://example.test/path");
        unsafe.setRequestProperty("Accept", "anything\r\nX-Injection: test");
        assertThrows(IOException.class, unsafe::requestBytes);
        DelegatedHttpsConnection cookie = connection("https://example.test/path");
        cookie.setRequestProperty("Cookie", "synthetic=1");
        assertThrows(IOException.class, cookie::requestBytes);
        assertThrows(IOException.class,
                () -> connection("https://example.test/" + "x".repeat(8192)).requestBytes());
        DelegatedHttpsConnection closed = connection("https://example.test/path");
        closed.disconnect();
        assertThrows(IOException.class, closed::connect);
    }

    @Test public void readsFixedRangeAndReportsTheCaseInsensitiveHeaders() throws Exception {
        DelegatedHttpsConnection.Response response = response("GET", 8,
                "HTTP/1.1 206 Partial Content\r\ncOnTeNt-LeNgTh: 3\r\nContent-Range: bytes 5-7/10\r\n\r\nxyzextra");
        assertEquals(206, response.status);
        assertEquals(3, response.length);
        assertEquals("bytes 5-7/10", response.header("CONTENT-RANGE"));
        assertEquals(List.of("3"), response.headerFields().get("content-length"));
        assertEquals(List.of("bytes 5-7/10"), response.headerFields().get("CONTENT-RANGE"));
        assertArrayEquals(bytes("xyz"), response.body.readAllBytes());
    }

    @Test(timeout = 3000) public void disconnectDoesNotWaitForTheBroker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DelegatedHttpsConnection connection = new DelegatedHttpsConnection(URI.create("https://example.test/path"), host -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        });
        FutureTask<Integer> connecting = new FutureTask<>(connection::getResponseCode);
        Thread worker = new Thread(connecting);
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            connection.disconnect();
        } finally {
            release.countDown();
        }
        assertEquals("E_WEB_CANCELED",
                assertThrows(ExecutionException.class, () -> connecting.get(1, TimeUnit.SECONDS)).getCause().getMessage());
    }

    @Test public void acceptsChunkingAndInterimHeadersWithStrictFraming() throws Exception {
        DelegatedHttpsConnection.Response response = response("GET", 8,
                "HTTP/1.1 103 Early Hints\r\nLink: </example>\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "2;ignored=yes\r\nab\r\n1\r\nc\r\n0\r\nX-Trailer: ignored\r\n\r\n");
        assertEquals(-1, response.length);
        assertArrayEquals(bytes("abc"), response.body.readAllBytes());
    }

    @Test public void rejectsAmbiguousOrOversizedMessagesAndPrematureEof() throws Exception {
        String prefix = "HTTP/1.1 200 OK\r\n";
        assertThrows(IOException.class, () -> response("GET", 8,
                prefix + "Transfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n"));
        assertThrows(IOException.class, () -> response("GET", 8,
                prefix + "Content-Length: 5\r\nContent-Length: 6\r\n\r\n"));
        assertThrows(IOException.class, () -> response("GET", 8,
                prefix + "X-Large: " + "x".repeat(32768) + "\r\n\r\n"));
        assertThrows(IOException.class, () -> response("GET", 8,
                prefix + "Content-Length: 4\r\n\r\nabc").body.readAllBytes());
        assertThrows(IOException.class, () -> response("GET", 3,
                prefix + "Transfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n").body.readAllBytes());
        assertThrows(IOException.class, () -> response("GET", 3,
                prefix + "\r\nabcd").body.readAllBytes());
        assertThrows(IOException.class, () -> response("GET", 8,
                prefix + "Transfer-Encoding: chunked\r\n\r\n1\r\nax\r\n0\r\n\r\n").body.readAllBytes());
    }

    @Test public void headAndRedirectDoNotReadOrFollowAnotherResource() throws Exception {
        DelegatedHttpsConnection.Response head = response("HEAD", 8,
                "HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\n");
        assertEquals(200, head.length);
        assertEquals(-1, head.body.read());
        DelegatedHttpsConnection.Response redirect = response("GET", 8,
                "HTTP/1.1 302 Found\r\nLocation: https://example.test/other\r\nContent-Length: 0\r\n\r\n");
        assertEquals(302, redirect.status);
        assertEquals("https://example.test/other", redirect.header("Location"));
        assertEquals(-1, redirect.body.read());
    }

    private static DelegatedHttpsConnection connection(String uri) throws IOException {
        return new DelegatedHttpsConnection(URI.create(uri), host -> { throw new AssertionError("Unexpected socket"); });
    }

    private static DelegatedHttpsConnection.Response response(String method, int limit, String fixture) throws IOException {
        return DelegatedHttpsConnection.readResponse(new ByteArrayInputStream(bytes(fixture)), method, limit);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }
}
