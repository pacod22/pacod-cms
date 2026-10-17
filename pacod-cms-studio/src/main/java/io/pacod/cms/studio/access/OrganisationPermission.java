package io.pacod.cms.studio.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What an organisation's screen needs, checked at {@code [platform, organisation:<orgId>]} with the
 * organisation taken from the handler's {@value #VARIABLE} path variable -- {@link #read} for GET
 * and HEAD, {@link #write} for every other method. A handler carrying this whose mapping has no
 * such variable is refused, never let through.
 *
 * <p>Organisation screens are platform screens (they belong to no website), so without this the
 * permission check would have nothing to check them against.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OrganisationPermission {

    String VARIABLE = "orgId";

    String read();

    /** Defaults to {@link #read}. */
    String write() default "";
}
