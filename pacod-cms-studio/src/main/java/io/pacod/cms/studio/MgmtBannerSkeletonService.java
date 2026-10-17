package io.pacod.cms.studio;

import io.pacod.cms.core.data.BannerSkeleton;
import io.pacod.cms.core.repository.BannerSkeletonRepository;
import io.pacod.cms.studio.form.BannerSkeletonForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skeleton administration: the looks a layout can be built from.
 *
 * <p>Two built-ins, {@code sidebar} and {@code top}, are seeded by {@link BannerSkeletonBootstrap};
 * an operator may add more. Everything an added skeleton could get wrong is caught by
 * {@link BannerSkeletonParser}, which is why every write here goes through it rather than storing
 * markup and finding out later.
 */
@Slf4j
@Service
public class MgmtBannerSkeletonService {

    /** Same shape as a layout id, and fixed once set for the same reason. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    private final BannerSkeletonRepository skeletonRepository;
    private final BannerSkeletonParser parser;
    private final MgmtBannerLayoutService layoutService;

    /**
     * {@code @Lazy} on the layout service breaks a cycle that is real rather than accidental: a
     * skeleton may not be deleted while a layout is built on it, and a layout cannot be generated
     * without its skeleton. Both directions are needed and neither is the wrong way round.
     */
    public MgmtBannerSkeletonService(BannerSkeletonRepository skeletonRepository,
                                     BannerSkeletonParser parser,
                                     @Lazy MgmtBannerLayoutService layoutService) {
        this.skeletonRepository = skeletonRepository;
        this.parser = parser;
        this.layoutService = layoutService;
    }

    public List<BannerSkeleton> findAll(String search) {
        List<BannerSkeleton> skeletons = new ArrayList<>();
        for (BannerSkeleton skeleton : skeletonRepository.findAll()) {
            if (MgmtSearch.matches(search, skeleton.getId(), skeleton.getTitle(), skeleton.getGtmId())) {
                skeletons.add(skeleton);
            }
        }
        skeletons.sort(Comparator.comparing(BannerSkeleton::getId));
        return skeletons;
    }

    /** Every skeleton, for the "new layout" picker. */
    public List<BannerSkeleton> findAll() {
        return findAll(null);
    }

    public Optional<BannerSkeleton> find(String id) {
        return id == null || id.isBlank() ? Optional.empty() : skeletonRepository.findById(id);
    }

    /** The stored split, shown back as the one file it came from. */
    public String sourceOf(BannerSkeleton skeleton) {
        return parser.assemble(skeleton);
    }

    /**
     * The skeleton behind an existing folder, reusing one that already matches.
     *
     * <p>Returns null when the markup cannot drive the generator -- an advertiser's animation export
     * has no container to write frames into -- which is how the caller decides that a folder is
     * imported rather than built. A refusal here is a fact about the folder, not an error.
     *
     * <h2>Deduped by content</h2>
     *
     * <p>Hand-copied folders come from a few templates, so adopting them one by one would
     * otherwise produce a near-duplicate skeleton per booking and a «Шаблони» list nobody could
     * read. Two folders whose chrome and stylesheet are byte-identical therefore share one skeleton
     * -- and a folder copied from a built-in resolves to the seeded {@code sidebar} or
     * {@code top}.
     *
     * <p>The comparison is on the <em>split</em> plus the CSS, not on the raw file: the frames
     * between the container's tags are exactly what differs between two bookings on the same
     * template, and they are not part of the look.
     */
    public BannerSkeleton adoptFrom(String folderName, String html, String css) {

        BannerSkeletonParser.Split split;
        try {
            split = parser.parse(html);
        } catch (MgmtOperationException e) {
            log.info("Folder {} is not skeleton-shaped and will be adopted as imported: {}",
                    folderName, e.getMessage());
            return null;
        }

        String stylesheet = css == null ? "" : css;

        for (BannerSkeleton existing : skeletonRepository.findAll()) {
            if (matches(existing, split, stylesheet)) {
                log.info("Folder {} matches the existing skeleton {}", folderName, existing.getId());
                return existing;
            }
        }

        BannerSkeleton derived = new BannerSkeleton();
        derived.setId(derivedId(folderName));
        derived.setTitle("От " + folderName);
        derived.setBuiltIn(false);
        derived.setHtmlHead(split.htmlHead());
        derived.setContainerTag(split.containerTag());
        derived.setHtmlTail(split.htmlTail());
        derived.setGtmId(split.gtmId());
        derived.setCss(stylesheet);
        // Dimensions are the layout's, and this folder's are whatever its own stylesheet says --
        // guessing a number here would put one in the builder that the CSS then overrides.
        log.info("Derived a new skeleton {} from folder {}", derived.getId(), folderName);
        return skeletonRepository.save(derived);
    }

