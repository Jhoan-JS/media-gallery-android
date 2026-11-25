package local.ttlab.gallery;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class WebTags {
    private static final Pattern TERM = Pattern.compile("-?[\\p{L}\\p{N}_().+'!?-]{1,80}");

    static String normalized(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    static boolean valid(String value) {
        if (value.isEmpty()) return true;
        if (value.length() > 320) return false;
        String[] words = value.split(" ");
        if (words.length > 8) return false;
        boolean positive = false;
        for (String word : words) {
            if (!TERM.matcher(word).matches() || word.equals("-")) return false;
            if (!word.startsWith("-")) positive = true;
        }
        return positive;
    }

    static String edit(String search, String tag, boolean exclude) {
        if (tag.isEmpty() || tag.startsWith("-") || !TERM.matcher(tag).matches()) return null;
        String current = normalized(search);
        if (!valid(current)) return null;
        Set<String> terms = new LinkedHashSet<>();
        for (String word : current.split(" ")) {
            if (!word.isEmpty() && !word.equalsIgnoreCase(tag) && !word.equalsIgnoreCase("-" + tag)) {
                terms.add(word);
            }
        }
        terms.add(exclude ? "-" + tag : tag);
        String result = String.join(" ", terms);
        return valid(result) ? result : null;
    }
}
