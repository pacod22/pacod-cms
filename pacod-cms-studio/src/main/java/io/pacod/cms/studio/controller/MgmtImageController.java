package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.studio.MgmtImageService;
import io.pacod.cms.studio.MgmtImageService.ArticleFilter;
import io.pacod.cms.studio.MgmtImageService.ImageRow;
import io.pacod.cms.studio.MgmtImageService.ImageSort;
import io.pacod.cms.studio.MgmtImageService.Usage;
import io.pacod.cms.studio.MgmtImageService.ImageUsage;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.i18n.MgmtText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The platform's image library: a screen of its own, and the search behind the editor's picker.
 *
 * <p>Both live under {@code /mgmt/}, which is not incidental: anything on a new prefix would fall
 * outside {@code MgmtSecurityConfig}'s authenticated matcher, into the permit-all chain. Being
 * under {@code /mgmt/} is what makes this a zero-configuration addition; it beats
 * {@code MgmtHomeController}'s {@code /mgmt/**} catch-all the same way {@code /mgmt/articles}
 * does, on pattern specificity.
 *
 * <p>Must stay in this package: {@code MgmtControllerAdvice} is scoped to
 * {@code io.pacod.cms.studio.controller}, so a controller outside it silently renders every
 * screen without {@code currentUser} and {@code imageRules}.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/images")
public class MgmtImageController {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;

    /** The picker is a narrow rail column; a page is a scroll, not a screenful. */
    private static final int PICKER_PAGE_SIZE = 12;

    private final MgmtImageService imageService;
    private final MgmtText text;

    /** A language's alt text field on the image screen. */
    public record AltField(String code, String name, String value) {
    }

    private io.pacod.cms.studio.MgmtLanguageService languages;
    private io.pacod.cms.studio.ai.AiAccess ai;

