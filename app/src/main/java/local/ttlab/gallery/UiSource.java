package local.ttlab.gallery;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

final class UiSource {
    static final String[] SOURCES = {"tato", "telegram", WebSource.GELBOORU, WebSource.KEY, "local"};
    private static String previousSource, switchedTo;

    static String key(Context context) {
        String key = context.getSharedPreferences("gallery-appearance", Context.MODE_PRIVATE)
                .getString("source", "tato");
        try { return SourceId.require(key); }
        catch (IllegalArgumentException invalid) { return "tato"; }
    }

    static void remember(Context context, String key) {
        context.getSharedPreferences("gallery-appearance", Context.MODE_PRIVATE)
                .edit().putString("source", SourceId.require(key)).apply();
    }

    static String screenKey(Context context) {
        return context instanceof SecureActivity && ((SecureActivity) context).state != null
                ? ((SecureActivity) context).state.key : key(context);
    }

    static boolean available(Context context) { return true; }
    static boolean isTato(Context context) { return "tato".equals(screenKey(context)); }
    static boolean isLocal(Context context) { return SourceId.local(screenKey(context)); }
    static String label(Context context) { return SourceId.label(screenKey(context)); }

    static Button selector(SecureActivity activity, Runnable changed) {
        Button button = GalleryUi.button(activity, label(activity), false);
        button.setContentDescription("Fuente: " + label(activity) + ". Cambiar fuente");
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_gallery_chevron_down, 0);
        button.setCompoundDrawablePadding(activity.dp(6));
        button.setOnClickListener(view -> choose(activity, changed));
        return button;
    }

    static void choose(SecureActivity activity, Runnable changed) {
        if (!GalleryWorkspace.usable(activity)) return;
        Dialog dialog = new SecureDialogs.Dialog(activity);
        LinearLayout panel = GalleryWorkspace.page(activity);
        panel.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(12));
        panel.setBackground(GalleryUi.background(activity.getColor(R.color.gallery_surface_container), activity.dp(22)));
        GalleryWorkspace.sectionLabel(activity, panel, "Cambiar fuente");
        for (String key : SOURCES) {
            String detail = key.equals(screenKey(activity)) ? "Biblioteca actual" : GalleryConnections.sourceDetail(key);
            GalleryWorkspace.link(activity, panel, SourceId.label(key), detail, () -> {
                dialog.dismiss();
                select(activity, key, GalleryWorkspace.LIBRARY, changed);
            });
        }
        Button close = GalleryUi.button(activity, "Cerrar", false);
        close.setOnClickListener(view -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, activity.dp(48)));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(-1, -2);
    }

    static void select(SecureActivity activity, String selected, String section, Runnable changed) {
        String key = SourceId.require(selected);
        if (!GalleryWorkspace.usable(activity) || key.equals(screenKey(activity))) return;
        previousSource = screenKey(activity);
        switchedTo = key;
        switchTo(activity, key, section, changed);
    }

    static boolean hasPrevious(SecureActivity activity) {
        return previousSource != null && screenKey(activity).equals(switchedTo);
    }

    static boolean back(SecureActivity activity, Runnable changed) {
        if (!GalleryWorkspace.usable(activity) || !hasPrevious(activity)) return false;
        String key = previousSource;
        previousSource = switchedTo = null;
        switchTo(activity, key, GalleryWorkspace.LIBRARY, changed);
        return true;
    }

    private static void switchTo(SecureActivity activity, String key, String section, Runnable changed) {
        remember(activity, key);
        if (SourceId.web(key) || activity instanceof WebLibraryActivity) {
            open(activity, key, section);
            activity.finish();
        } else {
            changed.run();
            activity.updateWorkspaceBack();
        }
    }

    static void open(SecureActivity activity, String key, String section) {
        Intent intent = SourceId.web(key) ? WebLibraryActivity.intent(activity, key)
                : new Intent(activity, MainActivity.class).putExtra("source", SourceId.require(key));
        intent.putExtra(GalleryWorkspace.SECTION, section);
        if (!SourceId.web(key)) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }
}
