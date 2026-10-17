package io.pacod.cms.core.design;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The design vocabulary a theme sets and every template set's stylesheet reads, as the custom
 * properties named here. One vocabulary for every set, so a theme survives a template switch.
 *
 * <p>Colours have a light and a dark value (stored as {@code <id>} and {@code <id>@dark}); fonts
 * are chosen from {@link #FONTS}, never typed -- a font name is CSS, and the studio is not where
 * CSS is typed; lengths are whole pixels within a range.
 */
public enum ThemeToken {

    BG("bg", Kind.COLOR, "--bg"),
    SURFACE("surface", Kind.COLOR, "--surface"),
    LINE("line", Kind.COLOR, "--line"),
    INK("ink", Kind.COLOR, "--ink"),
    MUTED("muted", Kind.COLOR, "--muted"),
    FAINT("faint", Kind.COLOR, "--faint"),
    ACCENT("accent", Kind.COLOR, "--accent"),
    HIGHLIGHT("highlight", Kind.COLOR, "--highlight"),
    TINT("tint", Kind.COLOR, "--tint"),
    HOVER("hover", Kind.COLOR, "--hover"),
    PANEL("panel", Kind.COLOR, "--panel"),
    FONT_DISPLAY("font-display", Kind.FONT, "--font-display"),
    FONT_TEXT("font-text", Kind.FONT, "--font-text"),
    FONT_LABEL("font-label", Kind.FONT, "--font-label"),
    SHELL("shell", Kind.LENGTH, "--shell", 720, 1600),
    GUTTER("gutter", Kind.LENGTH, "--gutter", 8, 64);

    public enum Kind { COLOR, FONT, LENGTH }

    /** The suffix of a colour's dark value: {@code accent@dark}. */
    public static final String DARK = "@dark";

    private static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    /** The fonts a theme may name, and the stacks they stand for. The first three are the editorial set's. */
    public static final Map<String, String> FONTS;

    static {
        Map<String, String> fonts = new LinkedHashMap<>();
        fonts.put("lora", "'Lora', Georgia, 'Times New Roman', serif");
        fonts.put("plex-sans", "'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', sans-serif");
        fonts.put("plex-mono", "'IBM Plex Mono', ui-monospace, 'SF Mono', Menlo, monospace");
        fonts.put("georgia", "Georgia, 'Times New Roman', serif");
        fonts.put("system-serif", "ui-serif, Georgia, 'Times New Roman', serif");
        fonts.put("system-sans", "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif");
        fonts.put("system-mono", "ui-monospace, 'SF Mono', Menlo, Consolas, monospace");
        FONTS = Map.copyOf(fonts);
    }

    private final String id;
    private final Kind kind;
    private final String cssVar;
    private final int min;
    private final int max;

    ThemeToken(String id, Kind kind, String cssVar) {
        this(id, kind, cssVar, 0, 0);
    }

    ThemeToken(String id, Kind kind, String cssVar, int min, int max) {
        this.id = id;
        this.kind = kind;
        this.cssVar = cssVar;
        this.min = min;
        this.max = max;
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public String cssVar() {
        return cssVar;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public static Optional<ThemeToken> byId(String id) {
        for (ThemeToken token : values()) {
            if (token.id.equals(id)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /** The value as stored, or empty when it is not one this token accepts. */
    public Optional<String> clean(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.strip();
        return switch (kind) {
            case COLOR -> COLOR.matcher(v).matches() ? Optional.of(v.toLowerCase(java.util.Locale.ROOT)) : Optional.empty();
            case FONT -> FONTS.containsKey(v) ? Optional.of(v) : Optional.empty();
            case LENGTH -> {
                try {
                    int n = Integer.parseInt(v.endsWith("px") ? v.substring(0, v.length() - 2) : v);
                    yield n >= min && n <= max ? Optional.of(Integer.toString(n)) : Optional.empty();
                } catch (NumberFormatException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    /** The CSS value of a clean stored value. */
    public String css(String stored) {
        return switch (kind) {
            case COLOR -> stored;
            case FONT -> FONTS.get(stored);
            case LENGTH -> stored + "px";
        };
    }
}
