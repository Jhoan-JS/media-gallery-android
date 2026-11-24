package local.ttlab.gallery;

import android.database.Cursor;

final class WebCatalogue {
    private final Library library;

    WebCatalogue(Library library) {
        this.library = library;
        library.db.execSQL("CREATE TABLE IF NOT EXISTS web_queries(query TEXT PRIMARY KEY,next_page INTEGER NOT NULL,updated INTEGER NOT NULL)");
        library.db.execSQL("CREATE TABLE IF NOT EXISTS web_results(query TEXT NOT NULL,id INTEGER NOT NULL,PRIMARY KEY(query,id))");
        library.db.execSQL("CREATE TABLE IF NOT EXISTS web_tags(id INTEGER PRIMARY KEY,tags TEXT NOT NULL)");
    }

    int next(String query) {
        try (Cursor row = library.db.rawQuery("SELECT next_page FROM web_queries WHERE query=?", new String[]{query})) {
            return row.moveToFirst() ? row.getInt(0) : 0;
        }
    }

    void store(String query, int page, WebSource.Result result, boolean live) {
        library.db.beginTransaction();
        try {
            if (page == 0) {
                if (live) {
                    library.db.execSQL("DELETE FROM web_results");
                    library.db.execSQL("DELETE FROM web_queries");
                } else {
                    library.db.execSQL("DELETE FROM web_results WHERE query=?", new Object[]{query});
                }
            }
            library.rows(result.posts);
            for (int n = 0; n < result.posts.size(); n++) {
                Item item = result.posts.get(n);
                library.db.execSQL("INSERT OR IGNORE INTO web_results(query,id) VALUES(?,?)", new Object[]{query,item.id});
                library.db.execSQL("INSERT OR REPLACE INTO web_tags(id,tags) VALUES(?,?)", new Object[]{item.id,result.tags.get(n)});
            }
            library.db.execSQL("INSERT OR REPLACE INTO web_queries(query,next_page,updated) VALUES(?,?,?)",
                    new Object[]{query,result.next,System.currentTimeMillis()});
            library.db.execSQL("DELETE FROM web_queries WHERE query NOT IN(SELECT query FROM web_queries ORDER BY updated DESC LIMIT 16)");
            library.db.execSQL("DELETE FROM web_results WHERE query NOT IN(SELECT query FROM web_queries)");
            library.db.execSQL("DELETE FROM media WHERE id NOT IN(SELECT id FROM web_results) "
                    + "AND NOT EXISTS(SELECT 1 FROM media_state s WHERE s.channel=media.channel AND s.id=media.id) "
                    + "AND NOT EXISTS(SELECT 1 FROM recent_media r WHERE r.channel=media.channel AND r.id=media.id)");
            library.db.execSQL("DELETE FROM web_tags WHERE id NOT IN(SELECT id FROM media)");
            library.db.setTransactionSuccessful();
        } finally { library.db.endTransaction(); }
    }

    String tags(int id) {
        try (Cursor row = library.db.rawQuery("SELECT tags FROM web_tags WHERE id=?", new String[]{Integer.toString(id)})) {
            return row.moveToFirst() ? row.getString(0) : "";
        }
    }
}
