package local.ttlab.isolated;

import android.app.AppComponentFactory;
import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.os.Process;

public final class IsolatedApplicationFactory extends AppComponentFactory {
    private AppComponentFactory normal;

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (sidecarService() != null) return new Application();
        return normal(loader).instantiateApplication(loader, name);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        String sidecar = sidecarService();
        if (sidecar != null) {
            if (!sidecar.equals(name)) throw new SecurityException("E_ISOLATED_COMPONENT");
            return super.instantiateService(loader, name, intent);
        }
        return normal(loader).instantiateService(loader, name, intent);
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return normal(loader).instantiateActivity(loader, name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return normal(loader).instantiateProvider(loader, name);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return normal(loader).instantiateReceiver(loader, name, intent);
    }

    private synchronized AppComponentFactory normal(ClassLoader loader)
            throws InstantiationException, IllegalAccessException {
        if (sidecarService() != null) throw new SecurityException("E_ISOLATED_COMPONENT");
        if (normal != null) return normal;
        try {
            Class<?> type = Class.forName("androidx.core.app.CoreComponentFactory", true, loader);
            normal = type.asSubclass(AppComponentFactory.class).newInstance();
        } catch (ClassNotFoundException standaloneWorker) {
            normal = new AppComponentFactory();
        }
        return normal;
    }

    private static String sidecarService() {
        if (!Process.isApplicationUid(Process.myUid())) return SourceWorkerService.class.getName();
        String process = Application.getProcessName();
        return process != null && process.endsWith(":sourceSockets") ? SocketBrokerService.class.getName() : null;
    }
}
