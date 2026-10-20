package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.data.PaperSize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gotenberg's Chromium route, {@code POST /forms/chromium/convert/url}: a page of the public site
 * in, a PDF out. {@code pacod.pdf.gotenberg-url} ({@code http://cms-gotenberg:3000} in compose; blank
 * switches PDF export off).
 *
 * <p>Gotenberg is the one thing here that fetches what an author wrote -- an article body accepts
 * any markup -- so compose starts it with a Chromium allow-list of the site's internal origin and
 * nothing else, and publishes no port. Each extra header is scoped to that origin too
 * ({@code value;scope=<regex>}, Gotenberg 8.12+), so the print token never leaves it even where
 * the allow-list were wider.
 */
@Component
public class GotenbergClient {

    private final String url;
    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public GotenbergClient(@Value("${pacod.pdf.gotenberg-url:}") String url,
                           @Value("${pacod.pdf.timeout:120s}") Duration timeout) {
        this.url = url == null ? "" : url.strip().replaceAll("/+$", "");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    /**
     * The paper, the margins in inches, and an optional running header and footer: whole HTML
     * documents Gotenberg prints into the top and bottom margins, with Chromium's
     * {@code pageNumber}/{@code totalPages} classes filled in. Header and footer load nothing --
     * Chromium renders them apart from the page -- so they carry their own styles ({@link PdfHeaders}).
     */
    public record RenderOptions(PaperSize paper, double marginTop, double marginBottom, double marginSide,
                                String headerHtml, String footerHtml) {

        /** A4, with the margins of a printed article, no header or footer: what every render was. */
        public static final RenderOptions DEFAULT = new RenderOptions(PaperSize.A4, 0.6, 0.6, 0.6, null, null);
    }

    /**
     * The page at {@code pageUrl} as a PDF, every request of the render carrying {@code headers}
     * where its address starts with {@code origin}.
     *
     * @throws PdfException with Gotenberg's own words when it refuses or fails
     */
    public byte[] render(String pageUrl, String origin, Map<String, String> headers) {
        return render(pageUrl, origin, headers, RenderOptions.DEFAULT);
    }

    /** {@link #render(String, String, Map)} on the given paper, margins, header and footer. */
    public byte[] render(String pageUrl, String origin, Map<String, String> headers, RenderOptions options) {
        if (!enabled()) {
            throw new PdfException("PDF export is off: pacod.pdf.gotenberg-url is blank");
        }
        String scope = "^" + Pattern.quote(origin) + "/.*";
        Map<String, String> scoped = new LinkedHashMap<>();
        headers.forEach((name, value) -> scoped.put(name, value + ";scope=" + scope));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("url", pageUrl);
        form.add("extraHttpHeaders", toJson(scoped));
        form.add("emulatedMediaType", "print");
        form.add("printBackground", "true");
        form.add("paperWidth", inches(options.paper().widthInches()));
        form.add("paperHeight", inches(options.paper().heightInches()));
        form.add("marginTop", inches(options.marginTop()));
        form.add("marginBottom", inches(options.marginBottom()));
        form.add("marginLeft", inches(options.marginSide()));
        form.add("marginRight", inches(options.marginSide()));
        // Gotenberg's convention: the header and footer are files of the form, by these names.
        if (options.headerHtml() != null) {
            form.add("files", htmlFile("header.html", options.headerHtml()));
        }
        if (options.footerHtml() != null) {
            form.add("files", htmlFile("footer.html", options.footerHtml()));
        }
        try {
            byte[] pdf = client.post().uri(url + "/forms/chromium/convert/url")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                    .retrieve().body(byte[].class);
            if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                throw new PdfException("Gotenberg answered with something that is not a PDF");
            }
            return pdf;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            throw new PdfException("Gotenberg answered " + e.getStatusCode().value()
                    + (body.isBlank() ? "" : ": " + body.strip().lines().findFirst().orElse("")));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new PdfException("Gotenberg cannot be reached: " + e.getMessage());
        }
    }

    /** Two decimals at most, never a locale's comma: {@code 0,6} is no number to Gotenberg. */
    static String inches(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static HttpEntity<ByteArrayResource> htmlFile(String name, String html) {
        ByteArrayResource resource = new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.TEXT_HTML);
        part.setContentDispositionFormData("files", name);
        return new HttpEntity<>(resource, part);
    }

    private String toJson(Map<String, String> headers) {
        return json.writeValueAsString(headers);
    }

    /** A render that did not produce a PDF, in words for the job's log. */
    public static class PdfException extends RuntimeException {
        public PdfException(String message) {
            super(message);
        }
    }
}
