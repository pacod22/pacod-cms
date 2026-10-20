package io.pacod.cms.studio.i18n;

import io.pacod.cms.core.exceptions.MediaException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The console's one resolver of user-facing text: templates ({@code ${mgmtText.label(x)}}), the
 * JavaScript bundle in the head fragment, flash messages and exception texts all go through here,
 * so a string cannot be translated in one place and hardcoded in another.
 *
 * <p>Reaches the templates as the {@code mgmtText} request attribute (see {@code MgmtLocaleConfig})
 * rather than published by {@code MgmtControllerAdvice}: that advice is scoped to the controller
 * package, and the sign-in and error pages must translate too.
 *
 * <p><b>The UI language is not the content language.</b> {@code lang} already means "which
 * translation of an article or page" in three places on this console and as the public site's
 * cookie, so the console's own language is the {@code mgmt-lang} cookie and the
 * {@code /mgmt/ui-language/{code}} endpoint -- see {@link MgmtLocaleResolver}.
 *
 * <p>Every message is run through {@link java.text.MessageFormat}
 * ({@code alwaysUseMessageFormat}), so an apostrophe is always written {@code ''} in the bundles,
 * whether or not the key takes arguments. One rule rather than two, and {@code MgmtBundleTest}
 * enforces it.
 */
@Component("mgmtText")
public class MgmtText {

    /** The bundle files, one per area of the console, under {@code src/main/resources/i18n/}. */
    public static final List<String> BASENAMES =
            List.of("i18n/common", "i18n/articles", "i18n/banners", "i18n/pages", "i18n/ops", "i18n/portal", "i18n/design", "i18n/jobs", "i18n/pdf");

    /** The languages the console is offered in. English first: it is the base bundle. */
    public static final List<UiLanguage> LANGUAGES = List.of(
            new UiLanguage("en", "EN", "English"),
            new UiLanguage("bg", "BG", "Български"),
            new UiLanguage("de", "DE", "Deutsch"),
            new UiLanguage("ru", "RU", "Русский"));

    /** The language of the base bundles, and the default until configuration says otherwise. */
    public static final String BASE_LANGUAGE = "en";

    private static volatile String defaultLanguage = BASE_LANGUAGE;

    /**
     * The UI language of an operator who has not chosen one: {@code pacod.studio.default-language},
     * set once at startup by {@code MgmtLocaleConfig}.
     */
    public static String defaultLanguage() {
        return defaultLanguage;
    }

    /** Sets {@link #defaultLanguage()}; a language the console is not offered in is refused. */
    public static void useDefaultLanguage(String code) {
        if (!isSupported(code)) {
            throw new IllegalArgumentException("The studio is not offered in " + code + "; one of "
                    + LANGUAGES.stream().map(UiLanguage::code).toList());
        }
        defaultLanguage = code;
    }

    /**
     * The base (English) bundles, readable without a Spring context. It is what an exception's
     * {@code getMessage()} says -- so logs read the same whatever language the operator chose.
     */
    private static final MessageSource BASE = newMessageSource();

    private final MessageSource messageSource;
    private final List<String> jsKeys;

    public MgmtText(MessageSource messageSource) {
        this.messageSource = messageSource;
        this.jsKeys = loadJsKeys();
    }

