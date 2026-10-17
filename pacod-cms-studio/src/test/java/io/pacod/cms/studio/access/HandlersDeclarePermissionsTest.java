package io.pacod.cms.studio.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every studio handler says what it needs. {@code PermissionInterceptor} refuses one that says
 * nothing -- the safe direction, but a silent one: a screen missing its annotation answers 403 to
 * everybody and only an ERROR line says why. That is how the studio's image previews once refused
 * every signed-in person while the anonymous test passed.
 */
@SpringBootTest
@ActiveProfiles("test")
class HandlersDeclarePermissionsTest {

    private static final List<Class<? extends Annotation>> DECLARATIONS = List.of(StudioPermission.class,
            PlatformScreen.class, OrganisationPermission.class, PlatformPermission.class);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every studio handler carries a permission, or is a platform screen")
    void everyHandlerDeclaresWhatItNeeds() {
        List<String> silent = new ArrayList<>();
        int checked = 0;
        for (HandlerMethod method : mappings.getHandlerMethods().values()) {
            if (!method.getBeanType().getName().startsWith("io.pacod.cms.studio.")) {
                continue;
            }
            checked++;
            boolean declared = DECLARATIONS.stream().anyMatch(type ->
                    AnnotatedElementUtils.hasAnnotation(method.getMethod(), type)
                            || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), type));
            if (!declared) {
                silent.add(method.getShortLogMessage());
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(silent).as("handlers declaring no permission").isEmpty();
    }

    @Test
    @DisplayName("an organisation permission is only on a handler whose address names the organisation")
    void organisationPermissionHasItsVariable() {
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (AnnotatedElementUtils.hasAnnotation(method.getMethod(), OrganisationPermission.class)
                    || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), OrganisationPermission.class)) {
                assertThat(info.getPatternValues()).as(method.getShortLogMessage())
                        .allMatch(p -> p.contains("{" + OrganisationPermission.VARIABLE + "}"));
            }
        });
    }
}
