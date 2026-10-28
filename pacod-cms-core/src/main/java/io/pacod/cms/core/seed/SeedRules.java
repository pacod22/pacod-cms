package io.pacod.cms.core.seed;

import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerPlacement;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.data.SocialLinkPlacement;
import io.pacod.cms.core.design.BodyBlocks;
import io.pacod.cms.core.design.LayoutResolver;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.design.SectionType;
import io.pacod.cms.core.design.TemplateSetManifest;
import io.pacod.cms.core.design.ThemeCss;
import io.pacod.cms.core.util.HttpUrls;
import io.pacod.cms.core.util.SocialTargets;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a seed from outside the jar must be before {@link DemoContent} writes any of it: every
 * reference resolves, every file it names is there and readable, every text is whole, and nothing
 * in it is something the studio's own screens would refuse -- a layout the set cannot render, a
 * booking on a page that cannot be booked, a theme value the editor would drop.
 *
 * <p>The rules every seed keeps, shipped or not. A shipped demo is held to more, and stricter, by
 * {@code SeedIntegrityTest} -- English alone, no outside address in a body, recent dates, every
 * photograph used -- which are the demo's own choices, not a website's: a product site's pages link
 * to their repositories and its articles keep their real dates.
 *
 * <p>Checked whole, before anything is written, so the answer is every problem at once and a seed
 * is never half-installed for a reason that was knowable in advance. The bookings especially: the
 * studio writes them after the articles, where a failure is only logged.
 */
public final class SeedRules {

    /** An id a picture, an advertiser or a creative's file may have. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern PARTNER_ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern CREATIVE = Pattern.compile("[a-z0-9-]+\\.html");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

    private static final Pattern SCRIPT = Pattern.compile("<script|javascript:|<iframe|\\son[a-z]+\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASSES = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)[^>]*?\\sclass=\"([^\"]*)\"");
    private static final Pattern SRC = Pattern.compile("\\ssrc=\"([^\"]*)\"");
    private static final Pattern SITE_ADDRESS = Pattern.compile("^/(article|list|p)/([^/?#]+)$");

    private static final Set<String> FAVICONS = Set.of(".svg", ".png");
    private static final Set<String> PICTURES = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

    private SeedRules() {
    }

    /**
     * Every problem the seed has, for a website whose content language is {@code language}; empty
     * when it may be written.
     *
     * @param set      the template set its layouts are for, or null when the installation has none of that id
     * @param standing the slugs of the standing pages every website starts with
     * @param now      what "in the future" is measured from
     */
    public static List<String> check(DemoContent.Seed seed, SeedSource source, String language, TemplateSetManifest set,
                                     Set<String> standing, LocalDateTime now) {
        Checker checker = new Checker(seed, source, standing, now);
        checker.languages(language);
        checker.ids();
        checker.pictures();
        checker.articles();
        checker.pages();
        checker.menus();
        checker.social();
        checker.layouts(set);
        checker.theme();
        checker.issues();
        checker.partners();
        checker.brand();
        checker.site();
        return List.copyOf(checker.problems);
    }

    private static final class Checker {

        private final DemoContent.Seed seed;
        private final SeedSource source;
        private final Set<String> standing;
        private final LocalDateTime now;
        private final List<String> problems = new ArrayList<>();

        private final Set<Integer> categories;
        private final Set<Integer> authors;
        private final Set<Integer> articles;
        private final Set<String> pictures;
        private final Set<String> pages;

        Checker(DemoContent.Seed seed, SeedSource source, Set<String> standing, LocalDateTime now) {
            this.seed = seed;
            this.source = source;
            this.standing = standing;
            this.now = now;
            this.categories = seed.getCategories().stream().map(DemoContent.CategorySeed::getId).collect(Collectors.toSet());
            this.authors = seed.getAuthors().stream().map(DemoContent.AuthorSeed::getId).collect(Collectors.toSet());
            this.articles = seed.getArticles().stream().map(DemoContent.ArticleSeed::getId).collect(Collectors.toSet());
            this.pictures = seed.getImages().stream().map(DemoContent.ImageSeed::getKey).collect(Collectors.toSet());
            this.pages = new HashSet<>(standing);
            seed.getPages().forEach(page -> pages.add(page.getSlug()));
        }

