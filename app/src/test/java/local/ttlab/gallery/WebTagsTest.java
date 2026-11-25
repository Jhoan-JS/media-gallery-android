package local.ttlab.gallery;

import org.junit.Test;

import static org.junit.Assert.*;

public final class WebTagsTest {
    @Test public void replacesContradictoryTermsAndKeepsTheRest() {
        assertEquals("sky -cloud", WebTags.edit("sky cloud", "cloud", true));
        assertEquals("sky CLOUD", WebTags.edit("sky -cloud", "CLOUD", false));
        assertEquals("sky cloud", WebTags.edit("sky cloud cloud", "cloud", false));
    }

    @Test public void forbidsUnsupportedOrOnlyNegativeSearches() {
        assertNull(WebTags.edit("", "cloud", true));
        assertNull(WebTags.edit("sky", "sky", true));
        assertNull(WebTags.edit("sky", "rating:safe", false));
        assertNull(WebTags.edit("", "two words", false));
        assertNull(WebTags.edit("one two three four five six seven eight", "nine", false));
        assertFalse(WebTags.valid(String.join(" ", java.util.Collections.nCopies(5, "a".repeat(72)))));
        assertTrue(WebTags.valid("sky -cloud"));
        assertTrue(WebTags.valid(""));
    }
}
