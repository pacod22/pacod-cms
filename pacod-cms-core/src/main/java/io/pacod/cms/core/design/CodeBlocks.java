package io.pacod.cms.core.design;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The classes a code sample in an article body may carry: {@code language-…}, the name of the
 * language it is written in, on its {@code <pre>} or {@code <code>}. The site's syntax highlighter
 * (Prism, loaded only on a page whose body holds a {@code <pre>}) colours a block by that name, and
 * the studio's editor writes it: TinyMCE's {@code codesample} plugin stores
 * {@code <pre class="language-java"><code>…</code></pre>}, the class on the {@code pre} alone.
 *
 * <p>A pattern rather than a list like {@link BodyBlocks}: the name is data for the highlighter,
 * never a style of the site's, and a language it does not know is shown as plain text. The
 * pattern is narrow all the same, so that a class from an AI answer or an import can only ever
 * name a language: lowercase letters, digits and {@code + # -}, at most 24 of them.
 */
public final class CodeBlocks {

    /** {@code language-java}, {@code language-c++}, {@code language-c#}, {@code language-objective-c}. */
    public static final Pattern LANGUAGE_CLASS = Pattern.compile("language-[a-z0-9+#-]{1,24}");

    /** The elements that may carry a language class. */
    public static final Set<String> ELEMENTS = Set.of("pre", "code");

    private CodeBlocks() {
    }

    /** Whether {@code cssClass} is one class name that names a code sample's language. */
    public static boolean isLanguageClass(String cssClass) {
        return cssClass != null && LANGUAGE_CLASS.matcher(cssClass).matches();
    }

    /** Whether {@code cssClass} is a language class and {@code element} may carry it. */
    public static boolean allowed(String element, String cssClass) {
        return ELEMENTS.contains(element) && isLanguageClass(cssClass);
    }
}