        private void problem(String format, Object... args) {
            problems.add(String.format(Locale.ROOT, format, args));
        }

        void languages(String language) {
            Set<String> complete = DemoContent.completeLanguages(seed);
            if (!complete.contains(language)) {
                problem("the seed is complete in %s, not in the website's language %s", complete, language);
            }
            Set<String> codes = new HashSet<>();
            for (io.pacod.cms.core.data.Language row : seed.getLanguages()) {
                if (row.getCode() == null || !LANGUAGE.matcher(row.getCode()).matches()) {
                    problem("language %s: a code is two or three lowercase letters", row.getCode());
                } else if (!codes.add(row.getCode())) {
                    problem("language %s is named twice", row.getCode());
                }
                if (row.getAdminName() == null || row.getAdminName().isBlank()) {
                    problem("language %s has no name", row.getCode());
                }
            }
        }

        void ids() {
            duplicates("category", seed.getCategories().stream().map(DemoContent.CategorySeed::getId).toList());
            duplicates("author", seed.getAuthors().stream().map(DemoContent.AuthorSeed::getId).toList());
            duplicates("article", seed.getArticles().stream().map(DemoContent.ArticleSeed::getId).toList());
            duplicates("picture", seed.getImages().stream().map(DemoContent.ImageSeed::getKey).toList());
            duplicates("page", seed.getPages().stream().map(DemoContent.PageSeed::getSlug).toList());
            duplicates("advertiser", seed.getPartners().stream().map(DemoContent.PartnerSeed::getId).toList());
            seed.getArticles().forEach(a -> {
                if (a.getId() != null && a.getId() <= 0) {
                    problem("article %d: an id is positive", a.getId());
                }
            });
            if (seed.getImagePrefix() == null || !seed.getImagePrefix().matches("[a-z0-9-]{0,24}")) {
                problem("imagePrefix %s: lowercase letters, digits and hyphens only", seed.getImagePrefix());
            }
        }

        private <T> void duplicates(String what, List<T> ids) {
            Set<T> seen = new HashSet<>();
            for (T id : ids) {
                if (id == null) {
                    problem("%s without an id", what);
                } else if (!seen.add(id)) {
                    problem("%s %s is there twice", what, id);
                }
            }
        }

        void pictures() {
            for (DemoContent.ImageSeed image : seed.getImages()) {
                String as = "picture " + image.getKey();
                if (image.getKey() != null && !KEY.matcher(image.getKey()).matches()) {
                    problem("%s: a key is lowercase letters, digits and hyphens", as);
                }
                if (image.getPhoto() == null) {
                    try {
                        DemoImages.Motif.of(image.getMotif());
                    } catch (RuntimeException e) {
                        problem("%s: neither a photo nor a motif (%s)", as, e.getMessage());
                    }
                    if (image.getColours().size() < 2 || !image.getColours().stream().allMatch(c -> COLOUR.matcher(c).matches())) {
                        problem("%s: a drawn picture needs two or more #rrggbb colours", as);
                    }
                    continue;
                }
                if (!DemoContent.ImageSeed.CROP.equals(image.getFit()) && !DemoContent.ImageSeed.PAD.equals(image.getFit())) {
                    problem("%s: fit is %s or %s, not %s", as, DemoContent.ImageSeed.CROP, DemoContent.ImageSeed.PAD, image.getFit());
                }
                if (image.getBackground() == null || !COLOUR.matcher(image.getBackground()).matches()) {
                    problem("%s: background %s is not #rrggbb", as, image.getBackground());
                }
                if (image.getFocusX() < 0 || image.getFocusX() > 1 || image.getFocusY() < 0 || image.getFocusY() > 1) {
                    problem("%s: a focus is between 0 and 1", as);
                }
                decodes(as, image.getPhoto());
            }
        }

