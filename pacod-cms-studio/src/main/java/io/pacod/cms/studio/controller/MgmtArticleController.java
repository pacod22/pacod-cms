package io.pacod.cms.studio.controller;

import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.studio.MgmtArticleService;
import io.pacod.cms.studio.MgmtImageService;
import io.pacod.cms.studio.MgmtLanguageService;
import io.pacod.cms.studio.MgmtNotFoundException;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.form.ArticleForm;
import io.pacod.cms.studio.form.TranslationForm;
import io.pacod.cms.studio.i18n.MgmtText;
import io.pacod.cms.core.data.Language;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Article administration: the list with its filters, and the editor.
 *
 * <p>Server-rendered throughout, with no credential in any script it serves: the browser calls
 * nothing but the studio.
 */
@Slf4j
@StudioPermission(read = "content.read", write = "content.write")
@Controller
@RequestMapping("/mgmt/articles")
public class MgmtArticleController {

    /** What the list template's pager assumes. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MgmtArticleService articleService;
    private final MgmtImageService imageService;
    private final MgmtLanguageService languageService;
    private final MgmtText text;
    private final io.pacod.cms.studio.access.StudioAccess access;

    public MgmtArticleController(MgmtArticleService articleService, MgmtImageService imageService,
                                 MgmtLanguageService languageService, MgmtText text,
                                 io.pacod.cms.studio.access.StudioAccess access) {
        this.articleService = articleService;
        this.imageService = imageService;
        this.languageService = languageService;
        this.text = text;
        this.access = access;
    }

    private io.pacod.cms.studio.ai.AiAccess ai;

    /** Whether the editor offers translation; optional so the hand-built tests need none. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useAi(io.pacod.cms.studio.ai.AiAccess ai) {
        this.ai = ai;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) Integer category,
                       @RequestParam(required = false) Integer author,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                       Model model) {

        // Deliberately an UNSORTED PageRequest. Spring Data appends a Pageable's sort to the one
        // declared on the repository's @Query, so passing one here would silently override the
        // ordering those finders were written around (see CategoryWidgetReader for the same note).
        PageRequest pageable = PageRequest.of(Math.max(page, 0), clamp(size));

        Page<Article> articles = articleService.list(q, category, author, pageable);

        model.addAttribute("articles", articles);
        model.addAttribute("categories", articleService.allCategories());
        model.addAttribute("authors", articleService.allAuthors());
        model.addAttribute("q", q);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedAuthor", author);
        model.addAttribute("size", clamp(size));

        return "mgmt/articles";
    }

    /**
     * Creates an empty article and opens it in the editor.
     *
     * <p>A POST, never a GET that creates as a side effect -- that would leave another empty
     * article behind on every reload of the editor.
     */
    @PostMapping
    public String create(RedirectAttributes flash) {
        Article article = articleService.create();

        if (article == null) {
            flash.addFlashAttribute("error", text.msg("articles.flash.createFailed"));
            return "redirect:/mgmt/articles";
        }

        flash.addFlashAttribute("message", text.msg("articles.flash.created", article.getId()));
        return "redirect:/mgmt/articles/" + article.getId();
    }

    private io.pacod.cms.studio.design.DesignPreview preview;