    /**
     * Whether two chromes are the same <em>look</em>, compared with whitespace collapsed.
     *
     * <p>Byte equality is the obvious rule and it is wrong: a hand-copied folder can differ from
     * the template it was copied from by <b>two blank lines</b> in the tail and by nothing else --
     * head, container tag and stylesheet byte-identical, as in the tests' {@code acme2} case. Under
     * byte equality it would derive its own skeleton, and so would every other hand-copied folder,
     * which is one skeleton per booking and precisely the noise this method exists to prevent.
     *
     * <p>Collapsing whitespace for the <em>comparison</em> changes nothing about what is stored: the
     * skeleton that wins keeps its own bytes. And two chromes that differ only in indentation are
     * the same look by any reading -- there is no {@code <pre>} in a banner, and the only script in
     * the head is a Google Tag Manager snippet whose formatting means nothing.
     */
    private boolean matches(BannerSkeleton skeleton, BannerSkeletonParser.Split split, String css) {
        return sameIgnoringWhitespace(skeleton.getHtmlHead(), split.htmlHead())
                && sameIgnoringWhitespace(skeleton.getContainerTag(), split.containerTag())
                && sameIgnoringWhitespace(skeleton.getHtmlTail(), split.htmlTail())
                && sameIgnoringWhitespace(skeleton.getCss(), css);
    }

    private boolean sameIgnoringWhitespace(String a, String b) {
        return Objects.equals(collapse(a), collapse(b));
    }

    private String collapse(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").strip();
    }

    /**
     * A skeleton id derived from a folder name, which -- unlike the folder's own id -- must satisfy
     * the narrow pattern, because nothing on disk is named after it.
     */
    private String derivedId(String folderName) {
        String base = folderName == null ? "" : folderName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "skeleton-" + base;
        }
        base = base.length() > 32 ? base.substring(0, 32) : base;

