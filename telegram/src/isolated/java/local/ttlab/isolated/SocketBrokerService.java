package local.ttlab.isolated;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.DnsResolver;
import android.net.InetAddresses;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserManager;
import local.ttlab.telegram.BuildConfig;
import local.ttlab.web.WebDestinations;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SocketBrokerService extends Service {
    // Telegram publishes the address ranges used by its production data centers at core.telegram.org/resources/cidr.txt.
    private static final List<Prefix> TELEGRAM_RANGES = Stream.of(
            "91.108.56.0/22", "91.108.4.0/22", "91.108.8.0/22", "91.108.16.0/22",
            "91.108.12.0/22", "149.154.160.0/20", "91.105.192.0/23", "91.108.20.0/22",
            "185.76.151.0/24", "2001:b28:f23d::/48", "2001:b28:f23f::/48",
            "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2a0a:f280::/32")
            .map(Prefix::new).collect(Collectors.toList());
    private static final List<Prefix> NON_PUBLIC = Stream.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/3", "64:ff9b::/96", "::/96",
            "fc00::/7", "fe80::/10", "ff00::/8")
            .map(Prefix::new).collect(Collectors.toList());

    @Override public IBinder onBind(Intent intent) {
        return new IConnectorSockets.Stub() {
            @Override public ParcelFileDescriptor openTelegramSocket(String endpoint) {
                verifyGallery();
                try {
                    if (endpoint == null || endpoint.length() > 60 || !endpoint.endsWith(":443"))
                        throw new IllegalArgumentException("E_SOCKET_ENDPOINT");
                    String address = endpoint.substring(0, endpoint.length() - 4);
                    if (address.startsWith("[") && address.endsWith("]"))
                        address = address.substring(1, address.length() - 1);
                    if (address.indexOf('%') >= 0) throw new IllegalArgumentException("E_SOCKET_ENDPOINT");
                    InetAddress destination = InetAddresses.parseNumericAddress(address);
                    if (TELEGRAM_RANGES.stream().noneMatch(prefix -> prefix.contains(destination)))
                        throw new IllegalArgumentException("E_SOCKET_ENDPOINT");
                    return dial(List.of(destination), 7000);
                } catch (IOException failure) {
                    throw new IllegalStateException("E_SOCKET_UNAVAILABLE");
                }
            }

            @Override public ParcelFileDescriptor openWebSocket(String host) {
                verifyGallery();
                if (!WebDestinations.permitsTransportHost(host)) throw new IllegalArgumentException("E_SOCKET_ENDPOINT");
                try {
                    long deadline = SystemClock.elapsedRealtime() + 7000;
                    long identity = Binder.clearCallingIdentity();
                    try {
                        List<InetAddress> addresses = resolve(host);
                        return dial(addresses, Math.max(1, (int) (deadline - SystemClock.elapsedRealtime())));
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("E_SOCKET_UNAVAILABLE");
                }
            }
        };
    }

    private void verifyGallery() {
        enforceCallingPermission("local.ttlab.permission.SOURCE_SOCKETS", "E_SOCKET_CALLER");
        PackageManager packages = getPackageManager();
        try {
            if (packages.getPackageUid(BuildConfig.GALLERY_PACKAGE, 0) != Binder.getCallingUid() ||
                    packages.checkSignatures(BuildConfig.GALLERY_PACKAGE, getPackageName()) != PackageManager.SIGNATURE_MATCH ||
                    !getSystemService(UserManager.class).isUserForeground()) throw new SecurityException("E_SOCKET_CALLER");
        } catch (PackageManager.NameNotFoundException unavailable) { throw new SecurityException("E_SOCKET_CALLER"); }
    }

    private static List<InetAddress> resolve(String host) throws Exception {
        CompletableFuture<List<InetAddress>> result = new CompletableFuture<>();
        CancellationSignal cancellation = new CancellationSignal();
        try {
            DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, Runnable::run, cancellation,
                    new DnsResolver.Callback<List<InetAddress>>() {
                        @Override public void onAnswer(List<InetAddress> answer, int code) {
                            if (code == 0) result.complete(answer);
                            else result.completeExceptionally(new IOException("E_SOCKET_DNS"));
                        }
                        @Override public void onError(DnsResolver.DnsException failure) {
                            result.completeExceptionally(new IOException("E_SOCKET_DNS"));
                        }
                    });
            return result.get(4500, TimeUnit.MILLISECONDS);
        } finally { cancellation.cancel(); }
    }

    private static final class Prefix {
        private final byte[] address;
        private final int bits;

        Prefix(String value) {
            int slash = value.indexOf('/');
            address = InetAddresses.parseNumericAddress(value.substring(0, slash)).getAddress();
            bits = Integer.parseInt(value.substring(slash + 1));
        }

        boolean contains(InetAddress target) {
            byte[] candidate = target.getAddress();
            if (candidate.length != address.length) return false;
            int bytes = bits / 8;
            for (int i = 0; i < bytes; i++) {
                if (candidate[i] != address[i]) return false;
            }
            int rest = bits % 8;
            return rest == 0 || (((candidate[bytes] ^ address[bytes]) & 255) >> (8 - rest)) == 0;
        }
    }

    private static ParcelFileDescriptor dial(List<InetAddress> targets, int budgetMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        for (InetAddress address : targets) {
            int remaining = (int) (deadline - SystemClock.elapsedRealtime());
            if (remaining <= 0) break;
            if (NON_PUBLIC.stream().anyMatch(prefix -> prefix.contains(address))) continue;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, 443), remaining);
                socket.setTcpNoDelay(true);
                return ParcelFileDescriptor.fromSocket(socket);
            } catch (IOException failed) {
                // Continue with a different address within the same connection budget.
            }
        }
        throw new IOException("E_SOCKET_UNAVAILABLE");
    }
}
