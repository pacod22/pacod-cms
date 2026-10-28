package io.pacod.cms.studio;

import io.pacod.cms.core.data.Banner;
import io.pacod.cms.core.data.BannerPage;
import io.pacod.cms.core.data.BannerSize;
import io.pacod.cms.core.data.BannerType;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.BannerRepository;
import io.pacod.cms.core.util.HttpUrls;
import io.pacod.cms.studio.form.BannerForm;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Banner administration.
 *
 * <p>Bookings are data, so selling or expiring one is an edit here, never a template change and a
 * deploy. The service is the standard console shape: {@link #require} plus {@link #apply} is a
 * read-modify-write, never a replace.
 *
 * <p>Ids are allocated max-id, like authors, because nothing references a banner by number --
 * position on the page comes from {@code placement} and {@code sortOrder}.
 * That read-then-write can collide under concurrent creates, which is the same known limitation
 * the article, author and image services carry.
 *
 * <p>No mutator evicts the public site's {@code banners} cache: that cache lives in the public
 * site's JVM, which an eviction in this process can never reach. The bound is
 * {@code CacheConfig}'s 60s TTL and nothing else -- a booking created, edited or deleted here is
 * live on the public site within a minute. Do not add {@code @CacheEvict}; in a process with no
 * cache manager it would be decoration that reads like a guarantee.
 */
@Slf4j
@Service
public class MgmtBannerService {

    /** Limits the max-id lookup to a single document. */
    private static final Pageable TOP_ONE = PageRequest.of(0, 1);

    private final BannerRepository bannerRepository;
    private final BannerCreativeService creativeService;

    private io.pacod.cms.core.repository.CategoryRepository categoryRepository;

    /**
     * Where a booking's sections are checked to exist; optional, so the hand-built instances of the
     * tests that book no section need none.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useCategories(io.pacod.cms.core.repository.CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public MgmtBannerService(BannerRepository bannerRepository, BannerCreativeService creativeService) {
        this.bannerRepository = bannerRepository;
        this.creativeService = creativeService;
    }

    /**
     * The console's banner list, optionally filtered.
     *
     * <p>Filtered in memory through {@link MgmtSearch} for the same reason the author and category
     * lists are: this is a collection of a handful of documents that {@code findAll} has already
     * read and sorted in the JVM, so a repository query would add a round trip and remove no work.
     *
     * <p>Sorted by placement, then order, then id, so the two surfaces read as two blocks in the
     * sequence they actually render.
     */
    public List<Banner> findAll(String search) {
        List<Banner> banners = new ArrayList<>();

        for (Banner banner : bannerRepository.findAll()) {
            if (MgmtSearch.matches(search, banner.getId(), banner.getName(),
                    banner.getType(), banner.getPlacement(), banner.getSource(),
                    banner.getTargetUrl(), banner.getCaption())) {
                banners.add(banner);
            }
        }

        banners.sort(Comparator
                .comparing((Banner b) -> b.getPlacement() == null ? "" : b.getPlacement().name())
                .thenComparing(b -> b.getSortOrder() == null ? Integer.MAX_VALUE : b.getSortOrder())
                .thenComparing(b -> b.getId() == null ? Integer.MAX_VALUE : b.getId()));
        return banners;
    }

    public Optional<Banner> find(Integer id) {
        return id == null ? Optional.empty() : bannerRepository.findById(id);
    }

    public long count() {
        return bannerRepository.count();
    }

    public Banner create(BannerForm form) {
        Banner banner = new Banner(getNextId());
        return apply(banner, form);
    }

    public Banner save(Integer id, BannerForm form) {
        return apply(require(id), form);
    }

    /** Removes the document and, if the console uploaded it, the creative file behind it. */
    public void delete(Integer id) {
        Banner banner = require(id);
        log.info("Deleting banner {} ({})", banner.getId(), banner.getName());

        // File first, then the document: an orphaned file is recoverable from the volume, while a
        // document pointing at a file that is gone renders a broken image on the public site.
        creativeService.delete(banner.getSource());
        bannerRepository.deleteById(banner.getId());
    }

    /**
     * Stores an uploaded creative and points the banner at it.
     *
     * <p>Its own endpoint rather than a field on the form, for the same reason article images are:
     * the upload is durable the moment it succeeds, so an operator who uploads and then navigates
     * away has not lost the file.
     */
    public Banner uploadCreative(Integer id, MultipartFile file) throws MediaException {
        Banner banner = require(id);

        if (banner.getType() != BannerType.IMAGE) {
            throw MgmtText.media("banners.error.uploadImageOnly");
        }

        String previous = banner.getSource();
        banner.setSource(creativeService.store(banner.getId(), file));
        banner.setLastModifiedOn(LocalDateTime.now());
        Banner saved = bannerRepository.save(banner);

        // Only after the document points at the new file, and only if the old one was ours. A
        // replacement that fails halfway then still leaves a banner with a creative.
        if (previous != null && !previous.equals(saved.getSource())) {
            creativeService.delete(previous);
        }

        return saved;
    }

    /**
     * Writes the edited fields onto the document.
     *
     * <p>Two fields are handled rather than copied:
     *
     * <ul>
     *   <li>{@code source} is taken from the form only for an HTML5 banner. An image banner's
     *       source is written by {@link #uploadCreative}, and the form does not carry it -- so
     *       assigning it here would blank the creative on the next save.</li>
     *   <li>{@code impressions} and {@code clicks} are not touched at all. They are absent from
     *       the form; see {@code BannerForm}.</li>
     * </ul>
     *
     * <p>A type switched from IMAGE to HTML5 keeps its uploaded file on disk deliberately:
     * switching back should not have destroyed the creative, and {@link #delete} still cleans it
     * up if the banner is removed while it is the current source.
     */
    private Banner apply(Banner banner, BannerForm form) {

        validate(form);

        banner.setName(form.getName().strip());
        banner.setType(form.getType());
        banner.setTargetUrl(blankToNull(form.getTargetUrl()));
        banner.setAltText(blankToNull(form.getAltText()));
        banner.setCaption(blankToNull(form.getCaption()));
        banner.setPlacement(form.getPlacement());
        banner.setSize(form.getSize());
        banner.setSortOrder(form.getSortOrder());
        banner.setPages(form.getPages() == null ? new ArrayList<>() : new ArrayList<>(form.getPages()));
        banner.setCategories(form.getCategories() == null ? new ArrayList<>()
                : new ArrayList<>(new java.util.LinkedHashSet<>(form.getCategories())));
        banner.setActive(form.isActive());
        banner.setActiveFrom(form.getActiveFrom());
        banner.setActiveTo(form.getActiveTo());
        banner.setLastModifiedOn(LocalDateTime.now());

        if (form.getType() == BannerType.HTML5) {
            banner.setSource(blankToNull(form.getSource()));
        }

        log.info("Saving banner {} ({}, {} {})", banner.getId(), banner.getName(),
                banner.getPlacement(), banner.getType());
        return bannerRepository.save(banner);
    }

    /**
     * The rules that depend on more than one field, and so cannot be bean-validation annotations.
     *
     * <p>The target URL check is the one that matters beyond data hygiene: {@code /b/{id}} puts
     * this value straight into a {@code Location} header, so a {@code javascript:} URL stored here
     * would be reflected back at every reader. The redirect refuses it a second time -- this is
     * the friendly refusal, that one is the guarantee.
     *
     * <p>The HTML5/AUTO refusal fires on <b>save only</b>. A document already written that way --
     * by hand, or before this check existed -- keeps rendering a blank column until somebody opens
     * it, at which point they are refused for a state they did not create. That is the right side to
     * err on: nothing migrates and nothing changes under a running deployment.
     */
    private void validate(BannerForm form) {

        if (form.getType() == BannerType.HTML5) {
            if (form.getSource() == null || form.getSource().isBlank()) {
                throw new MgmtOperationException("banners.error.html5NeedsSource");
            }
            // .partner-card__frame--auto is `height: auto` and the iframe inside it is `height: 100%`, so
            // an HTML5 booking on AUTO renders a box of no height at all: a blank ad column, with a
            // 200 response and nothing in any log. An image creative is the opposite -- it has an
            // intrinsic height and AUTO is the size it usually wants.
            if (form.getSize() == BannerSize.AUTO) {
                throw new MgmtOperationException("banners.error.html5Auto", labelOf(BannerSize.AUTO));
            }
        } else {
            if (!HttpUrls.isHttpUrl(form.getTargetUrl())) {
                throw new MgmtOperationException("banners.error.imageNeedsTarget");
            }
        }

        if (form.getActiveFrom() != null && form.getActiveTo() != null
                && form.getActiveTo().isBefore(form.getActiveFrom())) {
            throw new MgmtOperationException("banners.error.periodReversed");
        }

        // The form only offers bookable pages; this is for a crafted POST. A booking naming
        // /social would be stored and then run nowhere -- that page has no ad columns.
        for (String page : form.getPages() == null ? List.<String>of() : form.getPages()) {
            for (BannerPage candidate : BannerPage.values()) {
                if (!candidate.isBookable() && candidate.name().equals(page)) {
                    throw new MgmtOperationException("banners.error.pageNotBookable", labelOf(candidate));
                }
            }
        }

        // The same for sections: one that does not exist would book the banner nowhere at all.
        for (Integer category : form.getCategories() == null ? List.<Integer>of() : form.getCategories()) {
            if (category == null || categoryRepository != null && !categoryRepository.existsById(category)) {
                throw new MgmtOperationException("banners.error.noCategory", category);
            }
        }
    }

    /**
     * An enum label as a message argument, resolved in whichever language the message is: the
     * MessageSource resolves a {@code MessageSourceResolvable} argument itself, so the English
     * {@code getMessage()} and the operator's screen each get their own word.
     */
    private static DefaultMessageSourceResolvable labelOf(Enum<?> value) {
        return new DefaultMessageSourceResolvable(
                new String[]{MgmtText.enumKey(value, "label")}, value.name());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Reads one document off the index instead of loading every banner's id. */
    private Integer getNextId() {
        return bannerRepository.getMaxId(TOP_ONE)
                .stream()
                .findFirst()
                .map(b -> b.getId() + 1)
                .orElse(1);
    }

    private Banner require(Integer id) {
        return find(id).orElseThrow(
                () -> new MgmtNotFoundException("banners.error.noBanner.id", id));
    }
}
