package io.pacod.cms.core.design;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A theme as a stylesheet: its token values as custom properties on {@code :root} (and
 * {@code .night} for the dark values), then its own CSS. Served as an external {@code text/css}
 * file, never inline, so nothing in it can end a {@code <style>} element.
 *
 * <p>The own-CSS rules are defence in depth, not a security boundary: a website's owner already
 * runs script of their own on their own website. What is removed is what has no business in a
 * stylesheet at all.
 */
public final class ThemeCss {

    public static final int MAX_CSS = 50_000;

    private static final Pattern IMPORT = Pattern.compile("(?i)@import[^;]*;?");
    private static final Pattern DANGEROUS = Pattern.compile("(?i)expression\\s*\\(|behavior\\s*:|-moz-binding|javascript\\s*:|vbscript\\s*:|</");

    private ThemeCss() {
    }

    /** The token values this vocabulary accepts, cleaned; anything else dropped. */
    public static Map<String, String> clean(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (ThemeToken token : ThemeToken.values()) {
            token.clean(values.get(token.id())).ifPresent(v -> out.put(token.id(), v));
            if (token.kind() == ThemeToken.Kind.COLOR) {
                token.clean(values.get(token.id() + ThemeToken.DARK)).ifPresent(v -> out.put(token.id() + ThemeToken.DARK, v));
            }
        }
        return out;
    }

    /** CSS a website's owner wrote, without what a stylesheet never needs. */
    public static String sanitize(String css) {
        if (css == null || css.isBlank()) {
            return "";
        }
        String s = css.length() > MAX_CSS ? css.substring(0, MAX_CSS) : css;
        s = IMPORT.matcher(s).replaceAll("");
        s = DANGEROUS.matcher(s).replaceAll("/* removed */");
        return s.strip();
    }

    public static String render(Map<String, String> values, String css) {
        Map<String, String> clean = clean(values);
        StringBuilder light = new StringBuilder();
        StringBuilder dark = new StringBuilder();
        for (ThemeToken token : ThemeToken.values()) {
            String v = clean.get(token.id());
            if (v != null) {
                light.append("    ").append(token.cssVar()).append(": ").append(token.css(v)).append(";\n");
            }
            String d = clean.get(token.id() + ThemeToken.DARK);
            if (d != null) {
                dark.append("    ").append(token.cssVar()).append(": ").append(token.css(d)).append(";\n");
            }
        }
        StringBuilder out = new StringBuilder();
        if (!light.isEmpty()) {
            out.append(":root {\n").append(light).append("}\n");
        }
        if (!dark.isEmpty()) {
            out.append(".night {\n").append(dark).append("}\n");
        }
        String own = sanitize(css);
        if (!own.isEmpty()) {
            out.append("\n/* The website's own CSS */\n").append(own).append('\n');
        }
        return out.toString();
    }

    /** A short fingerprint of a stylesheet, for its cache-busting address. */
    public static String version(String css) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(css.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
