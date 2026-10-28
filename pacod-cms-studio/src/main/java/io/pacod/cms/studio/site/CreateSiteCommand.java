package io.pacod.cms.studio.site;

import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.design.TemplateSetCatalog;
import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.SeedSource;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.studio.demo.StudioDemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * {@code --create-site}: creates one website from the command line and exits.
 *
 * <pre>
 *   java -jar pacod-cms-studio.jar --spring.main.web-application-type=none \
 *       --create-site --site-id=acme --site-name="Acme News" --hosts=acme.localhost [--language=de] \
 *       [--org-id=org_... | --org-name="Acme Ltd"] [--template-set=minimal] [--demo[=space] | --seed-dir=/seed]
 * </pre>
 * {@code --org-id} adds the website to an organisation that exists; without it a new organisation
 * is created, named {@code --org-name} (else the website's name), with a generated id.
 *
 * {@code --template-set} must name a set this installation has (else it is refused before anything
 * is created); without it the website renders with the demo's own set when {@code --demo} names a
 * demo designed for one (the space demo: magazine), else with the default set.
 *
 * {@code --demo} starts it with the demo content ({@link StudioDemo}): the journal, or with
 * {@code --demo=space} the space travels; a content language the demo is not written in, or a demo
 * that does not exist, is refused before the website is created. {@code --seed-dir} starts it with
the seed in that folder instead ({@link io.pacod.cms.core.seed.DirectorySeed}), on the template set
its layouts are for unless {@code --template-set} names another; a seed with any problem
({@link io.pacod.cms.core.seed.SeedRules}) is refused, every problem listed, before anything is created.
 *
 * {@code make site-create SLUG=… NAME=… HOSTS=… [TEMPLATE=minimal] [DEMO=1|space | SEED_DIR=…]} runs it in a one-off studio container. It goes
 * through {@link SiteProvisioner#create} -- the same path the portal takes -- so the website
 * gets its indexes and its seed content and is active when the command returns.
 *
 * <p>Without {@code --create-site} this does nothing, which is every ordinary start.
 */
@Slf4j
@Component
public class CreateSiteCommand implements ApplicationRunner {

    static final String OPTION = "create-site";

    static final String DEMO = "demo";

    private final SiteProvisioner provisioner;
    private final StudioDemo demo;
    private final TemplateSetCatalog catalog;
    private final ApplicationContext context;

    public CreateSiteCommand(SiteProvisioner provisioner, StudioDemo demo, TemplateSetCatalog catalog,
                             ApplicationContext context) {
        this.provisioner = provisioner;
        this.demo = demo;
        this.catalog = catalog;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int status = 0;
        try {
            boolean withDemo = args.containsOption(DEMO) || args.containsOption(SeedDemoCommand.SEED_DIR);
            SeedSource which = withDemo ? SeedDemoCommand.source(args).orElseThrow(() -> new IllegalArgumentException(
                    "--demo names no demo; there are " + Arrays.stream(DemoContent.Demo.values()).map(DemoContent.Demo::id).toList()))
                    : null;
            SiteProvisioner.NewWebsite request = forSet(request(args),
                    which == null ? null : which.templateSet() != null ? which.templateSet() : demo.seed(which).getLayoutSet());
            if (request.templateSet() != null && catalog.find(request.templateSet()).isEmpty()) {
                throw new IllegalArgumentException("No template set " + request.templateSet() + "; this installation has "
                        + catalog.all().stream().map(m -> m.id()).toList());
            }
            if (withDemo && !demo.supports(which, request.defaultLanguage())) {
                throw new IllegalArgumentException("Demo content is written in " + demo.languages(which)
                        + ", not in " + request.defaultLanguage());
            }
            if (which != null && which.external()) {
                List<String> problems = demo.check(which, request.defaultLanguage());
                if (!problems.isEmpty()) {
                    throw new IllegalArgumentException("The seed of " + which.id() + " has " + problems.size()
                            + " problem(s):\n  " + String.join("\n  ", problems));
                }
            }
            Website created = provisioner.create(request);
            log.info("Created website {} ('{}'), database {}", created.getId(), created.getName(), created.getDbName());
            if (withDemo) {
                status = SeedDemoCommand.install(demo, which, created);
            }
        } catch (RuntimeException e) {
            log.error("Could not create the website: {}", e.getMessage());
            status = 1;
        }
        int exit = status;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    static SiteProvisioner.NewWebsite request(ApplicationArguments args) {
        String id = required(args, "site-id");
        String name = value(args, "site-name", id);
        List<String> hosts = Arrays.stream(required(args, "hosts").split(","))
                .map(String::strip).filter(h -> !h.isEmpty()).toList();
        String orgId = value(args, "org-id", null);
        return new SiteProvisioner.NewWebsite(orgId, value(args, "org-name", name), id, name,
                value(args, "language", "en"), value(args, "timezone", "UTC"), hosts, null,
                value(args, "template-set", null));
    }

    /**
     * The request on the seed's own template set when it names none: {@code --demo=space} alone
     * makes the magazine website its layouts were designed for, and so does a folder's seed whose
     * layouts are for the magazine set. An explicit {@code --template-set} wins.
     */
    static SiteProvisioner.NewWebsite forSet(SiteProvisioner.NewWebsite request, String seedSet) {
        if (request.templateSet() != null || seedSet == null) {
            return request;
        }
        return new SiteProvisioner.NewWebsite(request.organisationId(), request.organisationName(), request.siteId(),
                request.name(), request.defaultLanguage(), request.timezone(), request.hosts(), request.createdBy(),
                seedSet);
    }

    private static String required(ApplicationArguments args, String option) {
        String value = value(args, option, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + option + " is required");
        }
        return value;
    }

    private static String value(ApplicationArguments args, String option, String fallback) {
        List<String> values = args.getOptionValues(option);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }
}
