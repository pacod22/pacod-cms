package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.jobs.JobContext;
import io.pacod.cms.studio.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code DESIGN_SITE}: the website's theme and then every page type's layout drafted from one
 * brief, through {@link DesignAssist} -- the same schema, checks and draft-only writes as the
 * design screens' own assist, one call per step. A job because it is seven calls of up to a minute
 * each.
 *
 * <ul>
 *   <li><b>Each step stands alone.</b> One the model gets wrong -- nothing usable, or the gateway
 *       refusing -- is logged as failed and the others still run; the job then ends {@code FAILED}
 *       with what it did make kept.</li>
 *   <li><b>Safe to run again from the start</b>: each step replaces its draft whole, and the
 *       draft kept for undo is the one from before the job's first attempt
 *       ({@link io.pacod.cms.studio.design.DesignLayouts#assistDraft}).</li>
 *   <li><b>Attributed to the person who queued it</b>, whose account id the job carries
 *       ({@code by}; the job's own {@code queuedBy} is a display name, not an id the gateway or
 *       {@code updatedBy} takes), with the job as the gateway's trace.</li>
 * </ul>
 */
@Component
public class DesignSiteHandler implements JobHandler {

    /** The longest brief a job carries: with the page named before it, within {@link DesignAssist}'s cap. */
    public static final int MAX_BRIEF = 900;

    private final DesignAssist assist;
    private final AiAccess access;

    public DesignSiteHandler(DesignAssist assist, AiAccess access) {
        this.assist = assist;
        this.access = access;
    }

    @Override
    public JobType type() {
        return JobType.DESIGN_SITE;
    }

    @Override
    public void run(JobContext context) {
        String siteId = TenantContext.current().siteId();
        access.refusalFor(siteId).ifPresent(refusal -> {
            throw new IllegalStateException("AI is not available for this website (" + refusal + ")");
        });
        String brief = context.param("brief");
        if (brief == null) {
            throw new IllegalArgumentException("No description to design from");
        }
        DesignAssist.Mode mode = DesignAssist.Mode.of(context.param("mode"));
        DesignAssist.Actor actor = new DesignAssist.Actor(context.param("by"), context.job().getId());
        PageType[] pageTypes = PageType.values();
        context.total(1 + pageTypes.length);
        context.log((mode == DesignAssist.Mode.REFINE ? "Refining" : "Replacing") + " the theme and "
                + pageTypes.length + " layouts");

        context.checkpoint();
        step(context, "theme", "values", () -> assist.theme(brief, mode, actor));
        for (PageType pageType : pageTypes) {
            context.checkpoint();
            String name = pageType.name().toLowerCase(Locale.ROOT);
            step(context, "layout " + name, "sections", () -> assist.layout(pageType,
                    "For the " + name + " page of a website described as: " + brief, mode, actor));
        }
    }

    private static void step(JobContext context, String what, String unit, Supplier<DesignAssist.Outcome> work) {
        try {
            DesignAssist.Outcome outcome = work.get();
            context.addCostMicros(outcome.costMicros());
            context.log(what + ": " + outcome.applied() + " " + unit
                    + (outcome.refused().isEmpty() ? "" : ", left out: " + String.join(", ", outcome.refused())));
            context.advance();
        } catch (AiGateway.AiException | MgmtOperationException e) {
            context.fail(what + ": " + e.getMessage());
        }
    }
}
