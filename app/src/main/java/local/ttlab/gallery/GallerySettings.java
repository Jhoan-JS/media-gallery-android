package local.ttlab.gallery;

import android.app.Dialog;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

final class GallerySettings {
    static void show(SecureActivity activity, Runnable connections, Consumer<LinearLayout> preferences, Runnable help) {
        if (!GalleryWorkspace.usable(activity)) return;
        Dialog dialog = new SecureDialogs.Dialog(activity, R.style.GalleryLibraryTheme);
        LinearLayout page = GalleryWorkspace.page(activity);
        LinearLayout bar = GalleryWorkspace.toolbar(activity, "Ajustes", null, null);
        ImageButton back = GalleryUi.icon(activity, R.drawable.ic_gallery_back, "Volver");
        back.setOnClickListener(view -> dialog.dismiss());
        bar.addView(back, 0);
        page.addView(bar);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20));
        GalleryWorkspace.sectionLabel(activity, rows, "Biblioteca");
        GalleryWorkspace.link(activity, rows, "Fuentes y conexiones", SourceId.label(activity.state.key), () -> {
            dialog.dismiss();
            connections.run();
        });
        preferences.accept(rows);
        GalleryWorkspace.sectionLabel(activity, rows, "Privacidad y recuperación");
        GalleryWorkspace.link(activity, rows, "Huella y biometría", "Opcional, con PIN de respaldo", () -> {
            dialog.dismiss();
            BiometricUnlock.showSettings(activity);
        });
        GalleryWorkspace.link(activity, rows, "Respaldo cifrado", "Exportar o restaurar", () ->
                activity.startActivity(activity.sourceIntent(RecoveryActivity.class)));
        GalleryWorkspace.link(activity, rows, "Protección local", "Cómo se protege la biblioteca", help);
        GalleryWorkspace.link(activity, rows, "Bloquear biblioteca", "Pedir PIN o huella al volver", () -> {
            dialog.dismiss();
            activity.lock();
        });
        TextView version = activity.text("Tato Galería " + BuildConfig.VERSION_NAME, 12,
                activity.getColor(R.color.gallery_muted));
        version.setPadding(activity.dp(12), activity.dp(24), activity.dp(12), 0);
        rows.addView(version);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(page);
        dialog.getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }
}
