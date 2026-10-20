package io.pacod.cms.studio.pdf;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.DomainRepository;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.PdfFormat;
import io.pacod.cms.core.data.PdfMode;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.design.PrintTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What the PDF jobs share: the website's address to render at, the site's print pages rendered
 * through Gotenberg, the options built from a website's {@link PdfSettings}, and whole-file writes.
 *
 * <p>Gotenberg reaches the site on the compose network ({@code pacod.pdf.site-url},
 * {@code http://cms-site:8443}), where no host names a website, so every request of a render carries
 * {@code X-Forwarded-Host} -- one of the website's own hosts, which the site resolves exactly as
 * Caddy's forwarded host -- and an {@code X-Pacod-Print} token that keeps it uncounted and opens
 * the issue's front page. <b>A token is minted for every render</b>: it lasts five minutes, and an
 * issue job renders for longer than that.
 */
@Component
public class PdfRenderer {

    private final GotenbergClient gotenberg;
    private final PrintTokens tokens;
    private final DomainRepository domains;
    private final WebsiteRepository websites;
    private final String siteUrl;

    public PdfRenderer(GotenbergClient gotenberg, PrintTokens tokens, DomainRepository domains,
                       WebsiteRepository websites,
                       @Value("${pacod.pdf.site-url:http://cms-site:8443}") String siteUrl) {
        this.gotenberg = gotenberg;
        this.tokens = tokens;
        this.domains = domains;
        this.websites = websites;
        this.siteUrl = siteUrl.strip().replaceAll("/+$", "");
    }

    /** The website's primary host, else any. */
    public Optional<String> host(String siteId) {
        return domains.findByWebsiteId(siteId).stream()
                .sorted(Comparator.comparing(Domain::isPrimary).reversed())
                .map(Domain::getHost).findFirst();
    }

    /** The website's name, what a journal without a name of its own is called; its id if unreadable. */
    public String siteName(String siteId) {
        return websites.findById(siteId).map(Website::getName).filter(n -> !n.isBlank()).orElse(siteId);
    }

    /** One article's print view in a language, format and mode. */
    public byte[] article(String siteId, String host, int articleId, String lang, PdfFormat format, PdfMode mode,
                          GotenbergClient.RenderOptions options) {
        return render(siteId, host, "/article-print/" + articleId + "?lang=" + lang
                + "&format=" + format.param() + "&mode=" + mode.param(), options);
    }

    /**
     * An issue's front matter -- cover, editorial, contents -- with each article's first page as
     * the contents should print it: {@code pages.get(i)} for {@code articleIds.get(i)}.
     */
    public byte[] issueFront(String siteId, String host, int issueId, String lang, List<Integer> articleIds,
                             List<Integer> pages, GotenbergClient.RenderOptions options) {
        return render(siteId, host, "/issue-print/" + issueId + "/front?lang=" + lang
                + "&ids=" + join(articleIds) + "&pages=" + join(pages), options);
    }

    private byte[] render(String siteId, String host, String path, GotenbergClient.RenderOptions options) {
        return gotenberg.render(siteUrl + path, siteUrl,
                Map.of("X-Forwarded-Host", host, PrintTokens.HEADER, tokens.mint(siteId)), options);
    }

    /**
     * The render options of a website's settings, with room in the margins for a header or footer:
     * Chromium prints them inside the margin, and a narrow one would clip them.
     */
    public static GotenbergClient.RenderOptions options(PdfSettings settings, String header, String footer) {
        double margin = settings.marginInches();
        double roomy = Math.max(margin, 0.75);
        return new GotenbergClient.RenderOptions(settings.paper(),
                header == null ? margin : roomy, footer == null ? margin : roomy, margin, header, footer);
    }

    /** {@link #options} with the bottom margin kept for page numbers stamped afterwards. */
    public static GotenbergClient.RenderOptions withBottomRoom(GotenbergClient.RenderOptions options) {
        return new GotenbergClient.RenderOptions(options.paper(), options.marginTop(),
                Math.max(options.marginBottom(), 0.75), options.marginSide(), options.headerHtml(), options.footerHtml());
    }

    /** {@code bytes} into {@code file} whole: a temporary file beside it, then an atomic move. */
    public static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".pdf-", ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** {@code source} copied to {@code file} whole, as {@link #write} writes. */
    public static void copy(Path source, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".pdf-", ".part");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String join(List<Integer> numbers) {
        return numbers.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