        /** Whether a picture of the source is there and is an image Java can read. */
        private void decodes(String as, String path) {
            try {
                if (ImageIO.read(new ByteArrayInputStream(source.read(path))) == null) {
                    problem("%s: %s is not a picture this installation can read", as, path);
                }
            } catch (IllegalStateException | IOException e) {
                problem("%s: %s", as, e.getMessage());
            }
        }

        void articles() {
            for (DemoContent.ArticleSeed article : seed.getArticles()) {
                String as = "article " + article.getId();
                if (article.getCategories().isEmpty() || !categories.containsAll(article.getCategories())) {
                    problem("%s: its sections %s are not all in the seed", as, article.getCategories());
                }
                if (article.getAuthors().isEmpty() || !authors.containsAll(article.getAuthors())) {
                    problem("%s: its authors %s are not all in the seed", as, article.getAuthors());
                }
                // The cards skip an article without a picture: on the home page it would not be there.
                if (article.getImages().isEmpty() || !pictures.containsAll(article.getImages())) {
                    problem("%s: it needs pictures the seed has, and has %s", as, article.getImages());
                } else if (!article.getImages().containsAll(article.getInline())
                        || article.getInline().contains(article.getImages().getFirst())) {
                    problem("%s: a picture inside the body is one of its own, and never its lead", as);
                }
                Set<String> languages = article.getTitles().keySet();
                if (!article.getDescriptions().keySet().equals(languages) || !article.getBodies().keySet().equals(languages)) {
                    problem("%s: a translation has its title, description and body, never one alone", as);
                }
                if (article.getDate() == null && (article.getDaysAgo() < 0 || article.getHour() < 0 || article.getHour() > 23)) {
                    problem("%s: daysAgo is zero or more and hour between 0 and 23", as);
                }
                ArticleStatus status = article.getStatus() == null ? ArticleStatus.PUBLISHED : article.getStatus();
                if (status == ArticleStatus.PUBLISHED && DemoContent.dated(article, now).isAfter(now)) {
                    problem("%s: a published article is not dated in the future", as);
                }
                if (article.getModified() != null && article.getModified().isBefore(DemoContent.dated(article, now))) {
                    problem("%s: modified before its date", as);
                }
                article.getBodies().forEach((code, html) -> body(as + " in " + code, html, article.getInline()));
            }
            // The latest list and the cover sort by id, as the studio's max-plus-one ids do.
            List<DemoContent.ArticleSeed> byId = seed.getArticles().stream().filter(a -> a.getId() != null)
                    .sorted(Comparator.comparing(DemoContent.ArticleSeed::getId)).toList();
            for (int i = 1; i < byId.size(); i++) {
                if (DemoContent.dated(byId.get(i), now).isBefore(DemoContent.dated(byId.get(i - 1), now))) {
                    problem("article %d is older than article %d: a newer article has a higher id",
                            byId.get(i).getId(), byId.get(i - 1).getId());
                }
            }
        }

        /**
         * A body is the editor's markup: no script, handler or frame; a class only one of the body's
         * styled blocks or a code block's language; a picture only one of the seed's, by its stored
         * name -- one of {@code inline} where the body is an article's.
         */
        private void body(String as, String html, List<String> inline) {
            if (html == null || html.isBlank()) {
                problem("%s: the text is empty", as);
                return;
            }
            if (SCRIPT.matcher(html).find()) {
                problem("%s: no script, frame or event handler", as);
            }
            Matcher classes = CLASSES.matcher(html);
            while (classes.find()) {
                String element = classes.group(1).toLowerCase(Locale.ROOT);
                for (String name : classes.group(2).trim().split("\\s+")) {
                    boolean code = io.pacod.cms.core.design.CodeBlocks.allowed(element, name);
                    if (!name.isEmpty() && !code && BodyBlocks.byClass(name).isEmpty()) {
                        problem("%s: the class %s is neither a body block nor a code block's language", as, name);
                    }
                }
            }
            String stored = "/assets/img/articles/" + seed.getImagePrefix();
            Matcher sources = SRC.matcher(html);
            while (sources.find()) {
                String src = sources.group(1);
                String key = src.startsWith(stored) && src.endsWith(".jpg")
                        ? src.substring(stored.length(), src.length() - ".jpg".length()) : null;
                if (key == null || !pictures.contains(key)) {
                    problem("%s: %s is not one of the seed's pictures (%s<key>.jpg)", as, src, stored);
                } else if (inline != null && !inline.contains(key)) {
                    problem("%s: the picture %s is placed in the body but not listed in inline", as, key);
                }
            }
        }

