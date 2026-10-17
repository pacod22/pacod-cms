package io.pacod.cms.studio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * The studio: where websites are created, and where each one's content, advertising and
 * statistics are edited.
 *
 * <p>It autowires the MongoDB repositories directly, exactly like the public site does -- there is
 * no HTTP client between the two. Which website's database a repository reaches is decided by the
 * website bound for the request ({@code /sites/{siteId}/...}); see {@code StudioSiteFilter}.
 *
 * <p><b>The core packages are enumerated</b> rather than {@code io.pacod.cms.core} being named
 * whole, so that adding a controller to core cannot quietly change what this application exposes.
 * {@code core.seed} is scanned here and only here: provisioning a website runs its seed steps,
 * and the studio is where websites are provisioned.
 *
 * <p>No {@code @EnableScheduling}: the scheduled work in this system (the counter flushes, the
 * nightly tag rebuild) belongs to the public site.
 */
@Slf4j
@SpringBootApplication
@EnableMongoRepositories({"io.pacod.cms.core.repository"})
@ComponentScan(basePackages = {
		"io.pacod.cms.core.data",
		"io.pacod.cms.core.repository",
		"io.pacod.cms.core.services",
		"io.pacod.cms.core.config",
		"io.pacod.cms.core.tenant",
		"io.pacod.cms.core.design",
		"io.pacod.cms.core.seed",
		"io.pacod.cms.studio"})
public class MgmtApplication {

	public static void main(String[] args) {
		SpringApplication.run(MgmtApplication.class, args);
	}

}
