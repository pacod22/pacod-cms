package io.pacod.cms.studio.jobs;

import io.pacod.cms.core.config.MongoConfig;
import io.pacod.cms.core.jobs.JobQueue;
import io.pacod.cms.core.tenant.SiteLiveness;
import io.pacod.cms.core.tenant.TenantDirectory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

/**
 * The queue (every studio has one, to enqueue) and the runner ({@code pacod.jobs.runner.enabled},
 * on by default; the test profiles and the {@code --create-site} run switch it off).
 */
@Configuration
public class JobsConfig {

    @Bean
    public JobQueue jobQueue(@Qualifier(MongoConfig.CONTROL_PLANE_TEMPLATE) MongoTemplate controlPlane) {
        return new JobQueue(controlPlane);
    }

    @Bean
    @ConditionalOnProperty(name = "pacod.jobs.runner.enabled", havingValue = "true", matchIfMissing = true)
    public JobRunner jobRunner(JobQueue queue, List<JobHandler> handlers, TenantDirectory directory, SiteLiveness liveness,
                               @Value("${pacod.jobs.workers:2}") int workers,
                               @Value("${pacod.jobs.lease:60s}") Duration lease,
                               @Value("${pacod.jobs.heartbeat:5s}") Duration heartbeat,
                               @Value("${pacod.jobs.poll:2s}") Duration poll,
                               @Value("${pacod.jobs.per-organisation:1}") int perOrganisation) {
        return new JobRunner(queue, handlers, directory, liveness, workers, lease, heartbeat, poll, perOrganisation);
    }
}
