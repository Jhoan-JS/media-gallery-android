package local.ttlab.gallery;

import android.view.View;
import android.widget.TextView;

import java.util.function.Consumer;

final class GalleryConnections {
    static View page(SecureActivity activity, Consumer<String> select, Runnable openLibrary,
                     Runnable configure, Runnable settings) {
        State origin = activity.state;
        long expected = activity.unlockEpoch();
        return GalleryWorkspace.directory(activity, "Fuentes", "Fuente actual", rows -> {
            boolean local = SourceId.local(origin.key);
            TextView status = GalleryWorkspace.link(activity, rows, SourceId.label(origin.key),
                    local ? "Copias cifradas en este dispositivo" : "Comprobando conexión…", openLibrary);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            GalleryWorkspace.link(activity, rows, local ? "Importar archivos" : "Configurar conexión",
                    local ? "Añadir fotos y videos de este teléfono" : "Sesión, permisos y acceso", configure);
            GalleryWorkspace.sectionLabel(activity, rows, "Otras bibliotecas");
            for (String key : UiSource.SOURCES) {
                if (origin.key.equals(key)) continue;
                GalleryWorkspace.link(activity, rows, SourceId.label(key), sourceDetail(key), () -> select.accept(key));
            }
            if (!local) origin.database.execute(() -> {
                String result;
                try {
                    if (origin.source instanceof WebSource) {
                        result = ((WebSource) origin.source).enabled() ? "Conectada"
                                : "Pendiente de configurar";
                    } else {
                        origin.source.checkAuthorization();
                        result = "Conectada";
                    }
                } catch (Exception failure) {
                    result = SourceId.web(origin.key) ? WebSource.message(failure)
                            : ConnectionMessage.describe(failure, Profiles.owner(), origin.key);
                }
                String message = result;
                activity.runOnUiThread(() -> {
                    if (activity.state == origin && activity.biometricCurrent(expected) && status.isAttachedToWindow()) {
                        status.setText(message);
                    }
                });
            });
        }, settings);
    }

    static String sourceDetail(String key) {
        if (SourceId.local(key)) return "Almacenamiento cifrado";
        if (SourceId.web(key)) return "Buscar por etiquetas";
        return "Chats y canales autorizados";
    }
}