        void pages() {
            for (DemoContent.PageSeed page : seed.getPages()) {
                String as = "page " + page.getSlug();
                if (page.getSlug() == null || !KEY.matcher(page.getSlug()).matches()) {
                    problem("%s: a slug is lowercase letters, digits and hyphens", as);
                }
                page.getBodies().forEach((code, html) -> body(as + " in " + code, html, null));
            }
        }

        void menus() {
            seed.getMenus().forEach((key, items) -> items.forEach(item -> menuItem("menu " + key, item)));
        }

        private void menuItem(String as, SiteBootstrap.ItemSeed item) {
            if (item.getKind() == null) {
                problem("%s: an item has no kind", as);
                return;
            }
            String target = item.getTarget();
            switch (item.getKind()) {
                case CATEGORY -> {
                    if (target == null || !target.matches("\\d+") || !categories.contains(Integer.valueOf(target))) {
                        problem("%s: no section %s in the seed", as, target);
                    }
                }
                case PAGE -> {
                    if (!pages.contains(target)) {
                        problem("%s: no page %s, standing or the seed's", as, target);
                    }
                }
                case URL -> address(as, target);
                default -> { }
            }
            item.getChildren().forEach(child -> menuItem(as, child));
        }

        void social() {
            DemoContent.SocialSeed social = seed.getSocial();
            for (DemoContent.LinkSeed link : social.getLinks()) {
                String as = "link page " + link.getUrl();
                if (!SocialTargets.isValidTarget(link.getUrl())) {
                    problem("%s: not an address the link page takes", as);
                }
                if (link.getPlacement() == SocialLinkPlacement.ICON && link.getIcon() == null) {
                    problem("%s: an icon link needs its icon", as);
                }
                address(as, link.getUrl());
            }
            if (social.getLatestCount() < 1 || social.getLatestCount() > 10) {
                problem("link page: latestCount is between 1 and 10");
            }
        }

        /** A site address the seed writes names what the seed has; an outside one is http(s). */
        private void address(String as, String url) {
            if (url == null || url.isBlank()) {
                problem("%s: no address", as);
                return;
            }
            if (!url.startsWith("/")) {
                if (!HttpUrls.isHttpUrl(url) && !url.startsWith("mailto:")) {
                    problem("%s: %s is neither a site address nor http(s)", as, url);
                }
                return;
            }
            Matcher match = SITE_ADDRESS.matcher(url);
            if (!match.matches()) {
                if (!Set.of("/", "/latest", "/issues", "/search", "/social").contains(url)) {
                    problem("%s: %s is not an address the site serves", as, url);
                }
                return;
            }
            String reference = match.group(2);
            boolean found = switch (match.group(1)) {
                case "article" -> reference.matches("\\d+") && articles.contains(Integer.valueOf(reference));
                case "list" -> reference.matches("\\d+") && categories.contains(Integer.valueOf(reference));
                default -> pages.contains(reference);
            };
            if (!found) {
                problem("%s: %s names nothing the seed has", as, url);
            }
        }

