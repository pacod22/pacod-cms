package io.pacod.cms.studio.design;

import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.design.Theme;
import io.pacod.cms.core.design.ThemeCss;
import io.pacod.cms.core.design.ThemeToken;
import io.pacod.cms.core.repository.ThemeRepository;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.access.StudioPrincipals;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A website's theme, as the studio edits it: the draft's token values and own CSS, the current
 * set's presets as starting points, publishing. Every value is checked by its token's type
 * ({@link ThemeToken#clean}); a value that fails is refused by name, not silently dropped.
 */
@Service
public class DesignTheme {

    private final ThemeRepository themes;
    private final DesignLayouts layouts;

    public DesignTheme(ThemeRepository themes, DesignLayouts layouts) {
        this.themes = themes;
        this.layouts = layouts;
    }

    public Theme load() {
        return themes.findById(Theme.ID).orElseGet(Theme::new);
    }

    public Map<String, TemplateSetManifest.ThemePreset> presets() {
        return layouts.currentSet().themes();
    }

    /**
     * The draft becomes exactly these values (blank: not set) and this CSS.
     *
     * @throws MgmtOperationException {@code design.theme.error.values} naming the refused tokens
     */
    public void saveDraft(Map<String, String> values, String css) {
        Map<String, String> clean = ThemeCss.clean(values);
        List<String> refused = new ArrayList<>();
        values.forEach((k, v) -> {
            if (v != null && !v.isBlank() && !clean.containsKey(k)) {
                refused.add(k);
            }
        });
        if (!refused.isEmpty()) {
            throw new MgmtOperationException("design.theme.error.values", String.join(", ", refused));
        }
        if (css != null && css.length() > ThemeCss.MAX_CSS) {
            throw new MgmtOperationException("design.theme.error.css", ThemeCss.MAX_CSS);
        }
        Theme theme = load();
        theme.setDraft(new LinkedHashMap<>(clean));
        theme.setDraftCss(css == null || css.isBlank() ? null : css);
        stamp(theme);
        themes.save(theme);
    }

    /** The preset's values replace the draft's; the draft's own CSS stays. */
    public void applyPreset(String presetId) {
        TemplateSetManifest.ThemePreset preset = presets().get(presetId);
        if (preset == null) {
            throw new MgmtOperationException("design.theme.error.preset", presetId);
        }
        Theme theme = load();
        theme.setDraft(new LinkedHashMap<>(preset.tokens()));
        theme.setPreset(presetId);
        stamp(theme);
        themes.save(theme);
    }

    public void publish() {
        Theme theme = load();
        theme.setPublished(new LinkedHashMap<>(theme.getDraft() == null ? Map.of() : theme.getDraft()));
        theme.setPublishedCss(theme.getDraftCss());
        theme.setPublishedOn(Instant.now());
        theme.setPublishedBy(StudioPrincipals.currentSubject());
        stamp(theme);
        themes.save(theme);
    }

    /** The draft goes back to what is published. */
    public void discard() {
        Theme theme = load();
        theme.setDraft(new LinkedHashMap<>(theme.getPublished() == null ? Map.of() : theme.getPublished()));
        theme.setDraftCss(theme.getPublishedCss());
        stamp(theme);
        themes.save(theme);
    }

    private static void stamp(Theme theme) {
        theme.setUpdatedOn(Instant.now());
        theme.setUpdatedBy(StudioPrincipals.currentSubject());
    }
}
