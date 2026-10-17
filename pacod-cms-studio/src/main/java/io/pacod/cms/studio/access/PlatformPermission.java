package io.pacod.cms.studio.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a screen about the whole installation needs, checked at {@code [platform]} -- in practice
 * {@code platform.manage}, which only platform admins hold.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PlatformPermission {

    String read();

    /** Defaults to {@link #read}. */
    String write() default "";
}
