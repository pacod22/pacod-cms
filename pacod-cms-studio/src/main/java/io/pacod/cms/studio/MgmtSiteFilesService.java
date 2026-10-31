package io.pacod.cms.studio;

import io.pacod.cms.core.data.SiteFileKind;
import io.pacod.cms.core.data.SiteFileMode;
import io.pacod.cms.core.data.SiteFileSetting;
import io.pacod.cms.core.data.SiteFiles;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.SiteFilesRepository;
import io.pacod.cms.core.repository.SiteSettingsRepository;
import io.pacod.cms.core.services.sitefiles.SiteFileRules;
import io.pacod.cms.core.services.sitefiles.SiteFileService;
import io.pacod.cms.studio.access.StudioPrincipals;
import io.pacod.cms.studio.site.StudioSites;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /mgmt/site-files}: a website's crawler files ({@link SiteFiles}).
 *
 * <ul>
 *   <li><b>One file at a time.</b> A save {@code $set}s that file's setting and its own options
 *       alone, so saving robots.txt can never touch the sitemap -- not even one another tab saved a
 *       moment ago, which a load-modify-save of the whole document would put back.</li>
 *   <li><b>The custom text is kept whatever the mode</b>, so going back to custom restores it.</li>
 *   <li><b>The origin is the website's</b>: {@code baseUrl}, else its primary host's public address
 *       ({@link StudioSites}) -- never the studio's -- so the preview and "start from the generated
 *       file" are what the site serves.</li>
 *   <li>Every value goes through core's {@link SiteFileRules}; a refusal becomes an
 *       {@link MgmtOperationException} with the same key.</li>
 * </ul>
 */
@Slf4j
@Service
public class MgmtSiteFilesService {

    /** How much of a file the screen shows. */
    public static final int PREVIEW_LINES = 300;

    /**
     * What the screen shows of a file: its first {@link #PREVIEW_LINES} lines, how many there are
     * and how heavy it is; {@code off} when the site answers 404, {@code failed} when it could not
     * be generated just now.
     */
    public record Preview(String text, int lines, long bytes, boolean truncated, boolean off, boolean failed) {

        static Preview of(Optional<String> body) {
            if (body.isEmpty()) {
                return new Preview("", 0, 0, false, true, false);
            }
            String text = body.get();
            String[] all = text.split("\n", -1);
            int lines = text.endsWith("\n") ? all.length - 1 : all.length;
            boolean truncated = lines > PREVIEW_LINES;
            String shown = truncated ? String.join("\n", java.util.Arrays.copyOf(all, PREVIEW_LINES)) : text;
            return new Preview(shown, lines, text.getBytes(StandardCharsets.UTF_8).length, truncated, false, false);
        }

        static Preview unavailable() {
            return new Preview("", 0, 0, false, false, true);
        }
    }

    /** The values a file's form posts; options are applied only when {@code withOptions}. */
    public record Change(SiteFileMode mode, String additions, String exclusions, String custom,
                         boolean withOptions, boolean blockAiTraining, boolean blockAiAnswering, Integer articles) {
    }

    private final SiteFilesRepository repository;
    private final SiteSettingsRepository settings;
    private final SiteFileService files;
    private final StudioSites sites;
    private final MongoTemplate template;

    public MgmtSiteFilesService(SiteFilesRepository repository, SiteSettingsRepository settings, SiteFileService files,
                                StudioSites sites, MongoTemplate template) {
        this.repository = repository;
        this.settings = settings;
        this.files = files;
        this.sites = sites;
        this.template = template;
    }

    public SiteFiles load() {
        return repository.findById(SiteFiles.ID).orElseGet(SiteFiles::new);
    }

    /** The website's public origin, without a trailing slash; {@code ""} when it has neither base URL nor host. */
    public String origin() {
        String base = siteSettings().baseUrlOrEmpty();
        if (!base.isEmpty()) {
            return base;
        }
        return sites.current().map(StudioSites.SiteView::publicUrl).orElse("");
    }

    /** What the site answers for {@code kind} right now. */
    public Preview preview(SiteFileKind kind, SiteFiles stored) {
        try {
            String origin = origin();
            return Preview.of(files.render(kind, stored, origin, SiteFileService.ownHosts(siteSettings(), origin)));
        } catch (DataAccessException e) {
            log.error("Could not generate {} for the preview: {}", kind.path(), e.getMessage());
            return Preview.unavailable();
        }
    }

