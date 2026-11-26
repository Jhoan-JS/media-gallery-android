package local.ttlab.gallery;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

/** Shared navigation and screen chrome. Catalogues and transfers keep their own lifecycles. */
final class GalleryWorkspace {
    static final String LIBRARY = "library", SAVED = "saved", DOWNLOADS = "downloads", SOURCES = "sources";
    static final String SECTION = "workspace-section";
    static final String DOWNLOAD_FILES = "download-files";
    static final int DOWNLOAD_REQUEST = 78;
    static final String TITLE = "workspace-title";
    private final SecureActivity activity;
    private final Consumer<String> navigate;
    private final LinearLayout shell;
    private final FrameLayout body, bar;
    private final View library;
    private String tab;
    private View overlay;
    private boolean wide;

    GalleryWorkspace(SecureActivity activity, View library, Consumer<String> navigate) {
        this.activity = activity;
        this.navigate = navigate;
        this.library = library;
        shell = new LinearLayout(activity);
        shell.setBackgroundColor(activity.getColor(R.color.gallery_surface));
        shell.setFocusableInTouchMode(true);
        body = new FrameLayout(activity);
        body.addView(library, new FrameLayout.LayoutParams(-1, -1));
        bar = new FrameLayout(activity);
        tab = LIBRARY;
        resize();
    }

    View view() { return shell; }
    boolean is(String section) { return section.equals(tab); }
    boolean showingOverlay() { return overlay != null; }

    static boolean usesRail(Configuration size) {
        return size.screenWidthDp >= 600 && size.screenHeightDp >= 340;
    }

    void resize() {
        wide = usesRail(activity.getResources().getConfiguration());
        shell.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        shell.removeAllViews();
        if (wide) shell.addView(bar, new LinearLayout.LayoutParams(activity.dp(100), -1));
        shell.addView(body, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        if (!wide) shell.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        updateBar();
    }

    void show(String section, View next) {
        if (overlay != null) body.removeView(overlay);
        overlay = next;
        if (overlay != null) body.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        library.setVisibility(overlay == null ? View.VISIBLE : View.GONE);
        if (!is(section)) {
            tab = section;
            updateBar();
        }
        activity.updateWorkspaceBack();
    }

    private void updateBar() {
        bar.removeAllViews();
        bar.addView(GalleryUi.navigation(activity, tab, wide, navigate));
    }

    static String destination(Intent intent) {
        String tab = intent == null ? null : intent.getStringExtra(SECTION);
        if (SAVED.equals(tab) || DOWNLOADS.equals(tab) || SOURCES.equals(tab) || DOWNLOAD_FILES.equals(tab)) return tab;
        return LIBRARY;
    }

    static boolean usable(SecureActivity activity) {
        return activity.state != null && activity.state.unlocked && activity.biometricCurrent(activity.unlockEpoch());
    }

    static LinearLayout toolbar(SecureActivity activity, String heading, Runnable sourceChanged, Runnable settings) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(16), activity.dp(5), activity.dp(8), activity.dp(5));
        TextView title = activity.text(heading, 24, activity.getColor(R.color.gallery_foreground));
        title.setTag(TITLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, activity.dp(52), 1));
        if (sourceChanged != null) {
            Button source = UiSource.selector(activity, sourceChanged);
            source.setTag("source-selector");
            source.setPadding(activity.dp(10), 0, activity.dp(10), 0);
            source.setMaxWidth(activity.dp(147));
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(source, new LinearLayout.LayoutParams(-2, activity.dp(48)));
        }
        if (settings != null) {
            ImageButton gear = GalleryUi.icon(activity, R.drawable.ic_gallery_settings, "Ajustes");
            gear.setOnClickListener(view -> settings.run());
            row.addView(gear);
        }
        return row;
    }

    static LinearLayout page(SecureActivity activity) {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(activity.getColor(R.color.gallery_surface));
        return page;
    }

    static View directory(SecureActivity activity, String title, String source, Consumer<LinearLayout> fill,
                          Runnable settings) {
        LinearLayout page = page(activity);
        page.addView(toolbar(activity, title, null, settings));
        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(24));
        if (source != null) sectionLabel(activity, rows, source);
        fill.accept(rows);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    static void sectionLabel(SecureActivity activity, LinearLayout parent, String label) {
        TextView section = activity.text(label, 14, activity.getColor(R.color.gallery_primary));
        section.setAccessibilityHeading(true);
        section.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        section.setPadding(activity.dp(12), activity.dp(18), activity.dp(12), activity.dp(6));
        parent.addView(section);
    }

    static TextView link(SecureActivity activity, LinearLayout parent, String name, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(activity.dp(58));
        row.setPadding(activity.dp(12), activity.dp(9), activity.dp(12), activity.dp(9));
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.gallery_control);
        row.addView(activity.text(name, 16, activity.getColor(R.color.gallery_foreground)));
        TextView note = activity.text(detail, 13, activity.getColor(R.color.gallery_muted));
        note.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        note.setPadding(0, activity.dp(3), 0, 0);
        row.addView(note);
        row.setOnClickListener(view -> { if (usable(activity)) action.run(); });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(activity);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.setBackground(new ColorDrawable(activity.getColor(R.color.gallery_outline)));
        parent.addView(line, new LinearLayout.LayoutParams(-1, activity.dp(1)));
        return note;
    }
}
