package local.ttlab.gallery;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Only user-named search terms are saved. Results belong to the source's catalogue. */
final class WebSavedSearches {
    private static final String KEY = "web-saved-searches";
    private static final int MAX_SEARCHES = 24;

    private static final class Entry {
        final String name, tags;

        Entry(String name, String tags) {
            this.name = name;
            this.tags = tags;
        }
    }

    static void show(SecureActivity activity, String current, Consumer<String> open) {
        SharedPreferences prefs = activity.state.preferences;
        List<Entry> searches = read(prefs);
        boolean canSave = !current.isEmpty() && WebTags.valid(current) && searches.size() < MAX_SEARCHES;
        AlertDialog.Builder builder = new SecureDialogs.Builder(activity).setTitle("Búsquedas guardadas")
                .setNegativeButton("Cerrar", null);
        if (searches.isEmpty()) {
            builder.setMessage(canSave ? "Guarda estas etiquetas para volver a buscarlas en un toque."
                    : "Escribe etiquetas para guardar tu primera búsqueda.");
        } else {
            builder.setItems(names(searches), (dialog, index) -> open.accept(searches.get(index).tags));
            builder.setNeutralButton("Quitar…", (dialog, which) -> remove(activity, prefs, searches));
        }
        if (canSave) builder.setPositiveButton("Guardar actual", (dialog, which) ->
                nameSearch(activity, prefs, searches, current));
        AlertDialog dialog = builder.create();
        activity.trackPrivateDialog(dialog);
        dialog.show();
    }

    private static void nameSearch(SecureActivity activity, SharedPreferences prefs,
                                   List<Entry> searches, String tags) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(activity.dp(24), activity.dp(4), activity.dp(24), activity.dp(8));
        EditText name = new EditText(activity);
        name.setSingleLine();
        name.setHint("Nombre de la búsqueda");
        name.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(48) });
        name.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        name.setSaveEnabled(false);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        body.addView(name);
        AlertDialog dialog = new SecureDialogs.Builder(activity).setTitle("Guardar búsqueda")
                .setView(body).setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create();
        activity.trackPrivateDialog(dialog);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (activity.state == null || !activity.state.unlocked) return;
            String label = name.getText().toString().trim();
            if (label.isEmpty() || label.chars().anyMatch(Character::isISOControl)) {
                name.setError("Escribe un nombre para reconocer esta búsqueda.");
                return;
            }
            for (Entry entry : searches) {
                if (entry.name.equalsIgnoreCase(label)) {
                    name.setError("Ya hay una búsqueda con ese nombre.");
                    return;
                }
            }
            List<Entry> updated = new ArrayList<>(searches);
            updated.add(0, new Entry(label, tags));
            if (!write(prefs, updated)) {
                name.setError("No se pudo guardar la búsqueda.");
                return;
            }
            dialog.dismiss();
            Toast.makeText(activity, "Búsqueda guardada", Toast.LENGTH_SHORT).show();
        });
    }

    private static void remove(SecureActivity activity, SharedPreferences prefs, List<Entry> searches) {
        GalleryUi.menu(activity, "Quitar búsqueda guardada", names(searches), index -> {
            Entry entry = searches.get(index);
            AlertDialog confirm = new SecureDialogs.Builder(activity).setTitle("¿Quitar esta búsqueda?")
                    .setMessage(entry.name).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Quitar", (dialog, which) -> {
                        List<Entry> updated = new ArrayList<>(searches);
                        updated.remove(index);
                        if (!write(prefs, updated)) {
                            Toast.makeText(activity, "No se pudo quitar la búsqueda.", Toast.LENGTH_SHORT).show();
                        }
                    }).create();
            activity.trackPrivateDialog(confirm);
            confirm.show();
        });
    }

    private static List<Entry> read(SharedPreferences prefs) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < Math.min(data.length(), MAX_SEARCHES); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                String name = row.optString("name").trim();
                String tags = WebTags.normalized(row.optString("tags"));
                if (!name.isEmpty() && name.length() <= 48 && !tags.isEmpty() && WebTags.valid(tags)) {
                    result.add(new Entry(name, tags));
                }
            }
        } catch (JSONException invalid) {
            // Builds without saved searches and malformed legacy data open with an empty list.
        }
        return result;
    }

    private static String[] names(List<Entry> entries) {
        return entries.stream().map(entry -> entry.name).toArray(String[]::new);
    }

    private static boolean write(SharedPreferences prefs, List<Entry> entries) {
        try {
            JSONArray data = new JSONArray();
            for (Entry entry : entries) {
                data.put(new JSONObject().put("name", entry.name).put("tags", entry.tags));
            }
            return prefs.edit().putString(KEY, data.toString()).commit();
        } catch (JSONException invalid) {
            return false;
        }
    }
}
