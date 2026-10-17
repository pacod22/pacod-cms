package io.pacod.cms.studio.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A screen that belongs to no website -- sign-in, the website list, the console's language,
 * health -- and so needs no website permission. It answers inside a website prefix too, and checks
 * whatever it needs itself.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PlatformScreen {
}