    /** Optional, so the hand-built tests need neither. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useAltText(io.pacod.cms.studio.MgmtLanguageService languages, io.pacod.cms.studio.ai.AiAccess ai) {
        this.languages = languages;
        this.ai = ai;
    }

    public MgmtImageController(MgmtImageService imageService, MgmtText text) {
        this.imageService = imageService;
        this.text = text;
    }

    /**
     * @param article an article number or a fragment of its title; restricts the library to that
     *                article's attachments. The reference points from article to image, so this
     *                cannot be a condition on the image query -- it is resolved to a bounded id
     *                list first. See {@code MgmtImageService.resolveArticleFilter}.
     * @param usage   {@code USED} / {@code UNUSED} / absent. Unused is the one worth having: an
     *                image nothing references is a file the platform carries for nothing, and the
     *                only kind that can actually be deleted.
     * @param sort    the column, as a plain link on the header -- {@code id} / {@code name} /
     *                {@code articles}, the same allow-list the editor's picker uses, so the two
     *                screens over one collection cannot order it differently. A script that
     *                reordered the DOM would be wrong twice over: this list is <em>paged</em>, so
     *                it would order the page rather than the library, and a control that needs
     *                JavaScript to mean anything fails silently when the script does.
     * @param dir     {@code desc} reverses; anything else, including absent, is ascending.
     */
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String article,
                       @RequestParam(required = false) String usage,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String dir,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                       Model model) {

        ArticleFilter articleFilter = imageService.resolveArticleFilter(article);
        Usage want = Usage.of(usage);
        ImageSort key = ImageSort.of(sort);
        boolean descending = descending(dir);

        Page<ImageRow> images = imageService.search(q, articleFilter, want, key, descending,
                PageRequest.of(Math.max(page, 0), clamp(size)));

        model.addAttribute("images", images);
        model.addAttribute("q", q);
        model.addAttribute("article", article);
        model.addAttribute("articleFilter", articleFilter);
        // The parsed value, not what arrived: the select has to show the filter actually applied,
        // and Usage.of() forgives anything it does not recognise.
        model.addAttribute("usage", want.name());
        /*
         * The same rule, and here it is load-bearing rather than cosmetic: the header marks the
         * active column and builds each link's flip from these two attributes, so `?sort=nonsense`
         * has to arrive as the default. Echo it raw and the screen sorts by a column that says it
         * is not sorting, and the next click produces a descending sort out of nowhere. They are
         * also what the search form, «Изчисти», the pager and the page-size form carry as hidden
         * fields -- a GET form replaces the whole query string, so without them the first
         * keystroke throws the ordering away.
         */
        model.addAttribute("sort", key.name().toLowerCase(Locale.ROOT));
        model.addAttribute("dir", descending ? "desc" : "asc");
        // So the "showing the first N of M" line and the cap itself cannot drift apart.
        model.addAttribute("articleMatchLimit", MgmtImageService.ARTICLE_MATCH_LIMIT);
        model.addAttribute("size", clamp(size));

        return "mgmt/images";
    }

    /**
     * One image: what the document says, what the file on disk says, and who is using it.
     *
     * <p>{@code /search} above is a literal path and wins over this pattern on specificity, so the
     * picker's endpoint is not shadowed by an image called "search".
     *
     * <p>The list's query, page and size ride along as request parameters so that «Към списъка»
     * returns to the page the operator left rather than to page one of an unfiltered library --
     * which is a different screen entirely.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Integer id,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) String article,
                         @RequestParam(required = false) String usage,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) String dir,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                         Model model) {

        ImageMedia image = imageService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.imageNotFound", id));

        List<ImageUsage> usages = imageService.usedBy(id);

        model.addAttribute("image", image);
        // Not ImageRow: its `usages` is a count query answering exactly what the list below
        // already knows, and its `path` is the same static call.
        model.addAttribute("path", MgmtImageService.publicPath(image));
        model.addAttribute("usages", usages);
        model.addAttribute("file", imageService.fileOf(image));
        // The alt text per language: the website's languages, the original first, each with what
        // the image has in it; and whether a model may suggest one.
        String original = io.pacod.cms.core.data.LanguageChain.original();
        java.util.List<io.pacod.cms.core.data.Language> offered = languages == null ? java.util.List.of() : languages.findAll();
        java.util.List<AltField> alts = new java.util.ArrayList<>();
        alts.add(new AltField(original, offered.stream().filter(l -> original.equals(l.getCode()))
                .map(io.pacod.cms.core.data.Language::getAdminName).findFirst().orElse(original),
                io.pacod.cms.core.data.TextItems.find(image.getAlts(), original)));
        for (io.pacod.cms.core.data.Language language : offered) {
            if (language.getCode() != null && !language.getCode().equals(original)) {
                alts.add(new AltField(language.getCode(), language.getAdminName(),
                        io.pacod.cms.core.data.TextItems.find(image.getAlts(), language.getCode())));
            }
        }
        model.addAttribute("alts", alts);
        model.addAttribute("aiAvailable", ai != null && ai.availableHere());

        // Every filter the list was under, carried through untouched so the back link restores it
        // -- the ordering among them, or «Към списъка» would land on the same page number of a
        // differently ordered list, which is a different set of rows.
        model.addAttribute("q", q);
        model.addAttribute("article", article);
        model.addAttribute("usage", Usage.of(usage).name());
        model.addAttribute("sort", ImageSort.of(sort).name().toLowerCase(Locale.ROOT));
        model.addAttribute("dir", descending(dir) ? "desc" : "asc");
        model.addAttribute("page", Math.max(page, 0));
        model.addAttribute("size", clamp(size));

        return "mgmt/image-detail";
    }

    /**
     * Writes the caption, and only the caption.
     *
     * <p>Plain request parameters rather than a bound form object, like {@code arrange} and
     * {@code attach} on the article controller. See {@code MgmtImageService.saveCaption} for why
     * an {@code ImageForm} over this document would be a trap: {@code filename} locates the file
     * on disk, and Spring Data {@code save()} writes every field a form carries.
     */
    @PostMapping("/{id}")
    public String saveCaption(@PathVariable Integer id,
                              @RequestParam(required = false) String caption,
                              @RequestParam java.util.Map<String, String> params,
                              RedirectAttributes flash) {

        // The alt text per language comes as alt_<code>; the caption beside it.
        java.util.Map<String, String> alts = new java.util.LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (name.startsWith("alt_")) {
                alts.put(name.substring("alt_".length()), value);
            }
        });
        imageService.saveDescription(id, caption, alts);

        flash.addFlashAttribute("message", text.msg("articles.flash.captionSaved"));
        return "redirect:/mgmt/images/" + id;
    }

    /**
     * The editor's picker, as JSON.
     *
     * <p>The one place in the console where a search does not reload the page, because the page it
     * would reload is the article editor and the reload would take the operator's unsaved rich text
     * with it.
     *
     * <p>A session that has expired answers this with a 302 to the login page rather than a 401 --
     * see {@code MgmtSecurityConfig} -- so the caller has to check the response before parsing it.
     * mgmt.js does.
     *
     * @param sort {@code id} / {@code name} / {@code articles}, an allow-list and never a property
     *             path -- it arrives off a query string and ends up in a {@code Sort}. Anything
     *             else is the default rather than a 400, as {@code usage} above is.
     * @param dir  {@code desc} reverses; anything else, including absent, is ascending.
     */
    @GetMapping("/search")
    @ResponseBody
    public Map<String, Object> search(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String sort,
                                      @RequestParam(required = false) String dir,
                                      @RequestParam(defaultValue = "0") int page) {

        ImageSort key = ImageSort.of(sort);
        boolean descending = descending(dir);

        Page<ImageRow> images = imageService.search(
                q, key, descending, PageRequest.of(Math.max(page, 0), PICKER_PAGE_SIZE));

        return Map.of(
                "images", images.getContent(),
                "page", images.getNumber(),
                "more", images.hasNext(),
                // The ordering that was APPLIED, never what was asked for. mgmt.js paints the
                // active button from these two, so echoing `?sort=nonsense` back raw would mark a
                // column active that the list is not ordered by, and the next click would reverse a
                // direction nobody chose -- the rule the author and category screens state as
                // "normalise before the controller puts them in the model".
                "sort", key.name().toLowerCase(Locale.ROOT),
                "dir", descending ? "desc" : "asc");
    }

    /**
     * @param from {@code "detail"} when the button was on the image's own screen. A refusal has to
     *             land back where the operator can act on it -- the detail screen is the one that
     *             lists the articles they now have to detach the picture from. A <em>successful</em>
     *             delete always goes to the list, because the screen it came from no longer has a
     *             document behind it.
     */
    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id,
                         @RequestParam(required = false) String from,
                         RedirectAttributes flash) {
        try {
            imageService.delete(id);
            flash.addFlashAttribute("message", text.msg("articles.flash.imageDeleted", id));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));

            if ("detail".equals(from)) {
                return "redirect:/mgmt/images/" + id;
            }
        }

        return "redirect:/mgmt/images";
    }

    /** Lenient in the same direction {@code ImageSort.of} is: anything unrecognised is ascending. */
    private static boolean descending(String dir) {
        return dir != null && "desc".equalsIgnoreCase(dir.strip());
    }

    private int clamp(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