    /** Where "Preview" goes; optional so the hand-built tests need none. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usePreview(io.pacod.cms.studio.design.DesignPreview preview) {
        this.preview = preview;
    }

    /**
     * The editor's "Preview" and the image screen's "View on the site": a redirect to the article
     * on the website's own origin, with a preview token minted at the click, so a draft shows. Not
     * {@code @{/article/…}}, which on the studio's host is the studio's path, a 404.
     */
    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Integer id, @RequestParam(required = false) String lang,
                          RedirectAttributes redirect) {
        java.util.Optional<String> url = preview == null ? java.util.Optional.empty() : preview.articleUrl(id, lang);
        if (url.isEmpty()) {
            redirect.addFlashAttribute("error", text.msg("articles.preview.noAddress"));
            return "redirect:/mgmt/articles/" + id;
        }
        return "redirect:" + url.get();
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable Integer id, Model model) {
        Article article = articleService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.articleNotFound", id));

        ArticleForm form = ArticleForm.of(article);
        model.addAttribute("form", form);
        populateEditor(model, article, form);

        return "mgmt/article-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Integer id,
                       @Valid @ModelAttribute("form") ArticleForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {

        if (binding.hasErrors()) {
            // Re-render rather than redirect: a redirect would lose the operator's rich-text
            // edits, which can be an hour of work.
            populateEditor(model, articleService.find(id).orElseThrow(
                    () -> new MgmtNotFoundException("articles.error.articleNotFound", id)), form);
            return "mgmt/article-edit";
        }

        articleService.save(id, form, access.allowed("content.publish"));

        flash.addFlashAttribute("message", text.msg("articles.flash.saved"));
        return "redirect:/mgmt/articles/" + id;
    }

    /**
     * One translation of the article, on a screen of its own.
     *
     * <p>A separate screen and a separate form rather than {@link ArticleForm} with a language
     * switch: that form carries the rail -- authors, categories, status, date -- and
     * {@link MgmtArticleService#save} writes everything it carries. See {@link TranslationForm}.
     *
     * <p>Only languages the article already has are offered; the original language and anything
     * else 404.
     */
    @GetMapping("/{id}/translations/{lang}")
    public String editTranslation(@PathVariable Integer id, @PathVariable String lang, Model model) {
        Article article = requireArticle(id);
        String code = requireTranslation(article, lang);

        model.addAttribute("form", TranslationForm.of(article, code));
        populateTranslation(model, article, code);
        return "mgmt/article-translation-edit";
    }

    @PostMapping("/{id}/translations/{lang}")
    public String saveTranslation(@PathVariable Integer id,
                                  @PathVariable String lang,
                                  @Valid @ModelAttribute("form") TranslationForm form,
                                  BindingResult binding,
                                  Model model,
                                  RedirectAttributes flash) {

        Article article = requireArticle(id);
        String code = requireTranslation(article, lang);

        if (binding.hasErrors()) {
            // Re-render rather than redirect, for the reason save() does: unsaved rich text.
            populateTranslation(model, article, code);
            return "mgmt/article-translation-edit";
        }

        articleService.saveTranslation(id, code, form);

        flash.addFlashAttribute("message", text.msg("articles.flash.translationSaved", languageName(code)));
        return "redirect:/mgmt/articles/" + id + "/translations/" + code;
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{id}/translations/{lang}/delete")
    public String removeTranslation(@PathVariable Integer id, @PathVariable String lang, RedirectAttributes flash) {
        try {
            String code = articleService.removeTranslation(id, lang);
            flash.addFlashAttribute("message", text.msg("articles.flash.translationDeleted", languageName(code)));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }
        return "redirect:/mgmt/articles/" + id;
    }

    /**
     * Puts the body back to what it was before the last save.
     *
     * <p>Exists because the editor can rewrite markup on open; see
     * {@link MgmtArticleService#restoreText(Integer)}.
     */
    @PostMapping("/{id}/restore-text")
    public String restoreText(@PathVariable Integer id, RedirectAttributes flash) {
        try {
            articleService.restoreText(id);
            flash.addFlashAttribute("message", text.msg("articles.flash.textRestored"));
        } catch (MgmtOperationException e) {
            flash.addFlashAttribute("error", text.of(e));
        }

        return "redirect:/mgmt/articles/" + id;
    }

    @StudioPermission(read = "content.read", write = "content.delete")
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id, RedirectAttributes flash) {
        articleService.delete(id);

        flash.addFlashAttribute("message", text.msg("articles.flash.deleted", id));
        return "redirect:/mgmt/articles";
    }

    /**
     * The upload without JavaScript: a real multipart form POST answered by a redirect.
     *
     * <p>The browser normally uses {@link #uploadImage} instead, because a redirect here throws
     * away whatever the operator has written in the editor and not yet saved. This is what the
     * «Качи» button does when that fetch is unavailable, and it stays the form's declared action
     * so it cannot rot unnoticed.
     *
     * <p>Binds a <em>list</em> because the file input is {@code multiple}: as a single
     * {@code MultipartFile} Spring hands over the first part and drops the rest with nothing
     * logged and a 302 that looks like success. Every outcome is reported, since a partial
     * refusal is the ordinary one -- the per-article cap and the 600x400 rule both bite in the
     * middle of a selection.
     */
    @PostMapping("/{id}/images")
    public String addImage(@PathVariable Integer id,
                           @RequestParam("file") List<MultipartFile> files,
                           RedirectAttributes flash) {
        int added = 0;
        List<String> refused = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                articleService.addImage(id, file);
                added++;
            } catch (MediaException e) {
                // The message carries the reason -- wrong size, wrong type, duplicate filename --
                // and is the only feedback the operator gets, so it is shown verbatim, named with
                // the file it belongs to.
                log.warn("Image upload for article {} refused: {}", id, e.getMessage());
                // Named, but never named twice: most refusals already say which file they are
                // about ("garden.png is 601x400; ..."), while the per-article cap does not.
                String name = file.getOriginalFilename();
                String reason = String.valueOf(text.of(e));
                refused.add(name != null && !reason.contains(name) ? name + ": " + reason : reason);
            }
        }

        if (added == 1) {
            flash.addFlashAttribute("message", text.msg("articles.flash.imageAdded"));
        } else if (added > 1) {
            flash.addFlashAttribute("message", text.msg("articles.flash.imagesAdded", added));
        }

        if (!refused.isEmpty()) {
            flash.addFlashAttribute("error", text.msg("articles.flash.refused", String.join("; ", refused)));
        }

        return "redirect:/mgmt/articles/" + id;
    }

    @PostMapping("/{id}/images/{imageId}/delete")
    public String removeImage(@PathVariable Integer id,
                              @PathVariable Integer imageId,
                              RedirectAttributes flash) {
        articleService.removeImage(id, imageId);

        flash.addFlashAttribute("message", text.msg("articles.flash.imageRemoved"));
        return "redirect:/mgmt/articles/" + id;
    }

    /*
     * The three endpoints below are the console's JSON, alongside the image library's search.
     *
     * Everything else here is a form POST answered by a redirect, and that is still the right
     * shape for anything that changes a whole record. These three are different: they fire while
     * the operator is in the middle of writing an article, and a redirect would discard the
     * unsaved rich text -- exactly what mgmt.js's beforeunload guard exists to warn about. So the
     * browser calls them with fetch() and updates the cards itself. mgmt.js carries the CSRF
     * token through window.mgmtCsrf(); the mgmt chain has CSRF enabled and these are not exempt.
     */

    /**
     * Stores one uploaded file and answers with its row, instead of redirecting.
     *
     * <p>This is {@link #addImage} seen from the browser: both go through
     * {@link MgmtArticleService#addImage(Integer, org.springframework.web.multipart.MultipartFile)},
     * which is what keeps the two paths from drifting -- the cap, the 600x400 rule and the
     * article document's save are written once.
     *
     * <p>One file per request, and mgmt.js posts a multi-file selection one after another rather
     * than in parallel: image ids are assigned by {@code ImageService.getNextId()}, a read of the
     * current maximum followed by a write, so two uploads in flight at once can collide on one id.
     *
     * <p>A file past the container's multipart limit never reaches here -- Tomcat refuses it
     * inside the CSRF filter, below Spring, where no {@code @ControllerAdvice} can see it (see
     * {@code UploadSizeAdvice}). The browser gets an HTML 413, which mgmt.js's readJson reports
     * as a plain failure rather than choking on it; there is nothing to add here that could run.
     */
    @PostMapping("/{id}/images/upload")
    @ResponseBody
    public ResponseEntity<?> uploadImage(@PathVariable Integer id,
                                         @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(imageService.row(articleService.addImage(id, file)));
        } catch (MediaException e) {
            log.warn("Image upload for article {} refused: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", text.of(e)));
        }
    }

    /**
     * Records a new image order and hidden set for the article.
     *
     * <p>Order <em>is</em> the cover: the public site takes the first visible image as the share
     * card and every list thumbnail. Immediately durable, like an upload on this screen.
     */
    @PostMapping("/{id}/images/arrange")
    @ResponseBody
    public ResponseEntity<Void> arrangeImages(@PathVariable Integer id,
                                              @RequestParam(required = false) List<Integer> ids,
                                              @RequestParam(required = false) List<Integer> hidden) {
        articleService.arrangeImages(id, ids, hidden);
        return ResponseEntity.noContent().build();
    }

    /** Attaches an image that is already on the platform. Refusals come back as 409 with a reason. */
    @PostMapping("/{id}/images/{imageId}/attach")
    @ResponseBody
    public ResponseEntity<?> attachImage(@PathVariable Integer id, @PathVariable Integer imageId) {
        try {
            return ResponseEntity.ok(imageService.row(articleService.attachImage(id, imageId)));
        } catch (MgmtOperationException e) {
            log.warn("Attaching image {} to article {} refused: {}", imageId, id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", text.of(e)));
        }
    }

    /**
     * The editor's reference data.
     *
     * <p>Takes the form as well as the document because the author list is <em>ordered</em> by what
     * is selected: a {@code <select multiple>} submits its options in tree order, so the order of
     * the rendered {@code <option>} elements is the only place the byline's order can live. Reading
     * it off the form rather than off the article is what keeps the operator's order after a
     * validation error re-renders the screen.
     */
    private void populateEditor(Model model, Article article, ArticleForm form) {
        model.addAttribute("article", article);
        model.addAttribute("translations", translationNames(article));
        model.addAttribute("categories", articleService.allCategories());
        model.addAttribute("authors", articleService.authorsForPicker(form.getAuthorIds()));
        model.addAttribute("statuses", ArticleStatus.values());
        model.addAttribute("maxImages", MgmtArticleService.MAX_IMAGES_PER_ARTICLE);
        // The path shown under each thumbnail and inserted into the body, built in one place.
        model.addAttribute("imagePathPrefix", MgmtImageService.PUBLIC_PREFIX);
        // Translation, offered where AI is available to this person here: the website's languages
        // but the original, each marked when the article already has it.
        boolean aiAvailable = ai != null && ai.availableHere();
        model.addAttribute("aiAvailable", aiAvailable);
        if (aiAvailable) {
            String original = io.pacod.cms.core.data.LanguageChain.original();
            java.util.List<String> have = article.translatedIn();
            model.addAttribute("translateLanguages", languageService.findAll().stream()
                    .filter(l -> l.getCode() != null && !l.getCode().equals(original))
                    .map(l -> new TranslateChoice(l.getCode(), l.getAdminName(), have.contains(l.getCode())))
                    .toList());
        }
    }

    /** A language the editor offers to translate into, and whether the article has it already. */
    public record TranslateChoice(String code, String name, boolean present) {
    }

    private Article requireArticle(Integer id) {
        return articleService.find(id).orElseThrow(
                () -> new MgmtNotFoundException("articles.error.articleNotFound", id));
    }

    /** A 404 rather than the service's refusal: on a GET there is no screen to put a flash on. */
    private String requireTranslation(Article article, String lang) {
        try {
            return MgmtArticleService.requireTranslation(article, lang);
        } catch (MgmtOperationException e) {
            throw new MgmtNotFoundException(e.code(), e.args());
        }
    }

    private void populateTranslation(Model model, Article article, String code) {
        model.addAttribute("article", article);
        model.addAttribute("lang", code);
        model.addAttribute("languageName", languageName(code));
        model.addAttribute("translations", translationNames(article));
    }

    /**
     * Code to console name for each language the article is translated into, in the order
     * {@code translatedIn()} returns them.
     *
     * <p>Article translations are keyed by bare language codes that need not have a row in the
     * {@code language} collection, so a code with no row there falls back to itself, uppercased.
     */
    private Map<String, String> translationNames(Article article) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String code : article.translatedIn()) {
            names.put(code, languageName(code));
        }
        return names;
    }

    /**
     * Never throws: the article editor needs the {@code language} collection only for the names,
     * and a failed lookup must cost the name, not the screen.
     */
    private String languageName(String code) {
        try {
            return languageService.find(code).map(Language::displayName).orElse(code.toUpperCase());
        } catch (RuntimeException e) {
            log.warn("Could not resolve the name of language {}: {}", code, e.getMessage());
            return code.toUpperCase();
        }
    }

    private int clamp(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
