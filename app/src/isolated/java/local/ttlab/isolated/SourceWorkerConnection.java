package local.ttlab.isolated;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import local.ttlab.gallery.BuildConfig;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SourceWorkerConnection {
    private static SourceWorkerConnection current;
    private final ServiceLink workerLink;
    private final ServiceLink socketLink;
    public final ISourceWorker worker;
    public final IConnectorSockets sockets;
    private final IConnectorSockets broker;
    private final int workerUid;

    public static synchronized SourceWorkerConnection get(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("E_ISOLATED_MAIN_THREAD");
        if (current != null && current.isAlive()) return current;
        if (current != null) {
            current.socketLink.close();
            current.workerLink.close();
            current = null;
        }
        current = new SourceWorkerConnection(context.getApplicationContext());
        return current;
    }

    private SourceWorkerConnection(Context context) throws IOException {
        workerLink = new ServiceLink(context, "SourceWorkerService");
        socketLink = new ServiceLink(context, "SocketBrokerService");
        try {
            PackageManager packages = context.getPackageManager();
            if (packages.checkSignatures(context.getPackageName(), BuildConfig.ISOLATED_HOST)
                    != PackageManager.SIGNATURE_MATCH) throw new SecurityException("E_ISOLATED_SIGNATURE");
            int isolationFlags = ServiceInfo.FLAG_EXTERNAL_SERVICE | ServiceInfo.FLAG_ISOLATED_PROCESS;
            ServiceInfo workerInfo = packages.getServiceInfo(workerLink.component, 0);
            ServiceInfo socketInfo = packages.getServiceInfo(socketLink.component, 0);
            if (!workerInfo.exported || (workerInfo.flags & isolationFlags) != isolationFlags ||
                    !socketInfo.exported || (socketInfo.flags & isolationFlags) != 0 ||
                    !"local.ttlab.permission.SOURCE_SOCKETS".equals(socketInfo.permission))
                throw new SecurityException("E_ISOLATED_CONFIGURATION");
            workerLink.bind(Context.BIND_AUTO_CREATE | Context.BIND_EXTERNAL_SERVICE);
            socketLink.bind(Context.BIND_AUTO_CREATE);
            worker = ISourceWorker.Stub.asInterface(workerLink.await());
            broker = IConnectorSockets.Stub.asInterface(socketLink.await());
            workerUid = worker.isolatedUid();
            if (workerUid <= 0 || Process.isApplicationUid(workerUid) ||
                    !UserHandle.getUserHandleForUid(workerUid).equals(Process.myUserHandle()))
                throw new SecurityException("E_ISOLATED_IDENTITY");
            sockets = new IConnectorSockets.Stub() {
                @Override public ParcelFileDescriptor openTelegramSocket(String address) throws RemoteException {
                    verifyWorker();
                    return broker.openTelegramSocket(address);
                }
                @Override public ParcelFileDescriptor openWebSocket(String host) throws RemoteException {
                    verifyWorker();
                    return broker.openWebSocket(host);
                }
            };
        } catch (Exception failure) {
            socketLink.close();
            workerLink.close();
            throw new IOException("E_ISOLATED_CONNECTOR");
        }
    }

    public void verifyWorker() {
        if (Binder.getCallingUid() != workerUid || !isAlive()) throw new SecurityException("E_ISOLATED_CALLER");
    }

    public boolean isAlive() {
        IBinder workerBinder = worker.asBinder(), brokerBinder = broker.asBinder();
        return workerLink.binder == workerBinder && socketLink.binder == brokerBinder &&
                workerBinder.isBinderAlive() && brokerBinder.isBinderAlive();
    }

    private static final class ServiceLink implements ServiceConnection {
        private final Context context;
        private final ComponentName component;
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile IBinder binder;
        private boolean bound;

        ServiceLink(Context context, String className) {
            this.context = context;
            component = new ComponentName(BuildConfig.ISOLATED_HOST, "local.ttlab.isolated." + className);
        }

        void bind(int flags) throws IOException {
            bound = context.bindService(new Intent().setComponent(component), this, flags);
            if (!bound) throw new IOException("E_ISOLATED_CONNECTOR");
        }

        IBinder await() throws Exception {
            if (!ready.await(12, TimeUnit.SECONDS) || binder == null) throw new IOException("E_ISOLATED_CONNECTOR");
            return binder;
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            ready.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { onNullBinding(name); }
        @Override public void onBindingDied(ComponentName name) { onNullBinding(name); }
        @Override public void onNullBinding(ComponentName name) {
            binder = null;
            ready.countDown();
        }

        void close() {
            binder = null;
            if (bound) context.unbindService(this);
        }
    }
}
