package local.ttlab.web;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WebProvider extends ContentProvider {
    private static final String[] POSTS = {
        "id", "channel", "name", "mime", "size", "date", "width", "height",
        "duration", "revision", "content_key", "tags"
    };
    private final ThreadPoolExecutor pipes = new ThreadPoolExecutor(3, 3, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final ConcurrentHashMap<String, CancellationSignal> transfers = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Media> media = new LinkedHashMap<>(16, .75f, true);

    private static final class Media {
        final long generation;
        final WebNetwork.Post post;
        final WebNetwork.Representation representation;
        Media(long generation, WebNetwork.Post post, WebNetwork.Representation representation) {
            this.generation = generation;
            this.post = post;
            this.representation = representation;
        }
    }

    @Override public boolean onCreate() {
        pipes.allowCoreThreadTimeOut(true);
        return true;
    }

    private WebSettings settings() throws Exception {
        WebSettings.requireProvider(getContext(), getCallingPackage());
        return WebSettings.get(getContext());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return query(uri, projection, selection, args, sort, null);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                                  String sort, CancellationSignal cancellation) {
        List<String> path = uri.getPathSegments();
        CancellationSignal signal = cancellation == null ? new CancellationSignal() : cancellation;
        try {
            WebSettings settings = settings();
            if (selection != null || args != null || sort != null || projection != null) throw new IllegalArgumentException();
            if (path.equals(List.of("status"))) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"enabled"});
                WebSettings.Config config = settings.config();
                cursor.addRow(new Object[]{config != null && config.enabled ? 1 : 0});
                return cursor;
            }
            boolean identity = path.equals(List.of("identity"));
            WebSettings.Config config = settings.active(identity ? null : required(uri, "account", "[0-9a-f]{64}"));
            long generation = settings.generation(config);
            if (identity) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"account"});
                cursor.addRow(new Object[]{config.account});
                return cursor;
            }
            if (path.equals(List.of("search"))) {
                if (uri.getEncodedQuery() == null || uri.getEncodedQuery().length() > 4400) throw new IllegalArgumentException();
                int page = Integer.parseInt(required(uri, "page", "[0-9]{1,2}"));
                WebNetwork.Page result = settings.network.search(config, generation, uri.getQueryParameter("tags"), page, signal);
                MatrixCursor cursor = new MatrixCursor(POSTS, result.posts.size());
                for (WebNetwork.Post post : result.posts) {
                    cursor.addRow(new Object[]{post.id, 1, "Publicación #" + post.id, post.mime, 0L,
                        post.date, post.width, post.height, 0, post.revision, "", post.tags});
                }
                Bundle extras = new Bundle();
                extras.putInt("next", result.more ? page + 1 : -1);
                cursor.setExtras(extras);
                return cursor;
            }
            if (path.size() == 2 && "describe".equals(path.get(0))) {
                WebNetwork.Post post = settings.network.lookup(config, generation,
                        positiveId(path.get(1)), required(uri, "revision", "[0-9a-f]{64}"), signal);
                WebNetwork.Representation rep = settings.network.describe(post, generation, signal);
                settings.network.current(generation);
                synchronized (media) {
                    media.put(post.id + "/" + rep.version, new Media(generation, post, rep));
                    if (media.size() > 8) media.remove(media.keySet().iterator().next());
                }
                MatrixCursor cursor = new MatrixCursor(new String[]{"size", "version"});
                cursor.addRow(new Object[]{rep.length, rep.version});
                return cursor;
            }
            throw new IllegalArgumentException("E_WEB_REQUEST");
        } catch (Exception failed) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"error"});
            Bundle extras = new Bundle();
            extras.putString("error", error(failed));
            cursor.setExtras(extras);
            return cursor;
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        WebSettings.requireProvider(getContext(), getCallingPackage());
        if (!"cancel".equals(method) || arg == null || !arg.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("E_WEB_REQUEST");
        }
        CancellationSignal signal = transfers.get(arg);
        if (signal != null) signal.cancel();
        return Bundle.EMPTY;
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal callerCancel)
            throws FileNotFoundException {
        try {
            if (!"r".equals(mode)) throw new IllegalArgumentException("E_WEB_REQUEST");
            WebSettings settings = settings();
            WebSettings.Config config = settings.active(required(uri, "account", "[0-9a-f]{64}"));
            long generation = settings.generation(config);
            List<String> parts = uri.getPathSegments();
            if (parts.size() != 2) throw new IllegalArgumentException("E_WEB_REQUEST");
            boolean thumb = "thumb".equals(parts.get(0));
            if (!thumb && !"range".equals(parts.get(0))) throw new IllegalArgumentException("E_WEB_REQUEST");
            int id = positiveId(parts.get(1));
            String revision = required(uri, "revision", "[0-9a-f]{64}");
            String token = required(uri, "request", "[0-9a-f-]{36}");
            long offset = thumb ? 0 : Long.parseLong(required(uri, "offset", "[0-9]{1,10}"));
            int length = thumb ? 0 : Integer.parseInt(required(uri, "length", "[0-9]{1,6}"));
            if (!thumb && (length < 1 || length > WebPolicy.RANGE)) throw new IllegalArgumentException("E_WEB_RANGE");
            String version = thumb ? "" : required(uri, "version", "[0-9a-f]{64}");
            CancellationSignal signal = new CancellationSignal();
            if (callerCancel != null) callerCancel.throwIfCanceled();
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createReliablePipe();
            if (transfers.putIfAbsent(token, signal) != null) {
                pair[0].close();
                pair[1].close();
                throw new IOException("E_WEB_REQUEST");
            }
            try {
                pipes.execute(() -> write(pair[1], settings, config, generation, signal, token,
                        id, revision, thumb, version, offset, length));
            } catch (RuntimeException rejected) {
                transfers.remove(token, signal);
                pair[0].close();
                pair[1].close();
                throw new IOException("E_WEB_BUSY");
            }
            return new AssetFileDescriptor(pair[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (Exception failed) { throw new FileNotFoundException(error(failed)); }
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }

    private void write(ParcelFileDescriptor output, WebSettings settings, WebSettings.Config config,
                       long generation, CancellationSignal signal, String token, int id, String revision,
                       boolean thumb, String version, long offset, int length) {
        try {
            byte[] bytes;
            if (thumb) {
                WebNetwork.Post post = settings.network.lookup(config, generation, id, revision, signal);
                bytes = settings.network.thumbnail(post, generation, signal);
            } else {
                Media found;
                synchronized (media) { found = media.get(id + "/" + version); }
                if (found == null || found.generation != generation || !revision.equals(found.post.revision)) {
                    throw new IOException("E_WEB_REOPEN");
                }
                bytes = settings.network.range(found.post, found.representation, generation, offset, length, signal);
            }
            settings.network.current(generation);
            if (!getContext().getSystemService(android.os.UserManager.class).isUserForeground()) throw new SecurityException("E_WEB_CALLER");
            signal.throwIfCanceled();
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                stream.write(bytes);
            }
        } catch (Exception failed) {
            try { output.closeWithError(error(failed)); } catch (IOException ignored) { }
        } finally { transfers.remove(token, signal); }
    }

    private static int positiveId(String raw) {
        int id = Integer.parseInt(raw);
        if (id <= 0 || !raw.equals(Integer.toString(id))) throw new IllegalArgumentException("E_WEB_REQUEST");
        return id;
    }

    private static String required(Uri uri, String field, String pattern) {
        String value = uri.getQueryParameter(field);
        if (value == null || value.length() > 128 || !value.matches(pattern)) throw new IllegalArgumentException("E_WEB_REQUEST");
        return value;
    }

    private static String error(Exception failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("E_WEB_[A-Z_]{1,30}")) return message;
        if (failure instanceof android.os.OperationCanceledException) return "E_WEB_CANCELED";
        if (failure instanceof SecurityException) return "E_WEB_CALLER";
        if (failure instanceof IOException) return "E_WEB_NETWORK";
        return "E_WEB_RESPONSE";
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
