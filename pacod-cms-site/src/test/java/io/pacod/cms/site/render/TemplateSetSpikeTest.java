package io.pacod.cms.site.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two questions the composer rests on, asked without it: can a page render sections whose
 * template and fragment names are data, in a web context (where Thymeleaf 3.1 restricts
 * expressions), and can two template sets render the same view name side by side?
 *
 * <p>Each set is its own {@code SpringTemplateEngine} over its own folder; a section is a
 * (template, fragment, settings) triple from a registry, never from request data.
 */
class TemplateSetSpikeTest {

    record Section(String template, String fragment, Map<String, Object> settings) {
    }

    private static SpringTemplateEngine engine(String set) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("spike/" + set + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setEnableSpringELCompiler(true);
        return engine;
    }

    private static String render(SpringTemplateEngine engine, List<Section> sections) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", "/");
        // A request parameter naming another fragment must not be able to steer anything.
        request.setParameter("fragment", "articleList");
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("sections", sections);
        return engine.process("page", context);
    }

    private static final List<Section> PAGE = List.of(
            new Section("sections", "richText", Map.of("html", "<p>Hello</p>")),
            new Section("sections", "articleList", Map.of("heading", "Latest")));

    @Test
    @DisplayName("a section's template and fragment names can be data, in a web context")
    void computedFragmentsRender() {
        String html = render(engine("editorial"), PAGE);
        assertThat(html).contains("class=\"ed-rich\"", "<p>Hello</p>", "class=\"ed-list\"", "Latest");
    }

    @Test
    @DisplayName("two sets render the same view name, interleaved on many threads, each with its own markup")
    void setsStayApartUnderLoad() throws Exception {
        SpringTemplateEngine editorial = engine("editorial");
        SpringTemplateEngine minimal = engine("minimal");
        List<Section> rich = List.of(new Section("sections", "richText", Map.of("html", "<p>x</p>")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                boolean even = i % 2 == 0;
                results.add(pool.submit(() -> {
                    String html = render(even ? editorial : minimal, rich);
                    return even ? html.contains("ed-rich") && !html.contains("min-rich")
                            : html.contains("min-rich") && !html.contains("ed-rich");
                }));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a set that does not implement a section type fails loudly, which the renderer must catch")
    void missingFragmentIsAnError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render(engine("minimal"), PAGE))
                .isInstanceOf(org.thymeleaf.exceptions.TemplateProcessingException.class);
    }
}
