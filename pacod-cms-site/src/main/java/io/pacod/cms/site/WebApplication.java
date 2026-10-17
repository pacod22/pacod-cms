package io.pacod.cms.site;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The public site: every website hosted on the platform, told apart by the request's Host.
 *
 * <p><b>The core packages are enumerated</b> rather than {@code io.pacod.cms.core} being named
 * whole, so that anything added to core later -- a controller above all -- cannot quietly appear
 * on every tenant's public host. {@code core.seed} is left out on purpose: provisioning a website
 * is the studio's job, and the site only ever serves websites that already exist.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded because this application has no
 * authentication at all. Spring Boot's response to finding no {@code UserDetailsService} is to
 * create an in-memory user and log a generated password at startup -- on the public site, on
 * every deploy. There is nothing here to sign in to; saying so is better than accepting a
 * credential nobody uses. {@code WebApplicationTests} asserts zero such beans.
 */
@Slf4j
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableMongoRepositories({"io.pacod.cms.core.repository"})
@EnableScheduling
@ComponentScan(basePackages = {
		"io.pacod.cms.core.data",
		"io.pacod.cms.core.repository",
		"io.pacod.cms.core.services",
		"io.pacod.cms.core.config",
		"io.pacod.cms.core.tenant",
		"io.pacod.cms.core.design",
		"io.pacod.cms.site"})
public class WebApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebApplication.class, args);
	}

}
