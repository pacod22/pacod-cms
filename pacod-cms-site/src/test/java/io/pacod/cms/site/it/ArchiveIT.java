package io.pacod.cms.site.it;

import io.pacod.cms.core.archive.ArchiveException;
import io.pacod.cms.core.archive.SiteArchives;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.repository.AuthorRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import io.pacod.cms.core.tenant.SiteProvisioner;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A website exported and restored as a new one, against real MongoDB: every document comes back
 * byte for byte with its references resolving in the new database, its files in the new folder,
 * nothing of its traffic, no seeded row doubling a restored one, the source's absolute address
 * gone -- and the source untouched. An archive with a reference into another database is refused
 * before anything is created.
 */
class ArchiveIT extends TwoWebsitesIT {

    @Autowired
    AuthorRepository authors;

    @Autowired
    ImageMediaRepository images;

    @Autowired
    WebsiteRepository websites;

    SiteArchives archives;
    Tenant rho;

    /** A website of its own: the others' alpha and beta stay as every other IT expects them. */
    @BeforeAll
    void content() throws IOException {
        archives = new SiteArchives(siteTemplate, websites, STORAGE.toString(), 64L * 1024 * 1024);
        if (directory.bySite("rho").isEmpty()) {
            provisioner.create(new SiteProvisioner.NewWebsite("org_it", null, "rho", "Rho Daily", "en", "UTC",
                    List.of("rho.test"), "usr_it"));
        }
        rho = directory.bySite("rho").orElseThrow();
        TenantContext.runAs(rho, () -> {
            Author author = new Author();
            author.setId(7);
            author.setFirstname("Ada");
            author.setLastname("Lumen");
            authors.save(author);
            ImageMedia image = new ImageMedia();
            image.setId(3);
            image.setFilename("cover.jpg");
            image.setCaption("A cover");
            images.save(image);
            Article article = new Article();
            article.setId(1);
            article.setTitle("Rho -- first story");
            article.setText("<p>Only in Rho.</p>");
            article.setStatus(io.pacod.cms.core.data.ArticleStatus.PUBLISHED);
            article.setDate(java.time.LocalDateTime.now().minusDays(1));
            article.setCounter(0);
            article.setAuthors(List.of(author));
            article.setImages(List.of(image));
            articles.save(article);
            SiteSettings site = settings.findById(SiteSettings.ID).orElseGet(SiteSettings::new);
            site.setBaseUrl("https://rho.example.com");
            site.setNames(io.pacod.cms.core.data.TextItems.put(List.of(), "en", "Rho Daily"));
            settings.save(site);
            siteTemplate.getDb().getCollection("siteMetricDay").insertOne(new Document("_id", "2026-01-01").append("views", 5));
        });
        Path picture = STORAGE.resolve("sites/rho/assets/img/articles/cover.jpg");
        Files.createDirectories(picture.getParent());
        Files.write(picture, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
        Path banner = STORAGE.resolve("sites/rho/resources/banners/promo/index.html");
        Files.createDirectories(banner.getParent());
        Files.writeString(banner, "<p>promo</p>");
        Files.writeString(STORAGE.resolve("sites/rho/resources/banners/promo/.DS_Store"), "junk");
    }

    @Test
    @DisplayName("export, then restore as a new website: content, references and files come back; traffic and the old address do not")
    void roundTrip() throws IOException {
        Website source = websites.findById("rho").orElseThrow();
        Path zip = Files.createTempFile("rho", ".zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            archives.exportSite(source, out);
        }
        SiteArchives.Archive archive = archives.open(zip);
        assertThat(archive.organisation()).isFalse();
        SiteArchives.SiteEntry entry = archive.sites().getFirst();
        assertThat(entry.sourceId()).isEqualTo("rho");
        assertThat(entry.name()).isEqualTo("Rho Daily");
        assertThat(entry.collections()).containsKeys("article", "author", "image", "siteSettings")
                .doesNotContainKey("siteMetricDay");
        assertThat(entry.files()).isEqualTo(2);

        provisioner.create(new SiteProvisioner.NewWebsite("org_it", null, "rho-copy", "Rho Copy", entry.defaultLanguage(),
                entry.timezone(), List.of("rho-copy.test"), "usr_it"), created -> archives.fill(archive, entry));
        Tenant copy = directory.bySite("rho-copy").orElseThrow();

        long sourceLanguages = TenantContext.callAs(rho, () -> siteTemplate.getDb().getCollection("language").countDocuments());
        TenantContext.runAs(copy, () -> {
            Article article = articles.findById(1).orElseThrow();
            assertThat(article.getTitle()).isEqualTo("Rho -- first story");
            assertThat(article.getAuthors()).extracting(Author::getLastname).containsExactly("Lumen");
            assertThat(article.getImages()).extracting(ImageMedia::getFilename).containsExactly("cover.jpg");
            assertThat(settings.findById(SiteSettings.ID).orElseThrow().getBaseUrl()).isNull();
            assertThat(siteTemplate.getDb().getCollection("siteMetricDay").countDocuments()).isZero();
            // (The site's context runs no seed steps -- only the studio's does -- so nothing was added.)
            assertThat(siteTemplate.getDb().getCollection("language").countDocuments()).isEqualTo(sourceLanguages);
        });
        assertThat(STORAGE.resolve("sites/rho-copy/assets/img/articles/cover.jpg")).exists();
        assertThat(STORAGE.resolve("sites/rho-copy/resources/banners/promo/index.html")).hasContent("<p>promo</p>");
        assertThat(STORAGE.resolve("sites/rho-copy/resources/banners/promo/.DS_Store")).doesNotExist();
        assertThat(get("rho-copy.test", "/article/1").body()).contains("Only in Rho.");
        // Readers see the settings' name, which came with the archive (the studio's restore writes
        // the one typed, if any, PortalRestore.rename) -- not the control plane's "Rho Copy".
        assertThat(get("rho-copy.test", "/").body()).contains("Rho Daily");

        // The source is as it was.
        TenantContext.runAs(rho, () -> assertThat(settings.findById(SiteSettings.ID).orElseThrow().getBaseUrl())
                .isEqualTo("https://rho.example.com"));
        Files.deleteIfExists(zip);
    }

    @Test
    @DisplayName("an archive whose document points into another database is refused before anything exists")
    void foreignReferenceIsRefused() throws IOException {
        BsonDocument article = new BsonDocument("_id", new BsonInt32(1))
                .append("authors", new org.bson.BsonArray(List.of(new BsonDocument("$ref", new BsonString("author"))
                        .append("$id", new BsonInt32(1)).append("$db", new BsonString("site_beta")))));
        Path zip = Files.createTempFile("evil", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("db/article.bson"));
            out.write(bytes(article));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(SiteArchives.SITE_MANIFEST));
            out.write("""
                    {"format":"pacod-cms-site","version":1,"website":{"id":"evil","name":"Evil","defaultLanguage":"en","timezone":"UTC"}}
                    """.getBytes());
            out.closeEntry();
        }
        assertThatThrownBy(() -> archives.open(zip)).isInstanceOf(ArchiveException.class)
                .extracting(e -> ((ArchiveException) e).code()).isEqualTo("portal.archive.error.value");
        assertThat(websites.existsById("evil")).isFalse();
        Files.deleteIfExists(zip);
    }

    /** Encoded as it is, never through JSON, which would read {$ref, $id} as a pointer. */
    private static byte[] bytes(BsonDocument document) {
        RawBsonDocument raw = new RawBsonDocument(document, new org.bson.codecs.BsonDocumentCodec());
        ByteBuffer buffer = raw.getByteBuffer().asNIO();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
