package local.ttlab.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class WebLibraryActivity extends SecureActivity {
    private static final int SETUP = 601;
    private static final int VIEWER = 602;
    private static final String[] TYPES = {"Todo", "Imágenes", "Videos"};
    private static final String[] SECTIONS = {"Biblioteca web", "Favoritos", "Ver después", "Continuar", "Historial"};
    private WebSource web;
    private EditText terms;
    private Button search, more, typeButton, section, configure;
    private TextView status;
    private GalleryGridView grid;
    private PagedAdapter adapter;
    private String query = "", pendingTag;
    private int type, collection, revision, nextPage;
    private boolean active, enabled, moreAllowed;
    private CancellationSignal request;

    static Intent intent(Context context) {
        return new Intent(context, WebLibraryActivity.class).putExtra("source", WebSource.KEY);
    }

    static boolean linked(Context context) {
        return context.getSharedPreferences("gallery-appearance", MODE_PRIVATE).getBoolean("web-linked", false);
    }

    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.GalleryLibraryTheme);
        super.onCreate(saved);
        if (state == null || !SourceId.web(state.key)) return;
        web = (WebSource) state.source;
        query = state.preferences.getString("web-query", "");
        type = Math.max(0, Math.min(2, state.preferences.getInt("web-type", 0)));
        collection = Math.max(0, Math.min(4, state.preferences.getInt("web-collection", 0)));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.gallery_surface));
        LinearLayout header = row(page);
        section = GalleryUi.button(this, SECTIONS[collection] + " ▾", false);
        header.addView(section, new LinearLayout.LayoutParams(0, dp(48), 1));
        section.setOnClickListener(v -> GalleryUi.menu(this, "Colección", SECTIONS, selected -> {
            remember();
            collection = selected;
            persist();
            render("");
        }));
        header.addView(UiSource.selector(this, this::leaveForSource));

        LinearLayout input = row(page);
        terms = new EditText(this);
        terms.setSingleLine(true);
        terms.setTextColor(getColor(R.color.gallery_foreground));
        terms.setHintTextColor(getColor(R.color.gallery_muted));
        terms.setHint("Buscar etiquetas");
        terms.setText(state.preferences.getString("web-draft", query));
        terms.setSaveEnabled(false);
        terms.setFilters(new InputFilter[]{new InputFilter.LengthFilter(320)});
        terms.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        terms.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit();
            return true;
        });
        input.addView(terms, new LinearLayout.LayoutParams(0, dp(56), 1));
        search = GalleryUi.button(this, "Buscar", true);
        search.setOnClickListener(v -> submit());
        input.addView(search);

        LinearLayout actions = row(page);
        typeButton = GalleryUi.button(this, TYPES[type] + " ▾", false);
        typeButton.setOnClickListener(v -> GalleryUi.menu(this, "Tipo", TYPES, selected -> {
            remember();
            type = selected;
            persist();
            render("");
        }));
        actions.addView(typeButton);
        configure = GalleryUi.button(this, "Configurar", false);
        configure.setOnClickListener(v -> setup());
        actions.addView(configure);
        more = GalleryUi.button(this, "Cargar más", false);
        more.setOnClickListener(v -> fetch(false));
        actions.addView(more);

        status = text("", 14, getColor(R.color.gallery_muted));
        status.setPadding(dp(16), dp(5), dp(16), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        grid = new GalleryGridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(5));
        grid.setVerticalSpacing(dp(5));
        grid.setPadding(dp(12), 0, dp(12), dp(16));
        grid.setClipToPadding(false);
        grid.setSaveEnabled(false);
        grid.setDateNavigationEnabled(false);
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.setOnItemClickListener((parent, view, position, id) -> open(position));
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int value) {
                if (adapter != null) adapter.viewport(grid.getFirstVisiblePosition(), grid.getChildCount(), value == SCROLL_STATE_IDLE);
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (adapter != null) adapter.viewport(first, visible, false);
            }
        });
        page.setFocusableInTouchMode(true);
        content(page);
        page.requestFocus();
        controls();
    }

    private LinearLayout row(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        page.addView(row);
        return row;
    }

    private void leaveForSource() {
        String target = UiSource.key(this);
        startActivity(new Intent(this, MainActivity.class).putExtra("source", target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        active = web != null;
        if (active && state.unlocked) checkConnector();
    }

    @Override void onUnlocked() {
        if (active) checkConnector();
    }

    @Override protected void onPause() {
        active = false;
        revision++;
        remember();
        if (terms != null && state.unlocked) persist();
        if (grid != null) closeGrid();
        if (request != null) {
            request.cancel();
            request = null;
        }
        super.onPause();
    }

    private boolean ready() { return active && state.unlocked && biometricCurrent(unlockEpoch()); }

    private void checkConnector() {
        if (!ready()) return;
        int generation = ++revision;
        state.database.execute(() -> {
            boolean found = false;
            try { found = web.enabled(); } catch (Exception unavailable) { }
            final boolean result = found;
            runOnUiThread(() -> {
                if (!ready() || revision != generation) return;
                enabled = result;
                linked(result);
                if (enabled && pendingTag != null) {
                    terms.setText(pendingTag);
                    pendingTag = null;
                    submit();
                } else if (enabled) render("");
                else {
                    closeGrid();
                    status.setText("Instala Conector Web y habilita Rule34.xxx para buscar.");
                    controls();
                }
            });
        });
    }

    private void linked(boolean value) {
        getSharedPreferences("gallery-appearance", MODE_PRIVATE).edit().putBoolean("web-linked", value).apply();
    }

    private void setup() {
        if (!ready()) return;
        try {
            web.guard();
            startActivityForResult(WebSource.configure(), SETUP);
        } catch (Exception unavailable) { status.setText("Instala Conector Web con la misma firma en este perfil."); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SETUP && result != Activity.RESULT_CANCELED) {
            linked(result == Activity.RESULT_OK);
        } else if (code == VIEWER && result == Activity.RESULT_OK && data != null) {
            String tag = data.getStringExtra("web-tag");
            if (tag != null) {
                pendingTag = tag;
            }
        }
    }

    private void submit() {
        if (!ready() || !enabled || request != null) return;
        String text = terms.getText().toString().trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) { status.setText("Escribe una etiqueta para buscar."); return; }
        boolean same = text.equals(query) && collection == 0;
        remember();
        query = text;
        collection = 0;
        persist();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(terms.getWindowToken(), 0);
        if (!same) closeGrid();
        fetch(true);
    }

    private void fetch(boolean first) {
        if (!ready() || !enabled || request != null || query.isEmpty()) return;
        if (!first && !moreAllowed) return;
        CancellationSignal signal = new CancellationSignal();
        request = signal;
        String queryKey = query;
        int page = first ? 0 : nextPage;
        status.setText("Buscando…");
        controls();
        state.database.execute(() -> {
            String problem = "";
            try {
                WebSource.Result result = web.search(queryKey, page, signal);
                signal.throwIfCanceled();
                state.webCatalogue.store(queryKey, page, result);
            } catch (Exception failure) {
                problem = WebSource.message(failure);
            }
            String message = problem;
            runOnUiThread(() -> {
                if (request != signal) return;
                request = null;
                if (ready()) render(message);
            });
        });
    }

    private MediaFilter filter() {
        MediaFilter result = new MediaFilter();
        result.type = type;
        result.collection = collection < 4 ? collection : 0;
        result.recent = collection == 4;
        if (result.recent) result.sort = 6;
        result.webQuery = collection == 0 ? query : null;
        return result;
    }

    private void render(String message) {
        if (!ready() || !enabled) return;
        int generation = ++revision;
        MediaFilter filter = filter();
        if (adapter != null && adapter.snapshot().key().equals(filter.key())) remember();
        state.database.execute(() -> {
            try {
                int count = state.library.count(filter);
                Library.Viewport stored = state.library.viewport(filter);
                int next = filter.webQuery == null || filter.webQuery.isEmpty() ? -1 : state.webCatalogue.next(filter.webQuery);
                runOnUiThread(() -> {
                    if (!ready() || revision != generation) return;
                    closeGrid();
                    adapter = new PagedAdapter(this, state, filter, count);
                    grid.setAdapter(adapter);
                    if (stored != null && count > 0) grid.setSelectionFromTop(Math.min(stored.position, count - 1), stored.top);
                    nextPage = next;
                    moreAllowed = next > 0;
                    section.setText(SECTIONS[collection] + " ▾");
                    typeButton.setText(TYPES[type] + " ▾");
                    if (!message.isEmpty()) status.setText(message);
                    else if (count > 0) status.setText(count + " en esta vista");
                    else if (query.isEmpty() && collection == 0) status.setText("Busca por etiquetas. Solo se muestran resultados clasificados como seguros.");
                    else status.setText(moreAllowed ? "Sin coincidencias en lo cargado. Puedes cargar más."
                            : "Esta vista no tiene resultados guardados.");
                    controls();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (ready() && generation == revision) status.setText(WebSource.message(failure)); });
            }
        });
    }

    private void open(int position) {
        if (!ready() || !enabled || adapter == null) return;
        Item item = adapter.item(position);
        if (item == null) return;
        remember();
        Intent intent = new Intent(this, ViewerActivity.class).putExtra("source", state.key)
                .putExtra("channel", item.channel).putExtra("id", item.id)
                .putExtra("viewer-filter", adapter.snapshot().bundle());
        startActivityForResult(intent, VIEWER);
    }

    private void remember() {
        if (adapter == null || grid == null || grid.getChildCount() == 0) return;
        int first = grid.getFirstVisiblePosition();
        int top = grid.getChildAt(0).getTop();
        Item item = adapter.item(first);
        String key = adapter.snapshot().key();
        state.database.execute(() -> {
            try { state.library.saveViewport(key, item, first, top); } catch (RuntimeException ignored) { }
        });
    }

    private void closeGrid() {
        if (adapter != null) adapter.close();
        adapter = null;
        grid.setAdapter(null);
    }

    private void persist() {
        try {
            state.preferences.edit().putString("web-query", query).putString("web-draft", terms.getText().toString())
                    .putInt("web-type", type).putInt("web-collection", collection).apply();
        } catch (RuntimeException unavailable) { status.setText("No se pudo guardar la posición cifrada."); }
    }

    private void controls() {
        if (search == null) return;
        boolean idle = request == null;
        search.setEnabled(enabled && idle);
        terms.setEnabled(enabled);
        typeButton.setEnabled(enabled && idle);
        section.setEnabled(enabled && idle);
        configure.setEnabled(idle);
        more.setEnabled(enabled && idle && moreAllowed);
        more.setVisibility(enabled && moreAllowed ? View.VISIBLE : View.GONE);
    }
}
