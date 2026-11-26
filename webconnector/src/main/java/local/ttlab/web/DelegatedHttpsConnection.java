package local.ttlab.web;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** One HTTPS exchange over a TCP descriptor connected by the application process. */
public final class DelegatedHttpsConnection extends HttpURLConnection {
    public interface SocketBroker {
        /** Returns an owned descriptor to host:443. The broker bounds DNS and TCP connection time. */
        ParcelFileDescriptor openWebSocket(String host) throws IOException;
    }

    private static final int PORT = 443;
    private static final int REQUEST_LIMIT = 8192;
    private static final int HEADERS_LIMIT = 32 * 1024;
    private static final int BODY_LIMIT = 24 * 1024 * 1024;
    private static final int RESPONSE_TIMEOUT = 60_000;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?");
    private static final Set<String> REQUEST_HEADERS = Set.of("accept", "accept-encoding", "user-agent",
            "referer", "range", "if-match", "if-unmodified-since");
    private static final Set<String> SINGLE_HEADERS = Set.of("content-length", "transfer-encoding", "content-type",
            "content-encoding", "content-range", "etag", "last-modified", "date", "location", "retry-after");
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "web-tls-deadline");
        thread.setDaemon(true);
        return thread;
    });

    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private final URI uri;
    private final SocketBroker broker;
    private final Object lifetime = new Object();
    private ParcelFileDescriptor descriptor;
    private SSLSocket secureSocket;
    private ScheduledFuture<?> deadline;
    private long deadlineVersion;
    private volatile String closed;
    private Response response;
    private InputStream body;

    public DelegatedHttpsConnection(URI uri, SocketBroker broker) throws IOException {
        super(httpsUrl(uri));
        this.uri = URI.create(uri.toASCIIString());
        this.broker = Objects.requireNonNull(broker);
        setInstanceFollowRedirects(false);
        setConnectTimeout(7000);
        setReadTimeout(9000);
    }

    private static URL httpsUrl(URI uri) throws IOException {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.indexOf(':') >= 0 || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != PORT) || uri.toString().length() > REQUEST_LIMIT) {
            throw new IOException("E_WEB_REQUEST");
        }
        return uri.toURL();
    }

    byte[] requestBytes() throws IOException {
        if (getDoOutput() || !("GET".equals(method) || "HEAD".equals(method))) {
            throw new ProtocolException("E_WEB_REQUEST");
        }
        String path = uri.getRawPath();
        StringBuilder request = new StringBuilder(method).append(' ').append(path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) request.append('?').append(uri.getRawQuery());
        request.append(" HTTP/1.1\r\nHost: ").append(uri.getHost()).append("\r\nConnection: close\r\n");
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : getRequestProperties().entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADERS.contains(lower) || !seen.add(lower) || entry.getValue().size() != 1) {
                throw new ProtocolException("E_WEB_REQUEST");
            }
            String value = entry.getValue().get(0);
            if (value == null || value.length() > REQUEST_LIMIT || !fieldValue(value)) {
                throw new ProtocolException("E_WEB_REQUEST");
            }
            if ("accept-encoding".equals(lower)) {
                if (!"identity".equalsIgnoreCase(value)) throw new ProtocolException("E_WEB_REQUEST");
                continue;
            }
            request.append(name).append(": ").append(value).append("\r\n");
        }
        request.append("Accept-Encoding: identity\r\n\r\n");
        if (request.length() > REQUEST_LIMIT) throw new ProtocolException("E_WEB_REQUEST");
        return request.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override public synchronized void connect() throws IOException {
        checkOpen();
        if (response != null) return;
        byte[] request = requestBytes();
        connected = true;
        boolean ready = false;
        try {
            armDeadline(getConnectTimeout() > 0 ? getConnectTimeout() : 7000);
            ParcelFileDescriptor socket = broker.openWebSocket(uri.getHost());
            if (socket == null) throw new IOException("E_WEB_SOCKET");
            synchronized (lifetime) {
                if (closed != null) {
                    closeDescriptor(socket);
                    checkOpen();
                }
                descriptor = socket;
            }
            int readTimeout = getReadTimeout() > 0 ? getReadTimeout() : 9000;
            Socket carrier = new ConnectedSocket(socket.getFileDescriptor());
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(carrier, uri.getHost(), PORT, true);
            synchronized (lifetime) {
                if (closed != null) {
                    closeTls(tls);
                    checkOpen();
                }
                secureSocket = tls;
            }
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            armDeadline(Math.min(readTimeout, RESPONSE_TIMEOUT));
            long responseEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESPONSE_TIMEOUT);
            OutputStream output = tls.getOutputStream();
            output.write(request);
            output.flush();
            // The timer also works when Android TLS reads through a stream-based socket carrier.
            InputStream wire = new FilterInputStream(tls.getInputStream()) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(responseEnd - System.nanoTime());
                    if (remaining <= 0) {
                        DelegatedHttpsConnection.this.close("E_WEB_TIMEOUT", 0);
                        checkOpen();
                    }
                    armDeadline((int) Math.min(remaining, readTimeout));
                    return in.read(bytes, offset, length);
                }
            };
            Response parsed = readResponse(new BufferedInputStream(wire), method, BODY_LIMIT);
            checkOpen();
            response = parsed;
            body = new FilterInputStream(parsed.body) {
                @Override public int read() throws IOException {
                    checkOpen();
                    try { return in.read(); } finally { checkOpen(); }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    checkOpen();
                    try { return in.read(bytes, offset, length); } finally { checkOpen(); }
                }
                @Override public void close() { disconnect(); }
            };
            ready = true;
        } catch (IOException | RuntimeException failure) {
            checkOpen();
            if (failure instanceof SocketTimeoutException) throw new SocketTimeoutException("E_WEB_TIMEOUT");
            throw new IOException("E_WEB_TRANSPORT");
        } finally {
            if (!ready) disconnect();
        }
    }

    @Override public int getResponseCode() throws IOException { return result().status; }
    @Override public long getContentLengthLong() {
        try { return result().length; } catch (IOException unavailable) { return -1; }
    }
    @Override public String getHeaderField(String name) {
        try { return result().header(name); } catch (IOException unavailable) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try { return result().headerFields(); } catch (IOException unavailable) { return Collections.emptyMap(); }
    }
    @Override public InputStream getInputStream() throws IOException {
        if (result().status >= 400) throw new IOException("E_WEB_HTTP");
        checkOpen();
        return body;
    }
    @Override public boolean usingProxy() { return false; }
    @Override public void disconnect() { close("E_WEB_CANCELED", 0); }
    @Override public String toString() { return "DelegatedHttpsConnection"; }

    private synchronized Response result() throws IOException {
        if (response == null) connect();
        return response;
    }

    private void checkOpen() throws IOException {
        String reason = closed;
        if ("E_WEB_TIMEOUT".equals(reason)) throw new SocketTimeoutException(reason);
        if (reason != null) throw new IOException(reason);
    }

    private void armDeadline(int millis) throws IOException {
        synchronized (lifetime) {
            checkOpen();
            if (deadline != null) deadline.cancel(false);
            long version = ++deadlineVersion;
            deadline = DEADLINES.schedule(() -> close("E_WEB_TIMEOUT", version), millis, TimeUnit.MILLISECONDS);
        }
    }

    private void close(String reason, long version) {
        SSLSocket tls;
        synchronized (lifetime) {
            if (closed != null || (version != 0 && version != deadlineVersion)) return;
            closed = reason;
            if (deadline != null) deadline.cancel(false);
            if (descriptor != null) closeDescriptor(descriptor);
            descriptor = null;
            tls = secureSocket;
            secureSocket = null;
        }
        if (tls != null) closeTls(tls);
    }

    private static void closeDescriptor(ParcelFileDescriptor socket) {
        // Shutdown wakes a concurrent TLS read even if another process still holds a duplicate.
        try { Os.shutdown(socket.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (ErrnoException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeTls(SSLSocket tls) {
        try { tls.close(); } catch (IOException ignored) { }
    }

    static final class Response {
        final int status;
        final long length;
        final InputStream body;
        private final Map<String, String> headers;

        Response(int status, long length, Map<String, String> headers, InputStream body) {
            this.status = status;
            this.length = length;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) { return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT)); }

        Map<String, List<String>> headerFields() {
            Map<String, List<String>> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.forEach((name, value) -> fields.put(name, List.of(value)));
            return Collections.unmodifiableMap(fields);
        }
    }

    static Response readResponse(InputStream input, String method, int bodyLimit) throws IOException {
        Lines lines = new Lines(input, HEADERS_LIMIT);
        for (int interim = 0; interim <= 4; interim++) {
            String statusLine = lines.read();
            if (!STATUS_LINE.matcher(statusLine).matches() || !fieldValue(statusLine)) throw framing();
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            int count = 0;
            while (!(line = lines.read()).isEmpty()) {
                if (++count > 128) throw framing();
                int separator = headerSeparator(line);
                String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
                if (SINGLE_HEADERS.contains(name) && headers.containsKey(name)) throw framing();
                headers.merge(name, line.substring(separator + 1).trim(), (first, next) -> first + ", " + next);
            }
            String encoding = headers.get("transfer-encoding");
            long length = contentLength(headers.get("content-length"));
            if (encoding != null && (!"chunked".equalsIgnoreCase(encoding) || length != -1)) throw framing();
            if (status == 101) throw framing();
            if (status < 200) continue;
            boolean empty = "HEAD".equals(method) || status == 204 || status == 205 || status == 304;
            InputStream body = new ResponseBody(input, empty ? 0 : length, !empty && encoding != null, bodyLimit);
            return new Response(status, length, headers, body);
        }
        throw framing();
    }

    private static int headerSeparator(String line) throws IOException {
        int separator = line.indexOf(':');
        if (separator < 1 || !HEADER_NAME.matcher(line.substring(0, separator)).matches()
                || !fieldValue(line.substring(separator + 1))) throw framing();
        return separator;
    }

    private static boolean fieldValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 32 && character != '\t') || character == 127 || character > 255) return false;
        }
        return true;
    }

    private static long contentLength(String value) throws IOException {
        if (value == null) return -1;
        if (!value.matches("[0-9]{1,19}")) throw framing();
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw framing(); }
    }

    private static ProtocolException framing() { return new ProtocolException("E_WEB_HTTP_FRAMING"); }

    private static final class Lines {
        private final InputStream input;
        private int budget;

        Lines(InputStream input, int budget) {
            this.input = input;
            this.budget = budget;
        }

        String read() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(80);
            for (int character; --budget >= 0 && (character = input.read()) >= 0; ) {
                if (character == '\r') {
                    if (--budget < 0 || input.read() != '\n') throw framing();
                    return line.toString(StandardCharsets.ISO_8859_1.name());
                }
                if (character == '\n' || line.size() >= HEADERS_LIMIT) throw framing();
                line.write(character);
            }
            throw framing();
        }
    }

    private static final class ResponseBody extends InputStream {
        private final InputStream input;
        private final Lines chunks;
        private final byte[] single = new byte[1];
        private int budget;
        private long remaining;
        private boolean lastChunk;
        private boolean chunkEnding;

        ResponseBody(InputStream input, long length, boolean chunked, int limit) {
            this.input = input;
            this.chunks = chunked ? new Lines(input, 1024 * 1024) : null;
            this.remaining = chunked ? 0 : length;
            this.budget = limit;
        }

        @Override public int read() throws IOException {
            return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (chunks != null && remaining == 0 && !lastChunk) nextChunk();
            if (remaining == 0) return -1;
            if (remaining > budget) throw new IOException("E_WEB_SIZE");
            if (budget == 0) {
                if (input.read() == -1) return -1;
                throw new IOException("E_WEB_SIZE");
            }
            int wanted = Math.min(length, budget);
            if (remaining >= 0) wanted = (int) Math.min(wanted, remaining);
            int count = input.read(bytes, offset, wanted);
            if (count < 0 && remaining > 0) throw framing();
            if (count > 0) {
                budget -= count;
                if (remaining > 0) remaining -= count;
            }
            return count;
        }

        private void nextChunk() throws IOException {
            if (chunkEnding && !chunks.read().isEmpty()) throw framing();
            String line = chunks.read();
            int extension = line.indexOf(';');
            String size = extension < 0 ? line : line.substring(0, extension);
            if (!fieldValue(line) || !size.matches("[0-9a-fA-F]{1,16}")) throw framing();
            try { remaining = Long.parseLong(size, 16); } catch (NumberFormatException invalid) { throw framing(); }
            if (remaining != 0) {
                chunkEnding = true;
                return;
            }
            lastChunk = true;
            String trailer;
            while (!(trailer = chunks.read()).isEmpty()) headerSeparator(trailer);
        }
    }

    private static final class ConnectedSocket extends Socket {
        ConnectedSocket(FileDescriptor fd) throws SocketException { super(new ConnectedImpl(fd)); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isBound() { return true; }
    }

    private static final class ConnectedImpl extends SocketImpl {
        ConnectedImpl(FileDescriptor descriptor) {
            fd = descriptor;
            address = InetAddress.getLoopbackAddress();
            port = PORT;
        }

        @Override protected InputStream getInputStream() { return new FileInputStream(fd); }
        @Override protected OutputStream getOutputStream() { return new FileOutputStream(fd); }
        @Override public void setOption(int option, Object value) { }
        @Override public Object getOption(int option) { return 0; }
        @Override protected void close() { /* The exchange owns the descriptor. */ }
        @Override protected int available() { return 0; }
        @Override protected void create(boolean stream) { /* The broker already connected the descriptor. */ }
        @Override protected void connect(String host, int port) { throw new UnsupportedOperationException(); }
        @Override protected void connect(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override protected void connect(SocketAddress address, int timeout) { throw new UnsupportedOperationException(); }
        @Override protected void bind(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override protected void listen(int backlog) { throw new UnsupportedOperationException(); }
        @Override protected void accept(SocketImpl socket) { throw new UnsupportedOperationException(); }
        @Override protected void sendUrgentData(int data) { throw new UnsupportedOperationException(); }
    }
}
