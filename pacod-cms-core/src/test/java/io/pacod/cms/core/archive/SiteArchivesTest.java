package io.pacod.cms.core.archive;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonJavaScript;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an archive may hold, without a database: the real round trip is ArchiveIT's. */
class SiteArchivesTest {

    private static final String MANIFEST = """
            {"format":"pacod-cms-site","version":1,"website":{"id":"acme","name":"Acme","defaultLanguage":"en","timezone":"UTC"}}
            """;

    @TempDir
    Path dir;

    private final SiteArchives archives = new SiteArchives(null, null, "unused", 1024 * 1024);

    @Test
    @DisplayName("every website collection is archived or deliberately left out -- a new document must choose")
    void everyCollectionIsDecided() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        Set<String> website = new TreeSet<>();
        for (var candidate : scan.findCandidateComponents("io.pacod.cms.core")) {
            if (candidate.getBeanClassName().startsWith("io.pacod.cms.core.control.")) {
                continue;
            }
            Document document = Class.forName(candidate.getBeanClassName()).getAnnotation(Document.class);
            website.add(document.collection().isEmpty() ? document.value() : document.collection());
        }
        Set<String> decided = new TreeSet<>(SiteArchives.COLLECTIONS);
        decided.addAll(SiteArchives.SKIPPED);
        assertThat(decided).isEqualTo(website);
    }

    @Test
    @DisplayName("a document may hold references to its own website's collections, nothing like $db, $where or code")
    void documentChecks() {
        BsonDocument ok = new BsonDocument("_id", new BsonInt32(1)).append("authors",
                new BsonArray(List.of(new BsonDocument("$ref", new BsonString("author")).append("$id", new BsonInt32(2)))));
        SiteArchives.check(ok, 0);

        assertRefused(new BsonDocument("a", new BsonDocument("$ref", new BsonString("author"))
                .append("$id", new BsonInt32(2)).append("$db", new BsonString("site_other"))));
        assertRefused(new BsonDocument("a", new BsonDocument("$ref", new BsonString("website")).append("$id", new BsonString("x"))));
        assertRefused(new BsonDocument("$where", new BsonString("1")));
        assertRefused(new BsonDocument("f", new BsonJavaScript("x()")));
    }

    private static void assertRefused(BsonDocument document) {
        assertThatThrownBy(() -> SiteArchives.check(document, 0)).isInstanceOf(ArchiveException.class)
                .extracting(e -> ((ArchiveException) e).code()).isEqualTo("portal.archive.error.value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/../b", "/abs", "C:/x", "a//b", "a\\b", "files/.hidden", "./a"})
    @DisplayName("an entry name climbing out, absolute, doubled or hidden is not one")
    void names(String name) {
        assertThat(SiteArchives.name(name)).isNull();
    }

    @Test
    @DisplayName("a well-formed archive opens with its manifest read")
    void opens() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pacod-site.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
        entries.put("db/article.bson", bson(new BsonDocument("_id", new BsonInt32(1))));
        entries.put("files/assets/img/articles/a.jpg", new byte[]{1, 2, 3});
        SiteArchives.Archive archive = archives.open(zip(entries));
        assertThat(archive.organisation()).isFalse();
        assertThat(archive.sites()).singleElement().satisfies(site -> {
            assertThat(site.sourceId()).isEqualTo("acme");
            assertThat(site.prefix()).isEmpty();
        });
    }

    @Test
    @DisplayName("entries an export never writes are refused, naming why")
    void refusals() throws IOException {
        assertCode(Map.of("readme.txt", new byte[0]), "portal.archive.error.notArchive");
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "db/system.users.bson", new byte[0]), "portal.archive.error.collection");
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "files/assets/x.php", new byte[0]), "portal.archive.error.file");
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "files/backups/x.jpg", new byte[0]), "portal.archive.error.file");
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "files/../../etc/x.jpg", new byte[0]), "portal.archive.error.entry");
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "db/article.bson", new byte[]{9, 0, 0, 0, 1}), "portal.archive.error.document");
        assertCode(Map.of("pacod-site.json", MANIFEST.replace("\"version\":1", "\"version\":9").getBytes()), "portal.archive.error.version");
        assertCode(Map.of("pacod-site.json", MANIFEST.replace("\"acme\"", "\"../x\"").getBytes()), "portal.archive.error.manifest");
    }

    @Test
    @DisplayName("a name twice is refused: the second copy would be unpacked but never measured")
    void duplicateNames() throws IOException {
        Path file = Files.createTempFile(dir, "dup", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("pacod-site.json"));
            out.write(MANIFEST.getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("files/assets/a.jpg"));
            out.write(new byte[]{1});
            out.closeEntry();
            // ZipOutputStream refuses a duplicate name; a hand-made archive need not.
            out.putNextEntry(new ZipEntry("files/assets/b.jpg"));
            out.write(new byte[]{2});
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(file);
        replaceAll(bytes, "files/assets/b.jpg", "files/assets/a.jpg");
        Files.write(file, bytes);
        assertThatThrownBy(() -> archives.open(file)).isInstanceOf(ArchiveException.class)
                .extracting(e -> ((ArchiveException) e).code()).isEqualTo("portal.archive.error.entry");
    }

    /** Same length, so the archive's offsets stay right: the local header and the directory entry both. */
    private static void replaceAll(byte[] bytes, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.US_ASCII);
        byte[] t = to.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= bytes.length - f.length; i++) {
            if (java.util.Arrays.equals(bytes, i, i + f.length, f, 0, f.length)) {
                System.arraycopy(t, 0, bytes, i, t.length);
            }
        }
    }

    @Test
    @DisplayName("more than the ceiling, uncompressed, is refused")
    void tooLarge() throws IOException {
        assertCode(Map.of("pacod-site.json", MANIFEST.getBytes(), "files/assets/big.jpg", new byte[2 * 1024 * 1024]),
                "portal.archive.error.tooLarge");
    }

    @Test
    @DisplayName("an organisation's archive names its websites, each under sites/<id>/")
    void organisation() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pacod-organisation.json", """
                {"format":"pacod-cms-organisation","version":1,"organisation":{"id":"org_x","name":"X"},"websites":["acme"]}
                """.getBytes());
        entries.put("sites/acme/pacod-site.json", MANIFEST.getBytes());
        entries.put("sites/acme/db/author.bson", bson(new BsonDocument("_id", new BsonInt32(1))));
        SiteArchives.Archive archive = archives.open(zip(entries));
        assertThat(archive.organisation()).isTrue();
        assertThat(archive.organisationName()).isEqualTo("X");
        assertThat(archive.sites()).extracting(SiteArchives.SiteEntry::prefix).containsExactly("sites/acme/");

        entries.put("sites/other/db/author.bson", bson(new BsonDocument("_id", new BsonInt32(1))));
        assertCode(entries, "portal.archive.error.entry");
    }

    private void assertCode(Map<String, byte[]> entries, String code) throws IOException {
        Path zip = zip(entries);
        assertThatThrownBy(() -> archives.open(zip)).isInstanceOf(ArchiveException.class)
                .extracting(e -> ((ArchiveException) e).code()).isEqualTo(code);
    }

    private Path zip(Map<String, byte[]> entries) throws IOException {
        Path file = Files.createTempFile(dir, "a", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static byte[] bson(BsonDocument document) {
        ByteBuffer buffer = new RawBsonDocument(document, new BsonDocumentCodec()).getByteBuffer().asNIO();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
