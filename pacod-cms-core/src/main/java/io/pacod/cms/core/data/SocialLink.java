package io.pacod.cms.core.data;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One link on {@code /social}, embedded in {@link SocialPage#getLinks()}.
 *
 * <h2>The id is generated, never typed, never reused</h2>
 * Eight characters of {@code [a-z0-9]}, starting with a letter, from {@link SocialPage#newLinkId}.
 * Three things depend on that:
 * <ul>
 *   <li>It is a <b>map key in the traffic buckets</b> -- {@code socialClicks.k3x9a2bq.INSTAGRAM} is
 *       a dotted {@code $inc} -- so it must contain no {@code .} and not start with {@code $}. A
 *       title or a URL could not be trusted to.</li>
 *   <li>It is <b>never reused</b>, which is what lets the statistics read a bucket without the
 *       {@code createdOn} guard the banner report needs: banner ids are max-plus-one, and a booking
 *       created today would otherwise inherit its deleted predecessor's history.</li>
 *   <li>It is in the URL readers click, {@code /social/go/{id}}, and a cached page or a
 *       screenshot of one keeps naming it after an edit. Changing it would break those links and
 *       sever the link from its own statistics.</li>
 * </ul>
 *
 * <h2>No click counter here</h2>
 * Clicks live in the traffic buckets only. A counter on this document would be overwritten by the
 * console's whole-document {@code save()} with whatever it read when the screen opened -- the trap
 * {@code BannerForm} keeps {@code impressions} and {@code clicks} out of itself to avoid. "Since the
 * start" is the sum of the day buckets, which never expire.
 */
@Data
public class SocialLink {

    private String id;

    private SocialLinkPlacement placement = SocialLinkPlacement.BUTTON;

    /** Required for {@link SocialLinkPlacement#ICON}; optional decoration on a button. */
    private SocialIcon icon;

    /**
     * Per-language button text. On an icon it is the accessible name, and the icon's own label
     * stands in when none is stored -- see {@link #label(List)}.
     */
    private List<TextItem> titles = new ArrayList<>();

    /** An absolute http(s) URL or a site-relative path; see {@code SocialTargets}. */
    private String url;

    private boolean active = true;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime activeFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime activeTo;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime createdOn;

    public SocialLink() {
    }

    public SocialLink(String id) {
        this.id = id;
        this.createdOn = LocalDateTime.now();
    }

    /**
     * Whether the public page shows it now. Evaluated per request, after the cache, so a scheduled
     * link appears and disappears on time rather than up to a cache TTL late -- the reason
     * {@code BannerService} filters the same way.
     */
    public boolean isLive(LocalDateTime now) {
        return active && withinWindow(now);
    }

    public boolean withinWindow(LocalDateTime now) {
        if (activeFrom != null && now.isBefore(activeFrom)) {
            return false;
        }
        return activeTo == null || !now.isAfter(activeTo);
    }

    /** The title in the first language of {@code chain} that has one. No getter: it needs a chain. */
    public String title(List<String> chain) {
        return TextItems.resolve(titles, chain);
    }

    /** Which language {@link #title(List)} answered in -- the {@code lang} attribute when it differs. */
    public String titleLang(List<String> chain) {
        return TextItems.resolvedLang(titles, chain);
    }

    /** The title stored for exactly {@code lang}, or {@code null}. Spelled apart from {@link #title}; see {@code Page}. */
    public String rawTitle(String lang) {
        return TextItems.find(titles, lang);
    }

    public void putTitle(String lang, String text) {
        titles = TextItems.put(titles, lang, text);
    }

    /** What to call it on screen: the resolved title, else the icon's label, else the URL. */
    public String label(List<String> chain) {
        String title = title(chain);
        if (title != null) {
            return title;
        }
        return icon != null ? icon.getLabel() : url;
    }
}