    /** The console's MessageSource. One definition, shared by the Spring bean and {@link #BASE}. */
    public static ResourceBundleMessageSource newMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(BASENAMES.toArray(String[]::new));
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return source;
    }

    public static boolean isSupported(String code) {
        return code != null && LANGUAGES.stream().anyMatch(l -> l.code().equals(code));
    }

    /**
     * Resolves {@code code} in the base bundles' language. A code with no entry is returned as
     * written, which is what lets a literal message and a key share one constructor.
     */
    public static String base(String code, Object... args) {
        return resolve(BASE, code, args, Locale.ROOT);
    }

    /**
     * A missing key comes back verbatim and is <i>not</i> run through MessageFormat: a literal
     * that is not a key may well contain a brace or an apostrophe, which would mangle or throw.
     */
    private static String resolve(MessageSource source, String code, Object[] args, Locale locale) {
        if (code == null) {
            return null;
        }
        String text = source.getMessage(code, args, null, locale);
        return text != null ? text : code;
    }

    // ------------------------------------------------------------------ instance API

    /** The current request's UI language code. */
    public String current() {
        Locale locale = LocaleContextHolder.getLocale();
        String code = locale == null ? null : locale.getLanguage();
        return isSupported(code) ? code : defaultLanguage;
    }

    public List<UiLanguage> languages() {
        return LANGUAGES;
    }

    /** The message for {@code code} in the current language; a missing key is returned as written. */
    public String msg(String code, Object... args) {
        return resolve(messageSource, code, args, LocaleContextHolder.getLocale());
    }

    /**
     * The text of an exception the console shows to the operator: its code and arguments resolved
     * in the current language when it carries them, else its message as written.
     */
    public String of(Throwable e) {
        if (e instanceof MgmtCodedMessage coded) {
            return msg(coded.code(), coded.args());
        }
        if (e instanceof MediaException media && media.getCode() != null) {
            String text = messageSource.getMessage(media.getCode(), media.getArgs(), null,
                    LocaleContextHolder.getLocale());
            return text != null ? text : media.getMessage();
        }
        return e.getMessage();
    }

    /**
     * A {@link MediaException} thrown by the console itself: keyed for the operator's language,
     * with the English text as the message every log line and test reads.
     */
    public static MediaException media(String code, Object... args) {
        return new MediaException(base(code, args), code, args);
    }

    /**
     * An enum constant's label: {@code enum.<SimpleName>.<NAME>} in the current language, falling
     * back to the constant's own {@code getLabel()} and then to its name.
     */
    public String label(Enum<?> value) {
        return text(value, "label");
    }

    /**
     * Any other text an enum constant carries -- a hint, a help text, a command preview: key
     * {@code enum.<SimpleName>.<NAME>.<attribute>}, falling back to its {@code get<Attribute>()}.
     * {@code "label"} is the one attribute whose key has no suffix.
     */
    public String text(Enum<?> value, String attribute) {
        if (value == null) {
            return "";
        }
        String key = enumKey(value, attribute);
        String text = messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
        return text != null ? text : rawText(value, attribute);
    }

    /** The bundle key of an enum constant's {@code attribute}; see {@link #text(Enum, String)}. */
    public static String enumKey(Enum<?> value, String attribute) {
        String key = "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name();
        return "label".equals(attribute) ? key : key + "." + attribute;
    }

    /** TinyMCE's language pack for the current language; empty for English, which needs none. */
    public String tinymceLanguage() {
        return switch (current()) {
            case "bg" -> "bg-BG";
            case "de" -> "de";
            case "ru" -> "ru";
            default -> "";
        };
    }

    /**
     * Every {@code js.*} message in the current language, as a JSON object for the
     * {@code <script type="application/json" id="mgmt-i18n">} in the head fragment. Written with
     * {@code th:utext}, so {@code <}, {@code >} and {@code &} are escaped as {@code <} and so
     * on: a value containing {@code </script>} must not be able to close the element.
     */
    public String jsBundle() {
        Locale locale = LocaleContextHolder.getLocale();
        StringBuilder json = new StringBuilder("{");
        for (String key : jsKeys) {
            if (json.length() > 1) {
                json.append(',');
            }
            String value = resolve(messageSource, key, null, locale);
            appendJsonString(json, key.substring("js.".length()));
            json.append(':');
            appendJsonString(json, value);
        }
        return json.append('}').toString();
    }

    // ------------------------------------------------------------------ helpers

    static String rawText(Enum<?> value, String attribute) {
        try {
            String getterName = "get" + Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
            Method getter = value.getDeclaringClass().getMethod(getterName);
            Object label = getter.invoke(value);
            if (label != null) {
                return label.toString();
            }
        } catch (ReflectiveOperationException e) {
            // no such getter: fall through to the name
        }
        return value.name();
    }

    static List<String> loadJsKeys() {
        Set<String> keys = new TreeSet<>();
        for (Properties properties : baseBundles()) {
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("js.")) {
                    keys.add(key);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }

    /** The base (English) file of every area, read as UTF-8. */
    public static List<Properties> baseBundles() {
        List<Properties> bundles = new ArrayList<>();
        for (String basename : BASENAMES) {
            bundles.add(load(basename + ".properties"));
        }
        return bundles;
    }

    public static Properties load(String path) {
        try {
            return PropertiesLoaderUtils.loadProperties(
                    new EncodedResource(new ClassPathResource(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read message bundle " + path, e);
        }
    }

    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** One entry of the switcher: the code, the short tag shown, and the language's own name. */
    public record UiLanguage(String code, String tag, String nativeName) {
    }
}
