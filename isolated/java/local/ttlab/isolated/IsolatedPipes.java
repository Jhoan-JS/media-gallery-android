package local.ttlab.isolated;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class IsolatedPipes {
    private static final int BLOCK = 32 * 1024;
    private static final ThreadPoolExecutor WRITERS = new ThreadPoolExecutor(
            4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));

    private IsolatedPipes() {}

    public static ParcelFileDescriptor send(byte[] bytes) throws IOException {
        return send(new ByteArrayInputStream(bytes), () -> Arrays.fill(bytes, (byte) 0));
    }

    public static ParcelFileDescriptor send(InputStream input, Runnable done) throws IOException {
        ParcelFileDescriptor[] ends = null;
        try {
            ends = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor output = ends[1];
            WRITERS.execute(() -> transfer(input, output, done));
            return ends[0];
        } catch (IOException | RejectedExecutionException failure) {
            if (ends != null) {
                close(ends[0]);
                close(ends[1]);
            }
            try { input.close(); } finally { done.run(); }
            throw new IOException("E_ISOLATED_PIPE");
        }
    }

    public static byte[] read(ParcelFileDescriptor source, int maxBytes) throws IOException {
        if (source == null) throw new IOException("E_ISOLATED_PIPE");
        byte[] buffer = new byte[Math.min(BLOCK, maxBytes)];
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > maxBytes - bytes.size()) throw new IOException("E_ISOLATED_LIMIT");
                bytes.write(buffer, 0, count);
            }
            source.checkError();
            return bytes.toByteArray();
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static void transfer(InputStream input, ParcelFileDescriptor output, Runnable done) {
        byte[] block = new byte[BLOCK];
        OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(output);
        try (InputStream source = input) {
            for (int count; (count = source.read(block)) >= 0;) writer.write(block, 0, count);
            writer.close();
        } catch (IOException | RuntimeException failed) {
            try { output.closeWithError("E_ISOLATED_PIPE"); } catch (IOException ignored) {}
        } finally {
            Arrays.fill(block, (byte) 0);
            close(output);
            done.run();
        }
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) {}
    }
}
