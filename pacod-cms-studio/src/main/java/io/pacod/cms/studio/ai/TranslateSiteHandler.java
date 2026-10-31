package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.data.Category;
import io.pacod.cms.core.data.LanguageChain;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.data.TextItem;
import io.pacod.cms.core.repository.CategoryRepository;
import io.pacod.cms.core.repository.MenuRepository;
import io.pacod.cms.core.repository.PageRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.services.TranslationWriter;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.jobs.ArticleSelection;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobHandler;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * {@code TRANSLATE_SITE}: everything of a website's that is not an article -- its standing pages,
 * categories, tags, menus and social page -- into each language asked for, through the AI gateway.
 * The rules are {@link TranslateArticlesHandler}'s (its prompt, schema and checks; the material is
 * data; a page body must keep its skeleton), plus:
 *
 * <ul>
 *   <li><b>Parts</b> ({@code parts}, from {@link #PARTS}) say what; tags are a part of their own
 *       because they are unbounded -- the nightly rebuild only ever adds.</li>
 *   <li><b>From where.</b> A page from {@code original()}, and a page without a body in it is
 *       skipped, never translated from a fallback; a category from its own {@code lang}; a tag
 *       from its name. A menu or social label from the original, else English: the seed's labels
 *       are English, and a website created in another language would otherwise get none.</li>
 *   <li><b>Writes</b> go through {@link TranslationWriter}: pages, categories and tags as one
 *       language's entries in one atomic update; a menu or the social page as a compare-and-set
 *       on what was read, whole or not at all per language, so an edit made meanwhile is never
 *       overwritten -- that menu fails with "run it again" instead.</li>
 *   <li><b>At most {@link #MAX_CALLS} gateway calls a run</b>: the job ends early, saying so, and
 *       in the default {@code missing} mode the next run carries on where it stopped. One click
 *       must not spend the installation's key on a website with ten thousand tags.</li>
 *   <li>Terms and privacy are translated like any page: only the three text lists are written,
 *       so {@code officialInDefaultLanguageOnly} -- the notice that the original governs -- stays.</li>
 * </ul>
 */
@Component
public class TranslateSiteHandler implements JobHandler {

    /** What one run may cover, in the order it covers them. */
    public static final List<String> PARTS = List.of("pages", "categories", "tags", "menus", "social");

    /** Gateway calls one run may make. */
    public static final int MAX_CALLS = 2000;

    private final AiGateway gateway;
    private final AiAccess access;
    private final PageRepository pages;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final MenuRepository menus;
    private final TranslationWriter writer;
    private final AiModels models;

    public TranslateSiteHandler(AiGateway gateway, AiAccess access, PageRepository pages,
                                CategoryRepository categories, TagRepository tags, MenuRepository menus,
                                TranslationWriter writer, AiModels models) {
        this.gateway = gateway;
        this.access = access;
        this.pages = pages;
        this.categories = categories;
        this.tags = tags;
        this.menus = menus;
        this.writer = writer;
        this.models = models;
    }

    @Override
    public JobType type() {
        return JobType.TRANSLATE_SITE;
    }

    /** The parts named in {@code value} that {@link #PARTS} knows, in its order. */
    public static List<String> parts(String value) {
        Set<String> named = new LinkedHashSet<>();
        for (String part : (value == null ? "" : value).split("[,\\s]+")) {
            named.add(part.strip().toLowerCase(Locale.ROOT));
        }
        return PARTS.stream().filter(named::contains).toList();
    }

    @Override
    public void run(JobContext context) {
        String siteId = TenantContext.current().siteId();
        access.refusalFor(siteId).ifPresent(refusal -> {
            throw new IllegalStateException("AI is not available for this website (" + refusal + ")");
        });
        String source = LanguageChain.original();
        Set<String> targets = ArticleSelection.languages(context.param("languages"));
        targets.remove(source);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No language to translate into");
        }
        List<String> parts = parts(context.param("parts"));
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Nothing to translate");
        }
        Run run = new Run(context, siteId, source, targets, "all".equals(context.param("mode")));

        List<Page> pageList = parts.contains("pages") ? pages.findAll(Sort.by("sortOrder", "_id")) : List.of();
        List<Category> categoryList = parts.contains("categories") ? categories.findAll(Sort.by("_id")) : List.of();
        List<Tag> tagList = parts.contains("tags") ? tags.findAll(Sort.by("_id")) : List.of();
        List<String> menuKeys = parts.contains("menus")
                ? menus.findAll(Sort.by("_id")).stream().map(Menu::getKey).toList() : List.of();
        int social = parts.contains("social") ? 1 : 0;
        context.total((pageList.size() + categoryList.size() + tagList.size() + menuKeys.size() + social) * targets.size());
        context.log(String.join(", ", parts) + ", from " + source + " into " + String.join(", ", targets));

        try {
            pageList.forEach(run::page);
            categoryList.forEach(run::category);
            tagList.forEach(run::tag);
            menuKeys.forEach(run::menu);
            if (social == 1) {
                run.social();
            }
        } catch (Exhausted e) {
            context.log("Stopped after " + MAX_CALLS + " translations, the most one run makes. Queue it again to go on: "
                    + (run.all ? "with \"translate again\" ticked it would start over." : "what is translated now is skipped."));
        }
    }

    /** One run's state: where from, where to, and what is left of its calls. */
    private final class Run {

        final JobContext context;
        final String siteId;
        final String source;
        final Set<String> targets;
        final boolean all;
        int calls;

        Run(JobContext context, String siteId, String source, Set<String> targets, boolean all) {
            this.context = context;
            this.siteId = siteId;
            this.source = source;
            this.targets = targets;
            this.all = all;
        }

        void page(Page page) {
            context.checkpoint();
            String label = "page " + page.getSlug();
            if (!page.hasBody(source)) {
                context.log(label + ": no body in " + source + "; skipped.");
                targets.forEach(t -> context.advance());
                return;
            }
            for (String target : targets) {
                context.checkpoint();
                if (!all && page.hasBody(target)) {
                    skipped(label, target);
                    continue;
                }
                write(label, target, () -> {
                    String title = translate(source, target, page.rawTitle(source), "a page title", HtmlTranslation::field);
                    String description = translate(source, target, page.rawDescription(source), "a page summary", HtmlTranslation::field);
                    String body = translate(source, target, page.rawBody(source), "a page body in HTML", HtmlTranslation::html);
                    context.checkpoint();
                    return writer.putPage(page.getSlug(), target, title, description, body);
                });
            }
        }

        void category(Category category) {
            context.checkpoint();
            String from = category.getLang() == null || category.getLang().isBlank()
                    ? source : category.getLang().strip().toLowerCase(Locale.ROOT);
            String label = "category " + category.getId();
            for (String target : targets) {
                context.checkpoint();
                if (target.equals(from) || blank(category.getName())) {
                    context.advance();
                } else if (!all && has(category.getNames(), target)) {
                    skipped(label, target);
                } else {
                    write(label, target, () -> {
                        String name = translate(from, target, category.getName(), "the name of a section of a website", HtmlTranslation::field);
                        context.checkpoint();
                        return writer.putCategory(category.getId(), target, name);
                    });
                }
            }
        }

        void tag(Tag tag) {
            context.checkpoint();
            String label = "tag \"" + tag.getName() + "\"";
            for (String target : targets) {
                context.checkpoint();
                if (blank(tag.getName())) {
                    context.advance();
                } else if (!all && has(tag.getTranslations(), target)) {
                    skipped(label, target);
                } else {
                    write(label, target, () -> {
                        String name = translate(source, target, tag.getName(), "a tag, a word or phrase an article is filed under", HtmlTranslation::field);
                        context.checkpoint();
                        return writer.putTag(tag.getName(), target, name);
                    });
                }
            }
        }

        void menu(String key) {
            for (String target : targets) {
                context.checkpoint();
                String label = "menu " + key;
                Optional<Document> read = writer.read(Menu.class, key);
                if (read.isEmpty()) {
                    context.fail(label + " → " + target + ": the menu is gone.");
                    continue;
                }
                List<?> items = read.get().getList("items", Object.class);
                Changes changes = new Changes();
                write(label, target, () -> {
                    List<Object> rebuilt = items(items, target, changes);
                    if (changes.count == 0) {
                        return null;
                    }
                    context.checkpoint();
                    return writer.replaceIfUnchanged(Menu.class, key, single("items", items), single("items", rebuilt));
                });
            }
        }

        /** A menu's items, each label translated where wanted; groups' children likewise. */
        private List<Object> items(List<?> items, String target, Changes changes) {
            List<Object> rebuilt = new ArrayList<>();
            for (Object item : items == null ? List.of() : items) {
                if (!(item instanceof Document entry)) {
                    rebuilt.add(item);
                    continue;
                }
                Document copy = new Document(entry);
                List<?> labels = entry.getList("labels", Object.class);
                List<?> translated = label(labels, target, "a menu entry of a website", changes);
                if (translated != labels) {
                    copy.put("labels", translated);
                }
                if (entry.get("children") instanceof List<?> children) {
                    copy.put("children", items(children, target, changes));
                }
                rebuilt.add(copy);
            }
            return rebuilt;
        }

        void social() {
            for (String target : targets) {
                context.checkpoint();
                String label = "social page";
                Optional<Document> read = writer.read(SocialPage.class, SocialPage.ID);
                if (read.isEmpty()) {
                    context.log(label + ": not set up yet; skipped.");
                    context.advance();
                    continue;
                }
                Document page = read.get();
                Changes changes = new Changes();
                write(label, target, () -> {
                    // Only what changed is set, and compared: a field the document lacks stays absent.
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    for (String field : List.of("names", "bios", "latestHeadings")) {
                        List<?> list = page.getList(field, Object.class);
                        List<?> translated = label(list, target, switch (field) {
                            case "names" -> "the name on a link-in-bio page";
                            case "bios" -> "the short introduction on a link-in-bio page";
                            default -> "a heading over a list of articles";
                        }, changes);
                        if (translated != list) {
                            before.put(field, list);
                            after.put(field, translated);
                        }
                    }
                    List<?> links = page.getList("links", Object.class);
                    List<Object> rebuilt = new ArrayList<>();
                    boolean linksChanged = false;
                    for (Object link : links == null ? List.of() : links) {
                        List<?> titles = link instanceof Document entry ? entry.getList("titles", Object.class) : null;
                        List<?> translated = link instanceof Document
                                ? label(titles, target, "the title of a link button", changes) : titles;
                        if (translated != titles) {
                            Document copy = new Document((Document) link);
                            copy.put("titles", translated);
                            rebuilt.add(copy);
                            linksChanged = true;
                        } else {
                            rebuilt.add(link);
                        }
                    }
                    if (linksChanged) {
                        before.put("links", links);
                        after.put("links", rebuilt);
                    }
                    if (changes.count == 0) {
                        return null;
                    }
                    context.checkpoint();
                    return writer.replaceIfUnchanged(SocialPage.class, SocialPage.ID, before, after);
                });
            }
        }

        /**
         * A raw label list with {@code target}'s entry translated, when it is wanted and there is
         * something to translate from; otherwise the list as it was.
         */
        private List<?> label(List<?> list, String target, String what, Changes changes) {
            if (!all && TranslationWriter.textIn(list, target) != null) {
                return list;
            }
            String from = source;
            String text = TranslationWriter.textIn(list, source);
            if (text == null && !source.equals(LanguageChain.FALLBACK) && !target.equals(LanguageChain.FALLBACK)) {
                from = LanguageChain.FALLBACK;
                text = TranslationWriter.textIn(list, from);
            }
            if (text == null) {
                return list;
            }
            changes.count++;
            return TranslationWriter.withText(list, target, translate(from, target, text, what, HtmlTranslation::field));
        }

        /**
         * One item in one language: written, refused or failed, and said so. {@code work} answers
         * whether the write landed, or null when there was nothing to translate.
         */
        private void write(String label, String target, java.util.function.Supplier<Boolean> work) {
            String line = label + " → " + target;
            try {
                Boolean written = work.get();
                if (written == null) {
                    context.log(line + ": nothing to translate.");
                    context.advance();
                } else if (written) {
                    context.log(line + ": done.");
                    context.advance();
                } else {
                    context.fail(line + ": changed or removed meanwhile; run it again.");
                }
            } catch (AiGateway.AiException | TranslateArticlesHandler.Refused e) {
                context.fail(line + ": " + e.getMessage());
            }
        }

        private void skipped(String label, String target) {
            context.log(label + " → " + target + ": already translated; skipped.");
            context.advance();
        }

        /** One text's translation, checked; null for a text that is empty. */
        private String translate(String from, String target, String text, String what,
                                 BiFunction<String, String, Optional<String>> check) {
            if (blank(text)) {
                return null;
            }
            if (calls >= MAX_CALLS) {
                throw new Exhausted();
            }
            calls++;
            AiGateway.Answer answer = gateway.complete(new AiGateway.Call(TranslateArticlesHandler.FEATURE, models.modelFor(AiFeature.TRANSLATE),
                    TranslateArticlesHandler.prompt(from, target, what), text, null, "translation",
                    TranslateArticlesHandler.SCHEMA, TranslateArticlesHandler.maxTokens(text),
                    siteId, null, context.job().getId()));
            context.addCostMicros(answer.costMicros());
            String translated = answer.json().path("text").asString("");
            return check.apply(text, translated)
                    .orElseThrow(() -> new TranslateArticlesHandler.Refused("the translation of " + what + " changed its markup or its code; refused"));
        }
    }

    private static Map<String, Object> single(String field, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(field, value);
        return map;
    }

    private static boolean has(List<TextItem> items, String lang) {
        return items != null && items.stream().anyMatch(item -> item != null && lang.equalsIgnoreCase(item.getLang())
                && item.getText() != null && !item.getText().isBlank());
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** How many labels of one document were translated. */
    private static final class Changes {
        int count;
    }

    /** The run's calls are spent. */
    private static final class Exhausted extends RuntimeException {
        Exhausted() {
            super(null, null, false, false);
        }
    }
}
