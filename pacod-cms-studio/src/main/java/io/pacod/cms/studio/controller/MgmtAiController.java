package io.pacod.cms.studio.controller;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.studio.MgmtArticleService;
import io.pacod.cms.studio.access.StudioPermission;
import io.pacod.cms.studio.ai.AiAccess;
import io.pacod.cms.studio.ai.AiGateway;
import io.pacod.cms.studio.ai.WritingAssist;
import io.pacod.cms.studio.i18n.MgmtText;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /mgmt/ai/*}: the editor's writing help, as JSON for {@code fetch} -- the editor holds
 * unsaved rich text, and a redirect would lose it. Each answer goes back to the operator, who
 * inserts it, or not; nothing here writes an article.
 *
 * <p>{@code ai.use} for every call (a person may have it without {@code content.write}; they
 * still cannot save), and AI available here ({@link AiAccess}): otherwise a 409 whose
 * {@code error} says why, which {@code readJson} shows.
 */
@StudioPermission(read = "ai.use", write = "ai.use")
@Controller
@RequestMapping("/mgmt/ai")
public class MgmtAiController {

    private final AiAccess access;
    private final WritingAssist assist;
    private final MgmtArticleService articles;
    private final io.pacod.cms.studio.MgmtImageService images;
    private final MgmtText text;

    public MgmtAiController(AiAccess access, WritingAssist assist, MgmtArticleService articles,
                            io.pacod.cms.studio.MgmtImageService images, MgmtText text) {
        this.access = access;
        this.assist = assist;
        this.articles = articles;
        this.images = images;
        this.text = text;
    }

    /** {@code {action, html}}: the selection rewritten, cleaned. */
    @PostMapping("/assist")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> rewrite(@RequestBody Map<String, String> request) {
        Optional<ResponseEntity<Map<String, Object>>> refused = refused();
        if (refused.isPresent()) {
            return refused.get();
        }
        WritingAssist.Action action;
        try {
            action = WritingAssist.Action.valueOf(String.valueOf(request.get("action")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, text.msg("ai.error.action"));
        }
        return answer(() -> Map.of("html", assist.rewrite(action, request.get("html"))));
    }

    /** {@code {articleId}}: a summary for the description field. */
    @PostMapping("/describe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> describe(@RequestBody Map<String, Object> request) {
        Optional<ResponseEntity<Map<String, Object>>> refused = refused();
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<Article> article = article(request.get("articleId"));
        if (article.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, text.msg("ai.error.article"));
        }
        return answer(() -> Map.of("description", assist.describe(article.get())));
    }

    /** {@code {articleId}}: tags to add. */
    @PostMapping("/tags")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> tags(@RequestBody Map<String, Object> request) {
        Optional<ResponseEntity<Map<String, Object>>> refused = refused();
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<Article> article = article(request.get("articleId"));
        if (article.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, text.msg("ai.error.article"));
        }
        return answer(() -> Map.of("tags", assist.tags(article.get())));
    }

    /** {@code {imageId, lang}}: alt text for that image in that language, from the picture itself. */
    @PostMapping("/alt")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> alt(@RequestBody Map<String, Object> request) {
        Optional<ResponseEntity<Map<String, Object>>> refused = refused();
        if (refused.isPresent()) {
            return refused.get();
        }
        String lang = String.valueOf(request.get("lang"));
        Optional<io.pacod.cms.core.data.ImageMedia> image;
        try {
            image = images.find(Integer.valueOf(String.valueOf(request.get("imageId"))));
        } catch (NumberFormatException e) {
            image = Optional.empty();
        }
        Optional<byte[]> bytes = image.flatMap(images::bytesOf);
        if (!lang.matches("[a-z]{2,3}") || bytes.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, text.msg("ai.error.image"));
        }
        String caption = image.get().getCaption();
        return answer(() -> Map.of("alt", assist.alt(bytes.get(), caption, lang)));
    }

    private Optional<ResponseEntity<Map<String, Object>>> refused() {
        return access.refusalHere().map(refusal -> error(HttpStatus.CONFLICT, text.label(refusal)));
    }

    private Optional<Article> article(Object id) {
        try {
            return articles.find(Integer.valueOf(String.valueOf(id)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private ResponseEntity<Map<String, Object>> answer(java.util.function.Supplier<Map<String, Object>> work) {
        try {
            return ResponseEntity.ok(work.get());
        } catch (AiGateway.AiException e) {
            return error(HttpStatus.BAD_GATEWAY, text.msg("ai.error.gateway", e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
