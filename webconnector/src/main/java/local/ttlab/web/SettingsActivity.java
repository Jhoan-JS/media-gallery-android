package local.ttlab.web;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        final WebSite site;
        try { site = WebSite.require(getIntent().getStringExtra("site")); }
        catch (IllegalArgumentException unknown) { finish(); return; }
        title.setText(site.label);
        title.setTextSize(26);
        title.setAccessibilityHeading(true);
        layout.addView(title);
        TextView notice = new TextView(this);
        String ratings = site == WebSite.GELBOORU
                ? "Se incluyen todas las clasificaciones que devuelve Gelbooru. "
                : "Solo aparecen publicaciones que el sitio clasifica como seguras. ";
        notice.setText("Fuente opcional para mayores de edad. " + ratings
                + "El sitio recibe tu IP, tus búsquedas y tus credenciales de API. La galería guarda su biblioteca web por separado.");
        layout.addView(notice);
        TextView status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);
        final WebSettings settings;
        try { settings = WebSettings.get(this, site); }
        catch (Exception unavailable) { status.setText("No se pudo abrir el almacén cifrado."); return; }
        WebSettings.Config current = settings.config();
        boolean authorized = false;
        try {
            String caller = getCallingPackage();
            if (caller != null) {
                WebSettings.requireCaller(this, caller, getPackageManager().getPackageUid(caller, 0));
                authorized = true;
            }
        } catch (Exception invalid) { /* Launcher can revoke but cannot grant gallery access. */ }
        if (authorized) {
            EditText user = field(layout, "ID de usuario", InputType.TYPE_CLASS_NUMBER);
            EditText key = field(layout, "Clave de API", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (current != null) user.setText(current.user);
            Button enable = button(layout, "Habilitar y autorizar");
            enable.setOnClickListener(view -> {
                try {
                    settings.enable(user.getText().toString().trim(), key.getText().toString().trim());
                    key.getText().clear();
                    setResult(RESULT_OK);
                    finish();
                } catch (IllegalArgumentException invalid) {
                    status.setText("Revisa las credenciales. Esta biblioteca se vincula a un solo ID de usuario.");
                } catch (Exception unavailable) { status.setText("No se pudo guardar la autorización."); }
            });
            if (current != null && current.enabled) {
                Button keep = button(layout, "Usar autorización guardada");
                keep.setOnClickListener(view -> { setResult(RESULT_OK); finish(); });
            }
        } else {
            status.setText(current != null && current.enabled
                    ? "Autorizado. Usa el selector de fuentes de la galería para buscar."
                    : "Fuente apagada. Selecciónala en la galería para configurarla.");
            WebSite other = site == WebSite.GELBOORU ? WebSite.RULE34 : WebSite.GELBOORU;
            button(layout, "Configurar " + other.label).setOnClickListener(view -> {
                startActivity(new android.content.Intent(this, SettingsActivity.class).putExtra("site", other.id));
                finish();
            });
        }
        Button disable = button(layout, "Revocar y apagar");
        disable.setOnClickListener(view -> {
            try { settings.disable(); setResult(RESULT_FIRST_USER); finish(); }
            catch (Exception unavailable) { status.setText("No se pudo guardar el cambio."); }
        });
    }

    @Override protected void onStop() {
        View focus = getCurrentFocus();
        if (focus instanceof EditText &&
                (((EditText) focus).getInputType() & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_PASSWORD) ((EditText) focus).getText().clear();
        super.onStop();
    }

    private EditText field(LinearLayout layout, String hint, int input) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(input);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setSaveEnabled(false);
        field.setFilterTouchesWhenObscured(true);
        layout.addView(field);
        return field;
    }

    private Button button(LinearLayout layout, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setFilterTouchesWhenObscured(true);
        layout.addView(button);
        return button;
    }
}
