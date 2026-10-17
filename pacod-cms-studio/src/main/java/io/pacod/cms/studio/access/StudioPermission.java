package io.pacod.cms.studio.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a website screen needs: {@link #read} to look (GET, HEAD), {@link #write} to change
 * anything (every other method). Permissions are the manifest's local names -- {@code content.write},
 * not {@code cms.content.write} -- checked at the website the request is for:
 * {@code [platform, organisation:<org>, cms.website:<id>]}.
 *
 * <p>On a controller class it covers every handler; on a method it replaces the class's. A
 * handler inside a website with neither this nor {@link PlatformScreen} is refused: a screen
 * nobody thought about is not a screen everybody may use.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface StudioPermission {

    String read();

    /** Defaults to {@link #read}: a screen that changes nothing needs nothing more to be posted to. */
    String write() default "";
}