    /** Applies {@code change} to {@code kind}'s setting, and its options, alone. */
    public void save(SiteFileKind kind, Change change) {
        SiteFileMode mode = change.mode() == null ? SiteFileMode.AUTO : change.mode();
        if (!kind.modes().contains(mode)) {
            throw new MgmtOperationException("siteFiles.error.mode");
        }
        String origin = origin();
        // A field the request did not carry keeps what is stored: a form applies, it does not replace.
        SiteFileSetting setting = load().setting(kind);
        try {
            if (change.additions() != null) {
                setting.setAdditions(SiteFileRules.checkAdditions(kind, change.additions(),
                        SiteFileService.ownHosts(siteSettings(), origin)));
            }
            if (change.exclusions() != null) {
                setting.setExclusions(kind.excludes() ? SiteFileRules.checkExclusions(change.exclusions()) : null);
            }
            if (change.custom() != null) {
                setting.setCustom(kind.customisable() ? SiteFileRules.checkCustom(kind, change.custom()) : null);
            }
        } catch (SiteFileRules.Refused refused) {
            throw new MgmtOperationException(refused.code(), refused.args());
        }
        if (mode == SiteFileMode.CUSTOM && setting.getCustom() == null) {
            throw new MgmtOperationException("siteFiles.error.customEmpty");
        }
        setting.setMode(mode);
        Update update = stamped(setting);
        if (change.withOptions()) {
            switch (kind) {
                case ROBOTS -> update.set("blockAiTraining", change.blockAiTraining())
                        .set("blockAiAnswering", change.blockAiAnswering());
                case LLMS -> update.set("llmsArticles", articles(change.articles(), SiteFiles.MAX_LLMS_ARTICLES));
                case LLMS_FULL -> update.set("llmsFullArticles", articles(change.articles(), SiteFiles.MAX_LLMS_FULL_ARTICLES));
                case SITEMAP -> {
                    // No options of its own.
                }
            }
        }
        template.upsert(Query.query(Criteria.where("_id").is(SiteFiles.ID)), update.set(SiteFiles.field(kind), setting),
                SiteFiles.class);
    }

    /**
     * Copies the file as generated now into the custom text and makes it custom: the operator's
     * starting point. Refused for a file with no custom mode, without a public address (a sitemap
     * needs absolute ones), and when it is heavier than a custom text may be -- additions are then
     * the way.
     */
    public void customise(SiteFileKind kind) {
        if (!kind.customisable()) {
            throw new MgmtOperationException("siteFiles.error.notCustomisable");
        }
        String origin = origin();
        if (origin.isEmpty()) {
            throw new MgmtOperationException("siteFiles.error.noOrigin");
        }
        SiteFiles stored = load();
        String generated;
        try {
            generated = files.generate(kind, stored, origin, SiteFileService.ownHosts(siteSettings(), origin));
        } catch (DataAccessException e) {
            log.error("Could not generate {}: {}", kind.path(), e.getMessage());
            throw new MgmtOperationException("siteFiles.error.unavailable");
        }
        if (generated.getBytes(StandardCharsets.UTF_8).length > kind.customLimit()) {
            throw new MgmtOperationException("siteFiles.error.tooLargeToCustomise", (kind.customLimit() + 1023) / 1024);
        }
        SiteFileSetting setting = stored.setting(kind);
        setting.setCustom(generated);
        setting.setMode(SiteFileMode.CUSTOM);
        template.upsert(Query.query(Criteria.where("_id").is(SiteFiles.ID)),
                stamped(setting).set(SiteFiles.field(kind), setting), SiteFiles.class);
    }

    private static Update stamped(SiteFileSetting setting) {
        setting.setLastModifiedOn(LocalDateTime.now());
        setting.setUpdatedBy(StudioPrincipals.currentSubject());
        return new Update();
    }

    private static int articles(Integer value, int max) {
        if (value == null || value < 0 || value > max) {
            throw new MgmtOperationException("siteFiles.error.articles", max);
        }
        return value;
    }

    private SiteSettings siteSettings() {
        return settings.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
    }

    /** The hosts a sitemap addition may name, for the screen's hint. */
    public Set<String> ownHosts() {
        return SiteFileService.ownHosts(siteSettings(), origin());
    }
}
