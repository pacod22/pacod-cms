package io.pacod.cms.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's tab icon parses, and is in the jar.
 *
 * <p>Both halves of that fail <b>silently</b>, which is the only reason this test exists.
 *
 * <p><b>Parsing.</b> An SVG is XML, so a double hyphen inside a comment makes the whole document
 * ill-formed. Nothing notices: the resource handler still serves it, with a 200 and
 * {@code image/svg+xml}, and the browser quietly declines to render it and shows no icon. This
 * happened on the commit that added the file, and neither {@code make smoke} nor the driver's
 * status-and-content-type probe could see it. Only looking at it did.
 *
 * <p><b>Packaging.</b> It is loaded from the classpath rather than from {@code src/main/resources},
 * so a file that stopped being packaged into the jar fails here rather than at the tab.
 */
class MgmtFaviconTest {

    private static final String FAVICON = "static/mgmt-assets/favicon.svg";

    @Test
    @DisplayName("the favicon is on the classpath and is well-formed XML")
    void faviconParses() throws Exception {
        ClassPathResource resource = new ClassPathResource(FAVICON);
        assertThat(resource.exists())
                .as("%s is packaged into the jar", FAVICON)
                .isTrue();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // No network, no DTD: this is a static asset, not a document to resolve entities for.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);

        try (InputStream in = resource.getInputStream()) {
            // parse() throws SAXParseException on a stray "--" in a comment, which is exactly the
            // failure this test is here for.
            Document document = factory.newDocumentBuilder().parse(in);
            assertThat(document.getDocumentElement().getLocalName()).isEqualTo("svg");
            // A viewBox rather than fixed width/height: the browser asks for it at 16px and at 32.
            assertThat(document.getDocumentElement().getAttribute("viewBox")).isNotBlank();
        }
    }
}
