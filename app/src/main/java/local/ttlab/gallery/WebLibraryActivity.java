package local.ttlab.gallery;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

public final class WebLibraryActivity extends SecureActivity {
    private static final int SETUP = 601, VIEWER = 602;
    private static final String[] TILE_SIZES = {"Grandes", "Medianas", "Compactas"};
    private static final String[] COLLECTIONS = {"Biblioteca", "Favoritos", "Ver después", "Continuar", "Historial"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable delayedSearch = () -> submit(false);
    private final Runnable loadFollowingPage = this::loadNearEnd;
    private WebSource web;
    private State live;
    private EditText terms;
    private ImageButton search, clear, filters, savedSearches;
    private Button retry, connect, home;
    private TextView heading, status;
    private ProgressBar progress;
    private GalleryGridView grid;
    private PagedAdapter adapter;
    private GalleryWorkspace workspace;
    private String pendingSection;
    private boolean refreshEndpoint = true;
    private String query = "", homeTags = "", pendingQuery;
    private int type, shape, watched, tileSize, collection, revision, nextPage, sparsePages;
    private boolean gelbooru, active, enabled, editingTerms, viewing, rendering, retryFirst;
    private String pageError = "";
    private boolean checking = true, fitThumbnails;
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
        type = preference("web-filter-type", 2);
        shape = preference("web-filter-shape", 3);
        watched = preference("web-filter-watched", 2);
        tileSize = preference("web-tile-size", TILE_SIZES.length - 1);
        fitThumbnails = state.preferences.getBoolean("web-fit-thumbnails", false);
        UiSource.remember(this, state.key);
        pendingSection = saved == null ? GalleryWorkspace.destination(getIntent())
                : saved.getString(GalleryWorkspace.SECTION, GalleryWorkspace.LIBRARY);

        LinearLayout page = GalleryWorkspace.page(this);
        LinearLayout header = GalleryWorkspace.toolbar(this, "Biblioteca", () -> navigate(GalleryWorkspace.LIBRARY), this::showSettings);
        heading = header.findViewWithTag(GalleryWorkspace.TITLE);
        page.addView(header);

        LinearLayout tools = row(page);
        LinearLayout.LayoutParams toolsParams = (LinearLayout.LayoutParams) tools.getLayoutParams();
        toolsParams.topMargin = dp(4);
        toolsParams.bottomMargin = dp(8);
        tools.setLayoutParams(toolsParams);
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(GalleryUi.background(getColor(R.color.gallery_surface_container), dp(20)));
        tools.addView(searchBar, new LinearLayout.LayoutParams(0, -2, 1));
        filters = GalleryUi.icon(this, R.drawable.ic_gallery_filter, "Filtros");
        filters.setTooltipText("Filtros");
        filters.setOnClickListener(view -> showFilters());
        LinearLayout.LayoutParams filterSize = (LinearLayout.LayoutParams) filters.getLayoutParams();
        filterSize.setMarginStart(dp(8));
        tools.addView(filters, filterSize);
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
                if (clear != null) clear.setVisibility(terms.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchBar.addView(terms, new LinearLayout.LayoutParams(0, dp(52), 1));
        clear = GalleryUi.icon(this, R.drawable.ic_gallery_close, "Borrar búsqueda");
        clear.setVisibility(terms.length() == 0 ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(view -> {
            setTerms("");
            submit(true);
        });
        searchBar.addView(clear);

        LinearLayout context = row(page);
        context.addView(new View(this), new LinearLayout.LayoutParams(0, dp(42), 1));
        savedSearches = GalleryUi.icon(this, R.drawable.ic_gallery_star, "Búsquedas guardadas");
        savedSearches.setTooltipText("Búsquedas guardadas");
        savedSearches.setOnClickListener(view -> showSavedSearches());
        context.addView(savedSearches);
        ImageButton options = GalleryUi.icon(this, R.drawable.ic_gallery_more, "Opciones de biblioteca");
        options.setOnClickListener(view -> options());
        context.addView(options);
        home = GalleryUi.button(this, "Inicio", false);
        home.setOnClickListener(view -> {
            if (workspace.is(GalleryWorkspace.SAVED)) navigate(GalleryWorkspace.LIBRARY);
            else {
                setTerms("");
                submit(true);
            }
        });
        context.addView(home);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.gallery_primary)));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        status = text("", 14, getColor(R.color.gallery_muted));
        status.setPadding(dp(16), dp(6), dp(16), dp(7));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        page.addView(status);
        connect = GalleryUi.button(this, "Conectar " + SourceId.label(state.key), true);
        connect.setOnClickListener(view -> setup());
        row(page).addView(connect, new LinearLayout.LayoutParams(-1, dp(48)));

