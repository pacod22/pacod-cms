package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.studio.demo.StudioDemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code --seed-demo}: gives an existing website the demo content and exits.
 *
 * <pre>
 *   java -jar pacod-cms-studio.jar --spring.main.web-application-type=none --seed-demo --site-id=acme
 * </pre>
 * {@code make seed SITE=acme} runs it in a one-off studio container. It refuses a website that
 * already has articles, or whose content language the demo is not written in, and writes nothing
 * then; a run that stopped halfway is finished by running it again ({@link DemoContent}).
 *
 * <p>Without {@code --seed-demo} this does nothing, which is every ordinary start.
 */
@Slf4j
@Component
public class SeedDemoCommand implements ApplicationRunner {

    static final String OPTION = "seed-demo";

    private final WebsiteRepository websites;
    private final StudioDemo demo;
    private final ApplicationContext context;

    public SeedDemoCommand(WebsiteRepository websites, StudioDemo demo, ApplicationContext context) {
        this.websites = websites;
        this.demo = demo;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int status;
        List<String> ids = args.getOptionValues("site-id");
        String id = ids == null || ids.isEmpty() ? null : ids.getFirst();
        Website website = id == null ? null : websites.findById(id).orElse(null);
        if (website == null) {
            log.error("--seed-demo needs --site-id naming a website that exists{}", id == null ? "" : "; there is no " + id);
            status = 2;
        } else {
            status = install(demo, website);
        }
        int exit = status;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** Writes the demo content and says what happened; the exit status, 0 when it was written. */
    static int install(StudioDemo demo, Website website) {
        try {
            StudioDemo.Outcome outcome = demo.install(website);
            DemoContent.Result content = outcome.content();
            log.info("Demo content in website {}: {} articles, {} pictures, {} sections, {} authors, {} tags, {} bookings{}",
                    website.getId(), content.articles(), content.images(), content.categories(), content.authors(),
                    content.tags(), outcome.promotions(),
                    outcome.promotionsSkipped() == null ? "" : " (no bookings: " + outcome.promotionsSkipped() + ")");
            return 0;
        } catch (DemoContent.Refused e) {
            log.error("No demo content for website {}: {}", website.getId(), e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            log.error("Demo content for website {} stopped halfway: {}. Running it again finishes it.",
                    website.getId(), e.getMessage());
            return 1;
        }
    }
}
