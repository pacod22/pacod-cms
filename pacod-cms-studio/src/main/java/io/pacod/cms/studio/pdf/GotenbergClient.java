package io.pacod.cms.studio.pdf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gotenberg's Chromium route, {@code POST /forms/chromium/convert/url}: a page of the public site
 * in, a PDF out. {@code pacod.pdf.gotenberg-url} ({@code http://gotenberg:3000} in compose; blank
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
     * The page at {@code pageUrl} as a PDF, every request of the render carrying {@code headers}
     * where its address starts with {@code origin}.
     *
     * @throws PdfException with Gotenberg's own words when it refuses or fails
     */
    public byte[] render(String pageUrl, String origin, Map<String, String> headers) {
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
        // A4, with the margins of a printed article.
        form.add("paperWidth", "8.27");
        form.add("paperHeight", "11.7");
        form.add("marginTop", "0.6");
        form.add("marginBottom", "0.6");
        form.add("marginLeft", "0.6");
        form.add("marginRight", "0.6");
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