        String candidate = base;
        for (int i = 2; skeletonRepository.existsById(candidate) && i < 100; i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    public BannerSkeleton create(BannerSkeletonForm form) {

        String id = normaliseId(form.getId());
        if (skeletonRepository.existsById(id)) {
            throw new MgmtOperationException("banners.error.skeletonExists", id);
        }

        BannerSkeleton skeleton = new BannerSkeleton();
        skeleton.setId(id);
        skeleton.setBuiltIn(false);
        apply(skeleton, form);

        log.info("Created banner skeleton {}", id);
        return skeletonRepository.save(skeleton);
    }

    public BannerSkeleton save(String id, BannerSkeletonForm form) {
        BannerSkeleton skeleton = require(id);
        apply(skeleton, form);
        log.info("Saved banner skeleton {}", id);
        return skeletonRepository.save(skeleton);
    }

    /**
     * Refused twice over.
     *
     * <p>A built-in is refused outright: the seed only runs while the collection is empty, so
     * deleting one would remove it permanently with nothing saying so -- the same reason
     * {@code MgmtLanguageService} refuses the original language and English.
     *
     * <p>Anything else is refused while a layout is built on it, because the folder is regenerated
     * from the skeleton on every save and a layout without one could never be edited again. Nothing
     * cascades: deleting the layouts too would destroy folders the site is running.
     */
    public void delete(String id) {

        BannerSkeleton skeleton = require(id);

        if (skeleton.isBuiltIn()) {
            throw new MgmtOperationException("banners.error.skeletonBuiltIn", skeleton.getTitle());
        }

        long layouts = layoutService.countUsingSkeleton(id);
        if (layouts > 0) {
            throw new MgmtOperationException("banners.error.skeletonInUse", layouts);
        }

        log.info("Deleting banner skeleton {}", id);
        skeletonRepository.deleteById(id);
    }

    /**
     * Reads {@code index.html} and {@code css/banner.css} out of an uploaded archive, so an operator
     * can start from a folder they already have.
     *
     * <p>Deliberately fills the form rather than saving: a skeleton is two text fields the operator
     * is about to read and probably edit, and importing straight to a document would hide the one
     * step -- looking at the markup -- that this whole screen exists to make possible.
     */
    public BannerSkeletonForm readArchive(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new MgmtOperationException("banners.error.noFile");
        }

        String html = null;
        String css = null;
        String htmlAt = null;
        String cssAt = null;

        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.startsWith("__macosx/") || name.contains("/.")) {
                    continue;
                }
                // Shallowest wins. A zipped folder puts index.html one level down, and an archive
                // of a hand-kept folder often also carries an `old/index.html` or a `backup/` copy
                // -- taking whichever the archive happens to list first would pick between them by
                // accident.
                if (name.endsWith("index.html") && shallower(name, htmlAt)) {
                    html = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    htmlAt = name;
                } else if (name.endsWith("banner.css") && shallower(name, cssAt)) {
                    css = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    cssAt = name;
                }
            }
        } catch (IOException e) {
            log.warn("Could not read uploaded skeleton archive", e);
            throw new MgmtOperationException("banners.error.zipUnreadable");
        }

        if (html == null) {
            throw new MgmtOperationException("banners.error.zipNoIndex");
        }
        if (css == null) {
            throw new MgmtOperationException("banners.error.zipNoCss");
        }

        BannerSkeletonForm form = new BannerSkeletonForm();
        form.setHtml(html);
        form.setCss(css);
        return form;
    }

    /**
     * Writes the form onto the document, splitting the markup as it goes.
     *
     * <p>{@code gtmId} comes out of the parse, never off the form: the id lives in the markup, and a
     * second field carrying it would be a second place to state one fact.
     */
    private void apply(BannerSkeleton skeleton, BannerSkeletonForm form) {

        if ((form.getWidth() == null) != (form.getHeight() == null)) {
            throw new MgmtOperationException("banners.error.widthHeightTogether");
        }

        BannerSkeletonParser.Split split = parser.parse(form.getHtml());

        skeleton.setTitle(form.getTitle().strip());
        skeleton.setWidth(form.getWidth());
        skeleton.setHeight(form.getHeight());
        skeleton.setHtmlHead(split.htmlHead());
        skeleton.setContainerTag(split.containerTag());
        skeleton.setHtmlTail(split.htmlTail());
        skeleton.setGtmId(split.gtmId());
        skeleton.setCss(form.getCss());
    }

    /** Fewer path segments wins; nothing chosen yet always loses. */
    private boolean shallower(String candidate, String current) {
        if (current == null) {
            return true;
        }
        long candidateDepth = candidate.chars().filter(c -> c == '/').count();
        long currentDepth = current.chars().filter(c -> c == '/').count();
        return candidateDepth < currentDepth;
    }

    private String normaliseId(String raw) {
        String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new MgmtOperationException("banners.error.skeletonIdPattern");
        }
        return id;
    }

    public BannerSkeleton require(String id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("banners.error.noSkeleton", id));
    }
}
