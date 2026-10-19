package io.pacod.cms.site.controller;

import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.site.exceptions.IssueNotFoundException;
import io.pacod.cms.site.render.SitePrint;
import io.pacod.cms.site.services.IssueReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code /issue-print/{id}/front}: a journal issue's cover, editorial and contents, as the studio's
 * issue job has Gotenberg render them ({@code PdfIssueHandler}).
 *
 * <p><b>Only behind a valid print token</b> ({@link SitePrint}, set by {@code PrintFilter}): the
 * studio renders an issue before it is published, so this page reads drafts, and to anyone else it
 * is a 404 -- not a 403, which would say there is something here. The articles of the contents are
 * still published ones only ({@link IssueReader#contents}).
 *
 * <p>{@code ids} and {@code pages} are the articles in the issue's order and the page each starts
 * on, computed by the studio after counting; they must be number lists of the same length, at most
 * {@link JournalIssue#MAX_ARTICLES}, or the answer is a 404.
 */
@Controller
public class IssuePrintController {

    private static final Pattern NUMBERS = Pattern.compile("\\d{1,9}(,\\d{1,9})*");

    private final IssueReader reader;

    public IssuePrintController(IssueReader reader) {
        this.reader = reader;
    }

    @GetMapping("/issue-print/{id}/front")
    public String front(@PathVariable Integer id, @RequestParam(required = false) String lang,
                        @RequestParam(defaultValue = "") String ids, @RequestParam(defaultValue = "") String pages,
                        HttpServletRequest request, Model model, java.util.Locale locale) throws IssueNotFoundException {
        if (!SitePrint.is(request)) {
            throw new IssueNotFoundException(id);
        }
        List<Integer> articleIds = numbers(ids);
        List<Integer> pageNumbers = numbers(pages);
        if (articleIds == null || pageNumbers == null || articleIds.size() != pageNumbers.size()
                || articleIds.size() > JournalIssue.MAX_ARTICLES) {
            throw new IssueNotFoundException(id);
        }
        JournalIssue issue = reader.anyForPrint(id).orElseThrow(() -> new IssueNotFoundException(id));
        String language = lang == null || !lang.matches("[a-z]{2,3}") ? locale.getLanguage() : lang;
        model.addAttribute("lang", language);
        model.addAttribute("view", reader.view(issue, language));
        model.addAttribute("entries", reader.contents(articleIds, pageNumbers, language));
        return "issue-print-front";
    }

    /** The list, or {@code null} when it is not one. */
    private static List<Integer> numbers(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        if (!NUMBERS.matcher(value).matches()) {
            return null;
        }
        return Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }
}