        grid = new GalleryGridView(this);
        grid.setNumColumns(columns());
        grid.setHorizontalSpacing(dp(5));
        grid.setVerticalSpacing(dp(5));
        grid.setPadding(dp(12), dp(5), dp(12), dp(12));
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
                queueNearbyLoad();
            }
        });
        LinearLayout footer = row(page);
        footer.setPadding(dp(16), dp(4), dp(16), dp(8));
        retry = GalleryUi.button(this, "Reintentar", false);
        retry.setOnClickListener(view -> {
            sparsePages = 0;
            fetch(retryFirst);
        });
        footer.addView(retry, new LinearLayout.LayoutParams(-1, dp(48)));
        workspace = new GalleryWorkspace(this, page, this::navigate);
        content(workspace.view());
        workspace.view().requestFocus();
        paintControls();
    }

    private LinearLayout row(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
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

    private int preference(String key, int max) {
        return Math.max(0, Math.min(max, state.preferences.getInt(key, 0)));
    }

    private int columns() {
        Configuration size = getResources().getConfiguration();
        int width = size.screenWidthDp - 24 - (GalleryWorkspace.usesRail(size) ? 100 : 0);
        int targetWidth = tileSize == 0 ? 150 : tileSize == 1 ? 110 : 82;
        return Math.max(2, Math.min(8, width / targetWidth));
    }

    private void showSavedSearches() {
        if (!ready() || !enabled) return;
        String current = WebTags.normalized(terms.getText().toString());
        WebSavedSearches.show(this, gelbooru && current.isEmpty() ? homeTags : current, saved -> {
            workspace.show(GalleryWorkspace.LIBRARY, null);
            setTerms(saved);
            submit(true);
        });
    }

    private void options() {
        GalleryUi.menu(this, "Opciones de biblioteca", new String[]{"Actualizar publicaciones", "Ajustes"},
                selected -> { if (selected == 0) submit(true); else showSettings(); });
    }

    private void showSettings() {
        GallerySettings.show(this, () -> navigate(GalleryWorkspace.SOURCES), rows -> {
            GalleryWorkspace.link(this, rows, "Tamaño de miniaturas", "Grandes, medianas o compactas", this::chooseTileSize);
            GalleryWorkspace.link(this, rows, "Encuadre de miniaturas", "Imagen completa o llenar la casilla", this::chooseThumbnailFit);
            if (gelbooru) GalleryWorkspace.link(this, rows, "Etiquetas de inicio", "Personalizar la biblioteca reciente", this::showHomeTags);
        }, () -> new SecureDialogs.Builder(this).setTitle("Protección local")
                .setMessage(gelbooru ? "La búsqueda y los medios de esta sesión se quedan en memoria. Las búsquedas que guardas se cifran en el dispositivo."
                        : "El catálogo y las miniaturas se guardan cifrados. El conector hace las peticiones a la fuente.")
                .setPositiveButton("Entendido", null).show());
    }

    private void chooseThumbnailFit() {
        android.app.AlertDialog dialog = new SecureDialogs.Builder(this).setTitle("Encuadre de miniaturas")
                .setSingleChoiceItems(new String[]{"Llenar la casilla", "Ver imagen completa"},
                        fitThumbnails ? 1 : 0, (picker, selected) -> {
                            picker.dismiss();
                            if (!ready() || fitThumbnails == (selected == 1)) return;
                            fitThumbnails = selected == 1;
                            persist();
                            if (adapter == null) return;
                            int first = grid.getFirstVisiblePosition();
                            int top = grid.getChildCount() == 0 ? 0 : grid.getChildAt(0).getTop();
                            adapter.setFitThumbnails(fitThumbnails);
                            if (grid.getChildCount() > 0) restoreGrid(first, top);
                        }).setNegativeButton("Cerrar", null).create();
        trackPrivateDialog(dialog);
        dialog.show();
    }

    private void chooseTileSize() {
        android.app.AlertDialog dialog = new SecureDialogs.Builder(this).setTitle("Tamaño de miniaturas")
                .setSingleChoiceItems(TILE_SIZES, tileSize, (picker, selected) -> {
                    picker.dismiss();
                    if (!ready() || selected == tileSize) return;
                    tileSize = selected;
                    persist();
                    resizeGrid();
                }).setNegativeButton("Cerrar", null).create();
        trackPrivateDialog(dialog);
        dialog.show();
    }

    private void resizeGrid() {
        int first = grid.getFirstVisiblePosition();
        int top = grid.getChildCount() == 0 ? 0 : grid.getChildAt(0).getTop();
        grid.setNumColumns(columns());
        if (grid.getCount() == 0 || workspace.showingOverlay()) return;
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                if (ready()) restoreGrid(first, top);
                return true;
            }
        });
    }

    private void showFilters() {
        if (!ready() || !enabled) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), 0, dp(24), dp(12));
        Spinner kind = MediaFilterDialog.choice(this, fields, "Tipo de archivo",
                new String[]{"Imágenes y videos", "Solo imágenes", "Solo videos"}, type);
        Spinner orientation = MediaFilterDialog.choice(this, fields, "Orientación",
                new String[]{"Cualquiera", "Horizontal", "Vertical", "Cuadrada"}, shape);
        Spinner seen = MediaFilterDialog.choice(this, fields, "Visualización",
                new String[]{"Cualquier estado", "Videos sin ver", "Videos vistos"}, watched);
        TextView error = text("", 14, getColor(R.color.gallery_error));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        fields.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        android.app.AlertDialog dialog = new SecureDialogs.Builder(this).setTitle("Filtros")
                .setView(scroll).setNegativeButton("Cancelar", null)
                .setNeutralButton("Restablecer", (d, which) -> applyFilters(0, 0, 0))
                .setPositiveButton("Aplicar", null).create();
        trackPrivateDialog(dialog);
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int selected = kind.getSelectedItemPosition();
            int viewStatus = seen.getSelectedItemPosition();
            if (selected == 1 && viewStatus != 0) {
                error.setText("Elige imágenes y videos o solo videos para filtrar por visualización.");
                error.setVisibility(View.VISIBLE);
                scroll.fullScroll(View.FOCUS_DOWN);
                return;
            }
            dialog.dismiss();
            applyFilters(selected, orientation.getSelectedItemPosition(), viewStatus);
        });
    }

    private void applyFilters(int type, int shape, int watched) {
        if (!ready() || !enabled || (this.type == type && this.shape == shape && this.watched == watched)) return;
        remember();
        this.type = type;
        this.shape = shape;
        this.watched = watched;
        sparsePages = 0;
        persist();
        if (request == null) render(-1);
        else paintControls();
    }

    private void notice(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
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
            String value = WebTags.normalized(tags.getText().toString());
            if (!WebTags.valid(value)) {
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

    private void navigate(String destination) {
        if (!GalleryWorkspace.usable(this)) return;
        remember();
        switch (destination) {
            case GalleryWorkspace.SOURCES:
                workspace.show(destination, GalleryConnections.page(this, key ->
                        UiSource.select(this, key, GalleryWorkspace.SOURCES, () -> { }),
                        () -> navigate(GalleryWorkspace.LIBRARY), this::setup, this::showSettings));
                return;
            case GalleryWorkspace.SAVED:
                workspace.show(destination, GalleryWorkspace.directory(this, "Guardado", SourceId.label(state.key), rows -> {
                    GalleryWorkspace.link(this, rows, "Búsquedas guardadas", "Combinaciones de etiquetas de esta fuente", this::showSavedSearches);
                    for (int i = 1; i < COLLECTIONS.length; i++) {
                        final int selection = i;
                        GalleryWorkspace.link(this, rows, COLLECTIONS[i], gelbooru ? "Disponible en esta sesión" : "", () -> {
                            if (!enabled) return;
                            collection = selection;
                            workspace.show(GalleryWorkspace.SAVED, null);
                            render(-1);
                        });
                    }
                }, this::showSettings));
                return;
            case GalleryWorkspace.DOWNLOADS:
                workspace.show(destination, GalleryWorkspace.directory(this, "Descargas", SourceId.label(state.key), rows -> {
                    TextView info = text("La fuente web está disponible en el visor. Las descargas sin conexión se gestionan por biblioteca.", 16,
                            getColor(R.color.gallery_muted));
                    info.setPadding(dp(12), dp(10), dp(12), dp(16));
                    rows.addView(info);
                    GalleryWorkspace.link(this, rows, "Abrir fuentes", "Elegir una biblioteca con descargas",
                            () -> navigate(GalleryWorkspace.SOURCES));
                }, this::showSettings));
                return;
            default:
                workspace.show(GalleryWorkspace.LIBRARY, null);
                if (collection != 0) {
                    collection = 0;
                    render(-1);
                } else queueNearbyLoad();
        }
    }

    private void applyPendingSection() {
        if (pendingSection != null) {
            String next = pendingSection;
            pendingSection = null;
            if (!GalleryWorkspace.LIBRARY.equals(next)) navigate(next);
        } else if (workspace.is(GalleryWorkspace.SOURCES) && workspace.showingOverlay()) navigate(GalleryWorkspace.SOURCES);
    }

    @Override boolean hasWorkspaceBack() {
        return workspace != null && (!workspace.is(GalleryWorkspace.LIBRARY) || UiSource.hasPrevious(this));
    }

    @Override boolean handleWorkspaceBack() {
        if (!GalleryWorkspace.usable(this)) return false;
        if (!workspace.is(GalleryWorkspace.LIBRARY)) {
            navigate(GalleryWorkspace.LIBRARY);
            return true;
        }
        return UiSource.back(this, () -> {});
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (workspace == null) return;
        for (String tab : new String[]{GalleryWorkspace.SOURCES, GalleryWorkspace.SAVED, GalleryWorkspace.DOWNLOADS}) {
            if (workspace.is(tab)) out.putString(GalleryWorkspace.SECTION, tab);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (workspace == null) return;
        workspace.resize();
        resizeGrid();
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
        handler.removeCallbacks(loadFollowingPage);
        rendering = false;
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
        boolean recheck = refreshEndpoint;
        checking = true;
        if (gelbooru && (existing == null || existing.database.isShutdown())) notice("Abriendo Gelbooru…");
        paintControls();
        state.database.execute(() -> {
            String setupMessage = "Conecta " + SourceId.label(state.key) + " para ver sus publicaciones.";
            boolean connected = false;
            State opened = existing != null && !existing.database.isShutdown() ? existing : null;
            try {
                if (recheck && existing == null) web.refreshEndpoint();
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
                refreshEndpoint = false;
                live = session;
                enabled = linked;
                if (!enabled) {
                    discardLive();
                    closeGrid();
                    notice(message);
                    paintControls();
                    applyPendingSection();
                    return;
                }
                if (pendingQuery != null) {
                    setTerms(pendingQuery);
                    pendingQuery = null;
                    submit(true);
                } else if (gelbooru && session != existing) {
                    collection = 0;
                    clearPagination();
                    fetch(true);
                } else render(-1);
                applyPendingSection();
            });
        });
    }

    private static String connectorMessage(Exception unavailable) {
        if (unavailable instanceof SecurityException) return "Instala Conector de fuentes con la misma firma en este perfil.";
        return WebSource.message(unavailable);
    }

    private void setup() {
        if (!ready()) return;
        try {
            Intent configuration = web.configure();
            refreshEndpoint = true;
            startActivityForResult(configuration, SETUP);
        } catch (Exception unavailable) {
            String message = connectorMessage(unavailable);
            if (workspace.showingOverlay()) {
                new SecureDialogs.Builder(this).setTitle("Configurar conexión").setMessage(message)
                        .setPositiveButton("Entendido", null).show();
            } else notice(message);
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == VIEWER && result == RESULT_OK && data != null) pendingQuery = data.getStringExtra("web-query");
    }

    private void submit(boolean committed) {
        handler.removeCallbacks(delayedSearch);
        if (!ready() || !enabled) return;
        String value = WebTags.normalized(terms.getText().toString());
        if (!WebTags.valid(value)) {
            notice("Usa de una a ocho etiquetas separadas por espacios.");
            return;
        }
        if (!committed && value.equals(query) && collection == 0) return;
        if (committed) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(terms.getWindowToken(), 0);
        boolean same = value.equals(query) && collection == 0;
        if (same && request != null) return;
        remember();
        cancelRequest();
        clearPagination();
        query = value;
        collection = 0;
        persist();
        if (!same) closeGrid();
        if (!gelbooru && query.isEmpty()) render(-1);
        else fetch(true);
    }

    private String queryKey() { return gelbooru && query.isEmpty() ? ":" : query; }

    private void cancelRequest() {
        if (request == null) return;
        request.cancel();
        request = null;
    }

    private void clearPagination() {
        revision++;
        rendering = false;
        sparsePages = 0;
        nextPage = -1;
        pageError = "";
        retryFirst = false;
    }

    private void queueNearbyLoad() {
        if (!handler.hasCallbacks(loadFollowingPage)) handler.post(loadFollowingPage);
    }

    private void loadNearEnd() {
        if (!ready() || workspace.showingOverlay() || checking || rendering || !enabled || request != null || collection != 0
                || adapter == null || nextPage < 0 || !pageError.isEmpty() || sparsePages >= WebCatalogue.PAGE_SCAN_LIMIT) return;
        int count = adapter.getCount();
        int visible = grid.getChildCount();
        if (count == 0 || (visible > 0 && count - grid.getFirstVisiblePosition() - visible <= 2 * columns())) {
            fetch(nextPage == 0);
        }
    }

    private void fetch(boolean first) {
        State origin = browsing();
        if (!ready() || checking || !enabled || origin == null || origin.database.isShutdown() || request != null) return;
        if ((!gelbooru && query.isEmpty()) || (!first && nextPage <= 0)) return;
        String filterKey = filter().key();
        int before = first || adapter == null ? -1 : adapter.getCount();
        pageError = "";
        retryFirst = first;
        CancellationSignal signal = new CancellationSignal();
        request = signal;
        String key = queryKey();
        int page = first ? 0 : nextPage;
        if (adapter == null || adapter.getCount() == 0) notice(query.isEmpty() && first ? "Cargando recientes…" : "Buscando publicaciones…");
        else notice("");
        paintControls();
        origin.database.execute(() -> {
            String problem = "";
            try {
                WebCatalogue.fetchPage(origin, key, page, signal);
            } catch (Exception failure) {
                problem = WebSource.message(failure);
            }
            String message = problem;
            runOnUiThread(() -> {
                if (request != signal) return;
                request = null;
                if (!ready() || origin != browsing()) return;
                pageError = message;
                render(message.isEmpty() && filterKey.equals(filter().key()) ? before : -1);
            });
        });
    }

    private MediaFilter filter() {
        MediaFilter result = new MediaFilter();
        result.type = type;
        result.shape = shape;
        result.watched = watched;
        result.collection = collection < 4 ? collection : 0;
        result.recent = collection == 4;
        if (result.recent) result.sort = 6;
        result.webQuery = collection == 0 ? queryKey() : null;
        return result;
    }

    private void render(int beforeAppend) {
        State origin = browsing();
        if (!ready() || !enabled || origin == null || origin.database.isShutdown()) return;
        int generation = ++revision;
        rendering = true;
        MediaFilter filter = filter();
        String key = filter.key();
        if (adapter != null && adapter.snapshot().key().equals(key)) remember();
        int columns = columns();
        origin.database.execute(() -> {
            try {
                int count = origin.library.count(filter);
                Library.Viewport stored = origin.library.viewport(filter);
                int next = filter.webQuery == null || filter.webQuery.isEmpty() ? -1 : origin.webCatalogue.next(filter.webQuery);
                int first = stored == null || count == 0 ? 0 : Math.min(stored.position, count - 1);
                int start = Math.max(0, first - columns);
                int pageStart = start - start % PagedAdapter.PAGE_SIZE;
                List<Item> window = count == 0 ? java.util.Collections.emptyList()
                        : origin.library.page(filter, pageStart, PagedAdapter.PAGE_SIZE);
                int following = pageStart + PagedAdapter.PAGE_SIZE;
                if (count > following && first + columns * 8 >= following) {
                    window.addAll(origin.library.page(filter, following, PagedAdapter.PAGE_SIZE));
                }
                runOnUiThread(() -> {
                    if (!ready() || revision != generation || origin != browsing()) return;
                    nextPage = next;
                    if (beforeAppend >= 0) sparsePages = count > beforeAppend ? 0 : sparsePages + 1;
                    if (adapter != null && adapter.snapshot().key().equals(key)) {
                        adapter.replaceWebWindow(count, pageStart, window);
                    } else {
                        closeGrid();
                        adapter = new PagedAdapter(this, origin, filter, count);
                        adapter.setFitThumbnails(fitThumbnails);
                        adapter.replaceWebWindow(count, pageStart, window);
                        grid.setAdapter(adapter);
                    }
                    if (stored != null && count > 0) restoreGrid(first, stored.top);
                    rendering = false;
                    if (filter.webQuery != null && !pageError.isEmpty()) notice(pageError);
                    else if (count > 0) notice("");
                    else if (!gelbooru && query.isEmpty() && collection == 0) notice("Busca por etiquetas para explorar.");
                    else if (next >= 0 && sparsePages < WebCatalogue.PAGE_SCAN_LIMIT) notice("Buscando publicaciones…");
                    else if (next > 0) notice("Aún no hay coincidencias en las páginas revisadas.");
                    else notice("No hay publicaciones en esta vista.");
                    paintControls();
                    queueNearbyLoad();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!ready() || generation != revision) return;
                    rendering = false;
                    pageError = WebSource.message(failure);
                    retryFirst = nextPage <= 0;
                    notice(pageError);
                    paintControls();
                });
            }
        });
    }

    private void restoreGrid(int position, int top) {
        int columns = Math.max(1, grid.getNumColumns());
        int tileHeight = (grid.getWidth() - grid.getPaddingStart() - grid.getPaddingEnd()) / columns;
        GalleryUi.restoreGrid(grid, position, Math.max(top, dp(12) - tileHeight));
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
            android.content.SharedPreferences.Editor edit = state.preferences.edit()
                    .putInt("web-filter-type", type).putInt("web-filter-shape", shape)
                    .putInt("web-filter-watched", watched).putInt("web-tile-size", tileSize)
                    .putBoolean("web-fit-thumbnails", fitThumbnails);
            if (!gelbooru) edit.putString("web-query", query);
            edit.apply();
        } catch (RuntimeException unavailable) { notice("No se pudieron guardar los ajustes."); }
    }

    private void paintControls() {
        boolean idle = request == null && !rendering;
        heading.setText(collection > 0 ? COLLECTIONS[collection]
                : query.isEmpty() ? gelbooru ? "Recientes" : "Biblioteca" : "Resultados");
        boolean available = enabled && !checking;
        search.setEnabled(available);
        savedSearches.setEnabled(available);
        clear.setEnabled(available);
        terms.setEnabled(available);
        ((View) connect.getParent()).setVisibility(enabled || checking ? View.GONE : View.VISIBLE);
        home.setVisibility(collection > 0 || !query.isEmpty() ? View.VISIBLE : View.GONE);
        int activeFilters = (type == 0 ? 0 : 1) + (shape == 0 ? 0 : 1) + (watched == 0 ? 0 : 1);
        filters.setEnabled(available);
        filters.setImageTintList(ColorStateList.valueOf(getColor(!available ? R.color.gallery_outline
                : activeFilters > 0 ? R.color.gallery_primary : R.color.gallery_foreground)));
        filters.setContentDescription(activeFilters == 0 ? "Filtros"
                : "Filtros, " + activeFilters + (activeFilters == 1 ? " activo" : " activos"));
        progress.setVisibility(checking || request != null ? View.VISIBLE : View.INVISIBLE);
        boolean failed = !pageError.isEmpty();
        boolean offer = available && idle && collection == 0
                && (failed || (nextPage > 0 && sparsePages >= WebCatalogue.PAGE_SCAN_LIMIT));
        retry.setText(failed ? "Reintentar" : "Seguir buscando");
        ((View) retry.getParent()).setVisibility(offer ? View.VISIBLE : View.GONE);
    }
}
