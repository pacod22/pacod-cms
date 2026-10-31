package io.pacod.cms.studio.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * The AI features whose model an installation chooses ({@link AiModels}): each falls back to its
 * own property, {@code pacod.ai.models.<name>}, then to the default.
 */
public enum AiFeature {

    /** Translating articles and the rest of a website. */
    TRANSLATE,
    /** A PDF's abstract and keywords. */
    PDF,
    /** Writing help in the editor. */
    ASSIST,
    /** Alt text: the model looks at the picture. */
    VISION,
    /** A theme and layouts from a brief. */
    DESIGN;

    /** As stored and posted: {@code translate}, {@code vision} … */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String property() {
        return "pacod.ai.models." + key();
    }

    /** Whether the model must take images. */
    public boolean needsVision() {
        return this == VISION;
    }

    public static Optional<AiFeature> of(String key) {
        for (AiFeature f : values()) {
            if (f.key().equals(key)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