        void layouts(TemplateSetManifest set) {
            if (seed.getLayouts().isEmpty()) {
                return;
            }
            if (seed.getLayoutSet() == null || set == null) {
                problem("layouts: they are for the template set %s, which this installation does not have", seed.getLayoutSet());
                return;
            }
            seed.getLayouts().forEach((pageType, regions) -> {
                String as = "layout " + pageType;
                PageType type;
                try {
                    type = PageType.valueOf(pageType);
                } catch (IllegalArgumentException e) {
                    problem("%s: no such page type", as);
                    return;
                }
                try {
                    List<LayoutResolver.Issue> issues = LayoutResolver.resolve(set, type, DemoContent.toRegions(regions)).issues();
                    if (!issues.isEmpty()) {
                        problem("%s: the %s set cannot render it: %s", as, set.id(), issues);
                    }
                } catch (IllegalStateException e) {
                    problem("%s: %s", as, e.getMessage());
                }
                regions.values().forEach(sections -> sections.forEach(section -> {
                    Map<String, Object> settings = section.getSettings();
                    if (settings.get("category") instanceof Integer category && !categories.contains(category)) {
                        problem("%s: no section %d in the seed", as, category);
                    }
                    // The site shows nothing for a page that is not there, and says nothing.
                    if (SectionType.PAGE.id().equals(section.getType()) && settings.get("page") instanceof String slug
                            && !slug.isBlank() && !pages.contains(slug.strip())) {
                        problem("%s: no page %s, standing or the seed's", as, slug);
                    }
                    if (settings.get("url") instanceof String url) {
                        address(as, url);
                    }
                    if (settings.get("html") instanceof String html) {
                        body(as, html, null);
                    }
                }));
            });
        }

        void theme() {
            Map<String, String> theme = seed.getTheme();
            if (theme != null && !ThemeCss.clean(theme).equals(theme)) {
                Set<String> dropped = new HashSet<>(theme.keySet());
                dropped.removeAll(ThemeCss.clean(theme).keySet());
                problem("theme: the theme editor would drop %s", dropped.isEmpty() ? "some of its values" : dropped);
            }
        }

        void issues() {
            for (DemoContent.IssueSeed issue : seed.getIssues()) {
                String as = "issue " + issue.getVolume() + "/" + issue.getNumber();
                if (issue.getArticles().isEmpty() || !articles.containsAll(issue.getArticles())) {
                    problem("%s: its articles %s are not all in the seed", as, issue.getArticles());
                }
                if (issue.getCover() != null && !pictures.contains(issue.getCover())) {
                    problem("%s: no picture %s", as, issue.getCover());
                }
                issue.getEditorials().forEach((code, html) -> body(as + " in " + code, html, null));
            }
        }

        void partners() {
            for (DemoContent.PartnerSeed partner : seed.getPartners()) {
                String as = "advertiser " + partner.getId();
                if (partner.getId() != null && !PARTNER_ID.matcher(partner.getId()).matches()) {
                    problem("%s: an id is a lowercase letter, then letters, digits and hyphens", as);
                }
                if (!pages.contains(partner.getPage())) {
                    problem("%s: no page %s", as, partner.getPage());
                }
                if (!pictures.containsAll(partner.getPhotos().values())) {
                    problem("%s: its photos %s are not all the seed's pictures", as, partner.getPhotos().values());
                }
                if (partner.getBookings().isEmpty()) {
                    problem("%s: no booking", as);
                }
                boolean html5 = partner.getBookings().stream().anyMatch(b -> BannerType.HTML5.name().equals(b.getType()));
                List<String> files = html5 ? folder(as, partner.getFolder()) : List.of();
                for (DemoContent.BookingSeed booking : partner.getBookings()) {
                    booking(as + " booking " + booking.getName(), booking, files);
                }
            }
        }

        private List<String> folder(String as, String folder) {
            if (folder == null || folder.isBlank()) {
                problem("%s: an HTML5 booking needs the folder of its creatives", as);
                return List.of();
            }
            try {
                List<String> files = source.files(folder);
                if (files.isEmpty()) {
                    problem("%s: the folder %s has no files", as, folder);
                }
                return files;
            } catch (IllegalStateException e) {
                problem("%s: %s", as, e.getMessage());
                return List.of();
            }
        }

