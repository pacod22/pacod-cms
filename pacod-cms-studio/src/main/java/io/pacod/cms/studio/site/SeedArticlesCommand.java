package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.DirectorySeed;
import io.pacod.cms.studio.demo.StudioDemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code --seed-articles}: adds some of a folder seed's articles to a website that already has
 * content, writes nothing else, and exits.
 *
 * <pre>
 *   java -jar pacod-cms-studio.jar --spring.main.web-application-type=none --seed-articles --site-id=acme \
 *       --seed-dir=/seed --articles=14,15,16 [--apply]
 * </pre>
 * {@code make seed-articles SITE=acme SEED_DIR=… ARTICLES=14,15,16 [APPLY=1]} runs it in a one-off
 * studio container. Without {@code --apply} it only says what it would write. Where
 * {@code --seed-demo} fills an empty website, this is for the posts a blog kept as a seed gains after
 * its website went live: see {@link DemoContent#addArticles} for what it writes and what it refuses.
 *
 * <p>Exit status: 0 when written, or nothing needed writing, or on a dry run that found no problem;
 * 1 when refused; 2 for a wrong command line.
 */
@Slf4j
@Component
public class SeedArticlesCommand implements ApplicationRunner {

    static final String OPTION = "seed-articles";

    private final WebsiteRepository websites;
    private final StudioDemo demo;
    private final ApplicationContext context;

    public SeedArticlesCommand(WebsiteRepository websites, StudioDemo demo, ApplicationContext context) {
        this.websites = websites;
        this.demo = demo;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int status = run(args, websites, demo);
        System.exit(SpringApplication.exit(context, () -> status));
    }

    static int run(ApplicationArguments args, WebsiteRepository websites, StudioDemo demo) {
        String id = SeedDemoCommand.value(args, "site-id", null);
        String folder = SeedDemoCommand.value(args, SeedDemoCommand.SEED_DIR, null);
        List<Integer> ids;
        try {
            ids = articles(SeedDemoCommand.value(args, "articles", ""));
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            return 2;
        }
        if (id == null || folder == null || ids.isEmpty()) {
            log.error("--seed-articles needs --site-id, --seed-dir and --articles=<n,n,…>");
            return 2;
        }
        Website website = websites.findById(id).orElse(null);
        if (website == null) {
            log.error("There is no website {}", id);
            return 2;
        }
        DirectorySeed source;
        try {
            source = new DirectorySeed(Path.of(folder));
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            return 2;
        }
        boolean apply = args.containsOption("apply");
        try {
            DemoContent.Addition addition = demo.addArticles(source, website, ids, apply);
            log.info("Website {}{}:", website.getId(), addition.applied() ? "" : apply ? " (nothing to write)" : " (dry run: nothing written)");
            log.info("  articles {} {}", addition.applied() ? "added:" : "to add:", addition.added());
            log.info("  articles already there, left alone: {}", addition.present());
            log.info("  pictures it has, reused: {}", addition.picturesKept());
            log.info("  pictures {} {}", addition.applied() ? "stored:" : "to store:", addition.picturesNew());
            log.info("  tags {} {}", addition.applied() ? "created:" : "to create:", addition.tagsNew());
            if (!apply && !addition.added().isEmpty()) {
                log.info("Run it again with --apply (make … APPLY=1) to write them");
            }
            return 0;
        } catch (DemoContent.Refused e) {
            log.error("No articles added to website {}: {}", website.getId(), e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            log.error("Adding articles to website {} stopped: {}. Running it again finishes it.",
                    website.getId(), e.getMessage());
            return 1;
        }
    }

    /** {@code 14,15,16} as numbers; empty for a blank value. */
    static List<Integer> articles(String value) {
        List<Integer> ids = new ArrayList<>();
        for (String part : value.split("[,\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(part));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--articles takes article numbers, like 14,15,16; not " + part);
            }
        }
        return ids;
    }
}
