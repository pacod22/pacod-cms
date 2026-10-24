package io.pacod.cms.studio.ai;

import io.pacod.cms.core.control.data.Job;
import io.pacod.cms.core.control.data.JobType;
import io.pacod.cms.core.design.PageType;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.studio.jobs.JobContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TestTenantExtension binds the website test. */
class DesignSiteHandlerTest {

    private final DesignAssist assist = mock(DesignAssist.class);
    private final AiAccess access = mock(AiAccess.class);
    private final DesignSiteHandler handler = new DesignSiteHandler(assist, access);

    @BeforeEach
    void setUp() {
        when(access.refusalFor(anyString())).thenReturn(Optional.empty());
    }

    private JobContext context(Map<String, String> params) {
        Job job = new Job();
        job.setId("0123456789abcdef01234567");
        job.setSiteId("test");
        job.setType(JobType.DESIGN_SITE);
        job.setParams(new LinkedHashMap<>(params));
        return new JobContext(job, mock(SiteLiveness.class));
    }

    @Test
    @DisplayName("the theme, then every page type's layout, attributed to the person who queued it, the job as trace")
    void designsEverything() {
        when(assist.theme(any(), any(), any())).thenReturn(new DesignAssist.Outcome(4, List.of(), 120));
        when(assist.layout(any(), any(), any(), any())).thenReturn(new DesignAssist.Outcome(2, List.of("search-box"), 80));
        JobContext context = context(Map.of("brief", "a calm paper", "mode", "replace", "by", "usr_a"));

        handler.run(context);

        DesignAssist.Actor actor = new DesignAssist.Actor("usr_a", "0123456789abcdef01234567");
        verify(assist).theme("a calm paper", DesignAssist.Mode.REPLACE, actor);
        for (PageType type : PageType.values()) {
            verify(assist).layout(eq(type), contains("a calm paper"), eq(DesignAssist.Mode.REPLACE), eq(actor));
        }
        assertThat(context.failed()).isZero();
        assertThat(context.progress().done()).isEqualTo(1 + PageType.values().length);
        assertThat(context.progress().total()).isEqualTo(1 + PageType.values().length);
        assertThat(context.progress().costMicros()).isEqualTo(120 + 80L * PageType.values().length);
        assertThat(context.progress().log()).contains("theme: 4 values", "layout home: 2 sections, left out: search-box");
    }

    @Test
    @DisplayName("a step that fails is logged and counted; the others still run")
    void oneStepFails() {
        when(assist.theme(any(), any(), any())).thenThrow(new AiGateway.AiException("The answer held no value the theme could take"));
        when(assist.layout(any(), any(), any(), any())).thenReturn(new DesignAssist.Outcome(1, List.of()));
        JobContext context = context(Map.of("brief", "x", "by", "usr_a"));

        handler.run(context);

        assertThat(context.failed()).isEqualTo(1);
        assertThat(context.progress().log()).contains("FAILED theme");
        verify(assist).layout(eq(PageType.ARTICLE), any(), eq(DesignAssist.Mode.REFINE), any());
    }

    @Test
    @DisplayName("no AI here, or no description: nothing is asked")
    void refusals() {
        when(access.refusalFor(anyString())).thenReturn(Optional.of(AiRefusal.ORGANISATION_OFF));
        assertThatThrownBy(() -> handler.run(context(Map.of("brief", "x")))).isInstanceOf(IllegalStateException.class);
        when(access.refusalFor(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> handler.run(context(Map.of()))).isInstanceOf(IllegalArgumentException.class);
        verify(assist, never()).theme(any(), any(), any());
    }
}
