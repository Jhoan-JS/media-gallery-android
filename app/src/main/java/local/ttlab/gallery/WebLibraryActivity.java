package local.ttlab.gallery;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class WebLibraryActivity extends SecureActivity {
    private static final int SETUP = 601, VIEWER = 602;
    private static final String[] TYPES = {"Todo", "Imágenes", "Videos"};
    private static final String[] COLLECTIONS = {"Biblioteca", "Favoritos", "Ver después", "Continuar", "Historial"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable delayedSearch = () -> submit(false);
    private final Button[] filters = new Button[TYPES.length];
    private WebSource web;
    private State live;
    private EditText terms;
    private ImageButton search, clear;
    private Button more, connect, home;
    private TextView heading, status;
    private GalleryGridView grid;
    private PagedAdapter adapter;
    private String query = "", homeTags = "", pendingTag;
    private int type, collection, revision, nextPage;
    private boolean gelbooru, active, enabled, moreAllowed, editingTerms, viewing;
    private boolean checking = true;
    private CancellationSignal request;

    static Intent intent(Context context, String source) {
        return new Intent(context, WebLibraryActivity.class).putExtra("source", SourceId.require(source));
    }

    @Override protected void onCreate(Bundle saved) {
        setTheme(R.style.GalleryLibraryTheme);
        super.onCreate(saved);
        if (state == null || !SourceId.web(state.key)) return;
        web = (WebSource) state.source;
        gelbooru = WebSource.GELBOORU.equals(state.key);
        homeTags = state.preferences.getString("web-home-tags", "");
        query = gelbooru ? "" : state.preferences.getString("web-query", "");
        type = Math.max(0, Math.min(2, state.preferences.getInt("web-type", 0)));
        collection = gelbooru ? 0 : Math.max(0, Math.min(4, state.preferences.getInt("web-collection", 0)));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.gallery_surface));

        LinearLayout header = row(page);
        header.addView(UiSource.selector(this, this::leaveForSource));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, dp(56), 1));
        ImageButton options = GalleryUi.icon(this, R.drawable.ic_gallery_settings, "Ajustes de " + SourceId.label(state.key));
        options.setOnClickListener(view -> options());
        header.addView(options);

        LinearLayout searchBar = row(page, 0);
        searchBar.setBackground(GalleryUi.background(getColor(R.color.gallery_surface_container), dp(16)));
        LinearLayout.LayoutParams searchParams = (LinearLayout.LayoutParams) searchBar.getLayoutParams();
        searchParams.setMargins(dp(16), dp(10), dp(16), dp(10));
        searchBar.setLayoutParams(searchParams);
        search = GalleryUi.icon(this, R.drawable.ic_gallery_search, "Buscar etiquetas");
        search.setOnClickListener(view -> submit(true));
        searchBar.addView(search);
        terms = tagField("Buscar por etiquetas");
        terms.setBackground(null);
        terms.setText(query);
        terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(true);
            return true;
        });
        terms.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(delayedSearch);
                if (gelbooru && !editingTerms && ready() && enabled) handler.postDelayed(delayedSearch, 850);
                if (clear != null) clear.setVisibility(s.length() == 0 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchBar.addView(terms, new LinearLayout.LayoutParams(0, dp(52), 1));
        clear = GalleryUi.icon(this, R.drawable.ic_gallery_close, "Borrar búsqueda");
        clear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        clear.setOnClickListener(view -> {
            setTerms("");
            if (gelbooru) submit(true);
        });
        searchBar.addView(clear);

        LinearLayout context = row(page);
        heading = text("", 22, getColor(R.color.gallery_foreground));
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        context.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        home = GalleryUi.button(this, "Inicio", false);
        home.setOnClickListener(view -> {
            if (gelbooru) {
                setTerms("");
                submit(true);
            } else {
                setTerms(query);
                collection = 0;
                persist();
                render("");
            }
        });
        context.addView(home);

        LinearLayout types = row(page);
        for (int index = 0; index < TYPES.length; index++) {
            final int selected = index;
            Button button = GalleryUi.button(this, TYPES[index], false);
            button.setPadding(dp(6), 0, dp(6), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (index > 0) params.setMarginStart(dp(8));
            types.addView(button, params);
            filters[index] = button;
            button.setOnClickListener(view -> {
                if (type == selected || request != null) return;
                remember();
                type = selected;
                persist();
                render("");
            });
        }

        status = text("", 14, getColor(R.color.gallery_muted));
        status.setPadding(dp(16), dp(6), dp(16), dp(7));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        connect = GalleryUi.button(this, "Conectar " + SourceId.label(state.key), true);
        connect.setOnClickListener(view -> setup());
        row(page).addView(connect, new LinearLayout.LayoutParams(-1, dp(48)));

        grid = new GalleryGridView(this);
        int width = getResources().getConfiguration().screenWidthDp;
        grid.setNumColumns(width < 360 ? 2 : width >= 700 ? 5 : 3);
        grid.setHorizontalSpacing(dp(5));
        grid.setVerticalSpacing(dp(5));
        grid.setPadding(dp(16), 0, dp(16), dp(12));
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
        LinearLayout footer = row(page);
        footer.setPadding(dp(16), dp(4), dp(16), dp(4));
        more = GalleryUi.button(this, "Cargar más", false);
        more.setOnClickListener(view -> fetch(false));
        footer.addView(more, new LinearLayout.LayoutParams(-1, dp(48)));
        page.setFocusableInTouchMode(true);
        content(page);
        page.requestFocus();
        paintControls();
    }

    private LinearLayout row(LinearLayout page) { return row(page, 16); }

    private LinearLayout row(LinearLayout page, int padding) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(padding), 0, dp(padding), 0);
        page.addView(row);
        return row;
    }

    private EditText tagField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(getColor(R.color.gallery_foreground));
        field.setHintTextColor(getColor(R.color.gallery_muted));
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_outline)));
        field.setHint(hint);
        field.setSaveEnabled(false);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(320)});
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return field;
    }

    private void setTerms(String text) {
        editingTerms = true;
        terms.setText(text);
        editingTerms = false;
    }

    private void options() {
        String[] labels = gelbooru ? new String[]{"Etiquetas de inicio", "Conexión", "Colecciones de la sesión"}
                : new String[]{"Conexión", "Colecciones"};
        GalleryUi.menu(this, SourceId.label(state.key), labels, index -> {
            if (gelbooru && index == 0) showHomeTags();
            else if (index == (gelbooru ? 1 : 0)) setup();
            else showCollections();
        });
    }

    private void showHomeTags() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), 0);
        TextView note = text("Gelbooru cargará publicaciones recientes que coincidan con todas estas etiquetas. Puedes dejar el campo vacío.", 15, getColor(R.color.gallery_muted));
        body.addView(note);
        EditText tags = tagField("Etiquetas separadas por espacios");
        tags.setText(homeTags);
        body.addView(tags, new LinearLayout.LayoutParams(-1, dp(52)));
        android.app.AlertDialog dialog = new SecureDialogs.Builder(this)
                .setTitle("Etiquetas de inicio").setView(body).setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null).create();
        trackPrivateDialog(dialog);
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = normalized(tags.getText().toString());
            if (!validTags(value)) {
                tags.setError("Usa hasta ocho etiquetas separadas por espacios.");
                return;
            }
            try { state.preferences.edit().putString("web-home-tags", value).apply(); }
            catch (RuntimeException unavailable) { tags.setError("No se pudieron guardar los ajustes."); return; }
            homeTags = value;
            dialog.dismiss();
            setTerms("");
            submit(true);
        });
    }

    private void showCollections() {
        if (!enabled || request != null) return;
        GalleryUi.menu(this, gelbooru ? "Colecciones de la sesión" : "Colecciones", COLLECTIONS, selected -> {
            remember();
            collection = selected;
            persist();
            render("");
        });
    }

    private void leaveForSource() {
        startActivity(new Intent(this, MainActivity.class).putExtra("source", UiSource.key(this))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        active = web != null;
        viewing = false;
        if (active && state.unlocked) checkConnector();
    }

    @Override void onUnlocked() {
        if (active) checkConnector();
    }

    @Override protected void onPause() {
        active = false;
        revision++;
        handler.removeCallbacks(delayedSearch);
        remember();
        cancelRequest();
        if (grid != null) closeGrid();
        if (!viewing) discardLive();
        super.onPause();
    }

    @Override protected void onDestroy() {
        discardLive();
        super.onDestroy();
    }

    private void discardLive() {
        State.endWebLive(live);
        live = null;
    }

    private boolean ready() { return active && state.unlocked && biometricCurrent(unlockEpoch()); }
    private State browsing() { return gelbooru ? live : state; }

    private void checkConnector() {
        if (!ready()) return;
        int generation = ++revision;
        State existing = live;
        checking = true;
        if (gelbooru && (existing == null || existing.database.isShutdown())) status.setText("Abriendo Gelbooru…");
        paintControls();
        state.database.execute(() -> {
            String setupMessage = "Conecta " + SourceId.label(state.key) + " para ver sus publicaciones.";
            boolean connected = false;
            State opened = existing != null && !existing.database.isShutdown() ? existing : null;
            try {
                connected = web.enabled();
                if (connected && gelbooru && opened == null) opened = State.startWebLive(this, state);
            } catch (Exception unavailable) {
                connected = false;
                setupMessage = connectorMessage(unavailable);
            }
            final State session = opened;
            final boolean linked = connected;
            final String message = setupMessage;
            runOnUiThread(() -> {
                if (!ready() || revision != generation) {
                    if (session != existing) State.endWebLive(session);
                    return;
                }
                checking = false;
                live = session;
                enabled = linked;
                if (!enabled) {
                    discardLive();
                    closeGrid();
                    status.setText(message);
                    paintControls();
                    return;
                }
                if (pendingTag != null) {
                    setTerms(pendingTag);
                    pendingTag = null;
                    submit(true);
                } else if (gelbooru && session != existing) {
                    collection = 0;
                    fetch(true);
                } else render("");
            });
        });
    }

    private static String connectorMessage(Exception unavailable) {
        if (unavailable instanceof SecurityException) return "Instala Conector Web con la misma firma en este perfil.";
        return WebSource.message(unavailable);
    }

    private void setup() {
        if (!ready()) return;
        try {
            web.guard();
            startActivityForResult(web.configure(), SETUP);
        } catch (Exception unavailable) { status.setText(connectorMessage(unavailable)); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == VIEWER && result == RESULT_OK && data != null) pendingTag = data.getStringExtra("web-tag");
    }

    private static String normalized(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private static boolean validTags(String value) {
        if (value.isEmpty()) return true;
        String[] words = value.split(" ");
        if (words.length > 8) return false;
        boolean positive = false;
        for (String word : words) {
            if (!word.matches("-?[\\p{L}\\p{N}_().+'!?-]{1,80}") || "-".equals(word)) return false;
            if (!word.startsWith("-")) positive = true;
        }
        return positive;
    }

    private void submit(boolean committed) {
        handler.removeCallbacks(delayedSearch);
        if (!ready() || !enabled) return;
        String value = normalized(terms.getText().toString());
        if (!validTags(value) || (!gelbooru && value.isEmpty())) {
            status.setText("Usa de una a ocho etiquetas separadas por espacios.");
            return;
        }
        if (!committed && value.equals(query) && collection == 0) return;
        if (committed) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(terms.getWindowToken(), 0);
        boolean same = value.equals(query) && collection == 0;
        if (same && request != null) return;
        remember();
        cancelRequest();
        query = value;
        collection = 0;
        persist();
        if (!same) closeGrid();
        fetch(true);
    }

    private String queryKey() { return gelbooru && query.isEmpty() ? ":" : query; }

    private void cancelRequest() {
        if (request == null) return;
        request.cancel();
        request = null;
    }

    private void fetch(boolean first) {
        State origin = browsing();
        if (!ready() || !enabled || origin == null || origin.database.isShutdown() || request != null) return;
        if ((!gelbooru && query.isEmpty()) || (!first && !moreAllowed)) return;
        CancellationSignal signal = new CancellationSignal();
        request = signal;
        String key = queryKey();
        String tags = gelbooru && query.isEmpty() ? homeTags : query;
        int page = first ? 0 : nextPage;
        status.setText(first ? (query.isEmpty() ? "Cargando recientes…" : "Buscando…") : "Cargando más…");
        paintControls();
        origin.database.execute(() -> {
            String problem = "";
            try {
                WebSource.Result result = ((WebSource) origin.source).search(tags, page, signal);
                signal.throwIfCanceled();
                origin.webCatalogue.store(key, page, result, origin.liveId != null);
            } catch (Exception failure) {
                problem = WebSource.message(failure);
            }
            String message = problem;
            runOnUiThread(() -> {
                if (request != signal) return;
                request = null;
                if (ready() && origin == browsing()) render(message);
            });
        });
    }

    private MediaFilter filter() {
        MediaFilter result = new MediaFilter();
        result.type = type;
        result.collection = collection < 4 ? collection : 0;
        result.recent = collection == 4;
        if (result.recent) result.sort = 6;
        result.webQuery = collection == 0 ? queryKey() : null;
        return result;
    }

    private void render(String message) {
        State origin = browsing();
        if (!ready() || !enabled || origin == null || origin.database.isShutdown()) return;
        int generation = ++revision;
        MediaFilter filter = filter();
        if (adapter != null && adapter.snapshot().key().equals(filter.key())) remember();
        origin.database.execute(() -> {
            try {
                int count = origin.library.count(filter);
                Library.Viewport stored = origin.library.viewport(filter);
                int next = filter.webQuery == null || filter.webQuery.isEmpty() ? -1 : origin.webCatalogue.next(filter.webQuery);
                runOnUiThread(() -> {
                    if (!ready() || revision != generation || origin != browsing()) return;
                    closeGrid();
                    adapter = new PagedAdapter(this, origin, filter, count);
                    grid.setAdapter(adapter);
                    if (stored != null && count > 0) GalleryUi.restoreGrid(grid, stored.position, stored.top);
                    nextPage = next;
                    moreAllowed = next > 0;
                    if (!message.isEmpty()) status.setText(message);
                    else if (count > 0) status.setText(count == 1 ? "1 publicación" : count + " publicaciones");
                    else if (!gelbooru && query.isEmpty() && collection == 0) status.setText("Busca por etiquetas.");
                    else if (moreAllowed) status.setText("Puedes cargar más publicaciones para este filtro.");
                    else status.setText("No hay publicaciones en esta vista.");
                    paintControls();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (ready() && generation == revision) status.setText(WebSource.message(failure)); });
            }
        });
    }

    private void open(int position) {
        State origin = browsing();
        if (!ready() || !enabled || origin == null || adapter == null) return;
        Item item = adapter.item(position);
        if (item == null) return;
        remember();
        Intent intent = sourceIntent(ViewerActivity.class).putExtra("channel", item.channel).putExtra("id", item.id)
                .putExtra("viewer-filter", adapter.snapshot().bundle());
        if (origin.liveId != null) intent.putExtra("web-live", origin.liveId);
        viewing = true;
        startActivityForResult(intent, VIEWER);
    }

    private void remember() {
        State origin = browsing();
        if (origin == null || origin.database.isShutdown() || adapter == null || grid.getChildCount() == 0) return;
        int first = grid.getFirstVisiblePosition();
        int top = grid.getChildAt(0).getTop();
        Item item = adapter.item(first);
        String key = adapter.snapshot().key();
        origin.database.execute(() -> {
            try { origin.library.saveViewport(key, item, first, top); } catch (RuntimeException ignored) { }
        });
    }

    private void closeGrid() {
        if (adapter != null) adapter.close();
        adapter = null;
        grid.setAdapter(null);
    }

    private void persist() {
        try {
            android.content.SharedPreferences.Editor edit = state.preferences.edit().putInt("web-type", type);
            if (!gelbooru) edit.putString("web-query", query).putInt("web-collection", collection);
            edit.apply();
        } catch (RuntimeException unavailable) { status.setText("No se pudieron guardar los ajustes."); }
    }

    private void paintControls() {
        boolean idle = request == null;
        heading.setText(collection > 0 ? COLLECTIONS[collection]
                : query.isEmpty() ? gelbooru ? "Recientes" : "Biblioteca" : "Resultados");
        boolean available = enabled && !checking;
        search.setEnabled(available);
        clear.setEnabled(available);
        terms.setEnabled(available);
        ((View) connect.getParent()).setVisibility(enabled || checking ? View.GONE : View.VISIBLE);
        home.setVisibility(collection > 0 || (gelbooru && !query.isEmpty()) ? View.VISIBLE : View.GONE);
        for (int index = 0; index < filters.length; index++) {
            Button filter = filters[index];
            boolean selected = index == type;
            filter.setSelected(selected);
            filter.setEnabled(available && idle);
            filter.setTextColor(getColor(selected ? R.color.gallery_on_primary : R.color.gallery_foreground));
            filter.setBackground(GalleryUi.background(getColor(selected ? R.color.gallery_primary
                    : R.color.gallery_surface_container), dp(12)));
        }
        more.setEnabled(available && idle && moreAllowed);
        ((View) more.getParent()).setVisibility(enabled && moreAllowed ? View.VISIBLE : View.GONE);
    }
}