        private void booking(String as, DemoContent.BookingSeed booking, List<String> files) {
            BannerType type = constant(as, "type", BannerType.class, booking.getType());
            BannerSize size = constant(as, "size", BannerSize.class, booking.getSize());
            constant(as, "placement", BannerPlacement.class, booking.getPlacement());
            for (String page : booking.getPages()) {
                BannerPage bannerPage = constant(as, "page", BannerPage.class, page);
                if (bannerPage != null && !BannerPage.bookable().contains(bannerPage)) {
                    problem("%s: the page %s cannot be booked", as, page);
                }
            }
            if (!categories.containsAll(booking.getCategories())) {
                problem("%s: its sections %s are not all in the seed", as, booking.getCategories());
            }
            if (type == BannerType.HTML5) {
                if (size == BannerSize.AUTO) {
                    problem("%s: an HTML5 frame needs a height: TOP, SQUARE or TALL", as);
                }
                if (booking.getFile() == null || !CREATIVE.matcher(booking.getFile()).matches()) {
                    problem("%s: file %s is not a creative's name (name.html)", as, booking.getFile());
                } else if (!files.contains(booking.getFile())) {
                    problem("%s: the folder has no %s", as, booking.getFile());
                }
            } else if (type == BannerType.IMAGE) {
                if (booking.getAlt() == null || booking.getAlt().isBlank()) {
                    problem("%s: an image booking needs its alt text", as);
                }
                if (booking.getImage() == null || PICTURES.stream().noneMatch(booking.getImage().toLowerCase(Locale.ROOT)::endsWith)) {
                    problem("%s: image %s is not a picture file", as, booking.getImage());
                } else {
                    decodes(as, booking.getImage());
                }
            }
        }

        private <E extends Enum<E>> E constant(String as, String what, Class<E> type, String value) {
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException | NullPointerException e) {
                problem("%s: %s %s is none of %s", as, what, value, java.util.Arrays.toString(type.getEnumConstants()));
                return null;
            }
        }

        void brand() {
            DemoContent.BrandSeed brand = seed.getBrand();
            if (brand == null) {
                return;
            }
            if (brand.getFavicon() != null) {
                if (FAVICONS.stream().noneMatch(brand.getFavicon().toLowerCase(Locale.ROOT)::endsWith)) {
                    problem("brand: the favicon %s is an .svg or a .png", brand.getFavicon());
                } else {
                    readable("brand", brand.getFavicon());
                }
            }
            for (String logo : java.util.Arrays.asList(brand.getLogoLight(), brand.getLogoDark())) {
                if (logo != null) {
                    readable("brand", logo);
                }
            }
            if (brand.getShare() != null && brand.getShareFile() != null) {
                problem("brand: share and shareFile both name the share picture");
            }
            if (brand.getShare() != null && seed.getImages().stream()
                    .noneMatch(i -> i.getKey().equals(brand.getShare()) && i.getPhoto() != null)) {
                problem("brand: share names no photograph of the seed: %s", brand.getShare());
            }
            if (brand.getShareFile() != null) {
                decodes("brand", brand.getShareFile());
            }
        }

        private void readable(String as, String path) {
            try {
                source.read(path);
            } catch (IllegalStateException e) {
                problem("%s: %s", as, e.getMessage());
            }
        }

        void site() {
            DemoContent.SiteSeed site = seed.getSite();
            if (site != null && site.getBaseUrl() != null && !site.getBaseUrl().isBlank() && !HttpUrls.isHttpUrl(site.getBaseUrl())) {
                problem("site: baseUrl %s is not an http(s) address", site.getBaseUrl());
            }
            // The shape the site renders: any other id would be written, and then never load.
            if (site != null && site.getGtmId() != null && !site.getGtmId().isBlank()
                    && !io.pacod.cms.core.data.SiteSettings.GTM_ID.matcher(site.getGtmId()).matches()) {
                problem("site: gtmId %s is not a Tag Manager container id, GTM- and capitals or digits", site.getGtmId());
            }
        }
    }
}
