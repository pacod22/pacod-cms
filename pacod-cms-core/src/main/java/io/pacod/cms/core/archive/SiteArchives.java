package io.pacod.cms.core.archive;

import io.pacod.cms.core.control.data.Organisation;
import io.pacod.cms.core.control.data.Website;
import io.pacod.cms.core.control.data.WebsiteStatus;
import io.pacod.cms.core.control.repository.WebsiteRepository;
import io.pacod.cms.core.data.CrawlerVisit;
import io.pacod.cms.core.data.JournalIssue;
import io.pacod.cms.core.data.Language;
import io.pacod.cms.core.data.Menu;
import io.pacod.cms.core.data.Page;
import io.pacod.cms.core.data.PdfEnrichment;
import io.pacod.cms.core.data.PdfSettings;
import io.pacod.cms.core.data.SiteMetricDay;
import io.pacod.cms.core.data.SiteMetricHour;
import io.pacod.cms.core.data.SiteSettings;
import io.pacod.cms.core.data.SiteVisitor;
import io.pacod.cms.core.data.SocialPage;
import io.pacod.cms.core.tenant.SiteStorage;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import com.mongodb.client.MongoCollection;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A website, or an organisation's websites, as one ZIP: what the studio's export downloads and
 * its restore reads back -- always into a <b>new</b> website, never over an existing one.
 *
 * <h2>The archive</h2>
 * <pre>
 *   pacod-site.json            the manifest: format, version, the source website and its settings
 *   db/&lt;collection&gt;.bson       each content collection as concatenated BSON (mongodump's format)
 *   files/assets/…             the website's folder trees the public addresses name
 *   files/resources/…
 *   files/private/…            the draft issue PDFs, which no address names
 * </pre>
 * An organisation's is {@code pacod-organisation.json} and one such tree per website under
 * {@code sites/<id>/}. Raw BSON, never JSON: a document goes back byte for byte, its references
 * ({@code @DBRef}: {@code $ref}/{@code $id}, no database) and number types included.
 *
 * <h2>What is left out</h2>
 * The analytics ({@link #SKIPPED}: hourly and daily buckets, visitors, the crawler log), which
 * expire on their own and describe another website's traffic; members and roles, which pacod-id
 * holds; the website's hosts, which belong to the installation it came from; the banner backups.
 * {@link #COLLECTIONS} and {@link #SKIPPED} together name every website collection, and
 * {@code SiteArchivesTest} fails when a new document belongs to neither.
 *
 * <h2>An archive is untrusted</h2>
 * {@link #open} reads every entry before anything is created, and refuses -- naming why -- an
 * archive this studio would not have written: a path outside its tree, a collection or a file type
 * it does not know, a document with a reference into another database ({@code $db}) or to a
 * collection it does not know, a field name beginning {@code $}, code or pointer values, more than
 * {@link #MAX_ENTRIES} entries or more uncompressed than the upload ceiling allows. Restore writes
 * only into the new website's own database and folder; what a document says is what an operator of
 * that website could have typed.
 */
@Slf4j
public class SiteArchives {

    public static final String SITE_MANIFEST = "pacod-site.json";
    public static final String ORG_MANIFEST = "pacod-organisation.json";
    public static final String SITE_FORMAT = "pacod-cms-site";
    public static final String ORG_FORMAT = "pacod-cms-organisation";
    public static final int VERSION = 1;

    /** The website collections an archive carries. */
    public static final List<String> COLLECTIONS = List.of("article", "author", "category", "image", "tag",
            Page.COLLECTION, Language.COLLECTION, SiteSettings.COLLECTION, Menu.COLLECTION, "layout", "theme",
            "banner", "bannerLayout", "bannerSkeleton", SocialPage.COLLECTION, PdfSettings.COLLECTION,
            PdfEnrichment.COLLECTION, JournalIssue.COLLECTION);

    /** The website collections it leaves out: analytics, which expire and are another website's traffic. */
    public static final List<String> SKIPPED = List.of(SiteMetricHour.COLLECTION, SiteMetricDay.COLLECTION,
            SiteVisitor.COLLECTION, CrawlerVisit.COLLECTION);

    /** The trees of a website's folder an archive carries. */
    public static final List<String> TREES = List.of("assets", "resources", "private");

    /** What a file in an archive may be: pictures, PDFs, and what a banner folder holds. */
    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "ico",
            "pdf", "html", "htm", "css", "js", "json", "map", "woff", "woff2", "ttf", "eot", "txt", "xml");

    public static final int MAX_ENTRIES = 100_000;
    /** The largest BSON document MongoDB stores. */
    static final int MAX_DOCUMENT = 16 * 1024 * 1024;
    private static final int BATCH_BYTES = 8 * 1024 * 1024;
    private static final Pattern SITE_DIR = Pattern.compile("sites/(" + Website.ID.pattern() + ")/");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MongoTemplate mongo;
    private final WebsiteRepository websites;
    private final String storageRoot;
    private final long maxBytes;

    /**
     * Not a bean of core's: the studio declares it ({@code ArchiveConfig}), and the public site,
     * which never exports or restores, does not scan this package.
     *
     * @param mongo       the routed template: every read and write goes to the bound website
     * @param storageRoot the root of every website's folder ({@code pacod.storage.root})
     * @param maxBytes    the largest archive taken, staged or uncompressed
     */
    public SiteArchives(MongoTemplate mongo, WebsiteRepository websites, String storageRoot, long maxBytes) {
        this.mongo = mongo;
        this.websites = websites;
        this.storageRoot = storageRoot;
        this.maxBytes = maxBytes;
    }

    /** The largest archive taken, uncompressed as uploaded. */
    public long maxBytes() {
        return maxBytes;
    }

    // ------------------------------------------------------------------------------- export

    /** One website as an archive, written to {@code out}. */
    public void exportSite(Website website, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        writeSite(zip, "", website);
        zip.finish();
    }

    /** Every website of the organisation, but those being deleted, as one archive. */
    public void exportOrganisation(Organisation organisation, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        List<String> ids = new ArrayList<>();
        for (Website website : websites.findByOrganisationIdOrderByNameAsc(organisation.getId())) {
            if (website.getStatus() == WebsiteStatus.DELETING) {
                continue;
            }
            writeSite(zip, "sites/" + website.getId() + "/", website);
            ids.add(website.getId());
        }
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("format", ORG_FORMAT);
        manifest.put("version", VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.putObject("organisation").put("id", organisation.getId()).put("name", organisation.getName());
        ids.forEach(manifest.putArray("websites")::add);
        put(zip, ORG_MANIFEST, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.finish();
    }

    /** The manifest goes last: it counts what was written. */
    private void writeSite(ZipOutputStream zip, String prefix, Website website) throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        long[] files = {0};
        try {
            TenantContext.runAs(TenantDirectory.tenantOf(website), () -> {
                try {
                    for (String name : COLLECTIONS) {
                        long n = writeCollection(zip, prefix + "db/" + name + ".bson", name);
                        if (n > 0) {
                            counts.put(name, n);
                        }
                    }
                    Path root = SiteStorage.siteRoot(storageRoot);
                    for (String tree : TREES) {
                        files[0] += writeTree(zip, prefix + "files/", root, root.resolve(tree));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("format", SITE_FORMAT);
        manifest.put("version", VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("schemaVersion", website.getSchemaVersion());
        ObjectNode site = manifest.putObject("website");
        site.put("id", website.getId());
        site.put("name", website.getName());
        site.put("defaultLanguage", website.getDefaultLanguage());
        site.put("timezone", website.getTimezone());
        site.put("templateSet", website.getTemplateSet());
        site.put("aiDisabled", website.isAiDisabled());
        ObjectNode collections = manifest.putObject("collections");
        counts.forEach(collections::put);
        manifest.put("files", files[0]);
        put(zip, prefix + SITE_MANIFEST, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        log.info("Exported website {}: {} collections, {} files", website.getId(), counts.size(), files[0]);
    }

    private long writeCollection(ZipOutputStream zip, String entry, String name) throws IOException {
        MongoCollection<RawBsonDocument> collection = mongo.getDb().getCollection(name, RawBsonDocument.class);
        long n = 0;
        for (RawBsonDocument document : collection.find()) {
            if (n == 0) {
                zip.putNextEntry(new ZipEntry(entry));
            }
            ByteBuffer buffer = document.getByteBuffer().asNIO();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            zip.write(bytes);
            n++;
        }
        if (n > 0) {
            zip.closeEntry();
        }
        return n;
    }

    private static long writeTree(ZipOutputStream zip, String prefix, Path root, Path tree) throws IOException {
        if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(tree)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        long n = 0;
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (name(relative) == null || !EXTENSIONS.contains(extension(relative))) {
                // Not something a restore would take back: a dot file, an editor's leftover.
                continue;
            }
            zip.putNextEntry(new ZipEntry(prefix + relative));
            Files.copy(file, zip);
            zip.closeEntry();
            n++;
        }
        return n;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    // ------------------------------------------------------------------------------- reading

    /** An upload, staged whole on disk under the storage root -- at most {@link #maxBytes()}. */
    public Path stage(InputStream in) throws IOException {
        Path folder = Path.of(storageRoot, "tmp");
        Files.createDirectories(folder);
        Path file = Files.createTempFile(folder, "restore-", ".zip");
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new ArchiveException("portal.archive.error.tooLarge", maxBytes / (1024 * 1024));
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return file;
    }

    /** One website in an archive: where its tree starts, and what its manifest says. */
    public record SiteEntry(String prefix, String sourceId, String name, String defaultLanguage, String timezone,
                            String templateSet, boolean aiDisabled, Map<String, Long> collections, long files) {
    }

    /**
     * An archive, read and checked whole.
     *
     * @param organisationName the organisation's name, for an organisation's archive; else null
     */
    public record Archive(Path file, boolean organisation, String organisationName, List<SiteEntry> sites) {
    }

    /**
     * Reads and checks every entry of the archive -- manifests, paths, file types, every document
     * -- before anything is created.
     *
     * @throws ArchiveException naming what is wrong
     */
    public Archive open(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> names = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (names.size() >= MAX_ENTRIES) {
                    throw new ArchiveException("portal.archive.error.tooMany", MAX_ENTRIES);
                }
                // A name twice: getEntry() -- what the checks read -- answers the first, while
                // entries() -- what fill() copies -- yields both, so the second would go unmeasured.
                if (!seen.add(entry.getName())) {
                    throw new ArchiveException("portal.archive.error.entry", entry.getName());
                }
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
            boolean organisation = names.contains(ORG_MANIFEST);
            String organisationName = null;
            List<String> prefixes = new ArrayList<>();
            if (organisation) {
                JsonNode manifest = manifest(zip, ORG_MANIFEST, ORG_FORMAT);
                organisationName = manifest.path("organisation").path("name").asString("");
                for (JsonNode id : manifest.path("websites")) {
                    String prefix = "sites/" + id.asString("") + "/";
                    if (!SITE_DIR.matcher(prefix).matches()) {
                        throw new ArchiveException("portal.archive.error.entry", id.asString(""));
                    }
                    prefixes.add(prefix);
                }
                if (prefixes.isEmpty()) {
                    throw new ArchiveException("portal.archive.error.empty");
                }
            } else if (names.contains(SITE_MANIFEST)) {
                prefixes.add("");
            } else {
                throw new ArchiveException("portal.archive.error.notArchive");
            }

            List<SiteEntry> sites = new ArrayList<>();
            for (String prefix : prefixes) {
                sites.add(siteEntry(prefix, manifest(zip, prefix + SITE_MANIFEST, SITE_FORMAT)));
            }
            long[] total = {0};
            for (String name : names) {
                checkEntry(zip, name, organisation ? ORG_MANIFEST : null, prefixes, total);
            }
            return new Archive(file, organisation, organisationName, sites);
        } catch (IOException e) {
            throw new ArchiveException("portal.archive.error.notArchive");
        }
    }

    private JsonNode manifest(ZipFile zip, String name, String format) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new ArchiveException("portal.archive.error.manifest", name);
        }
        JsonNode manifest;
        try (InputStream in = zip.getInputStream(entry)) {
            manifest = JSON.readTree(in.readNBytes(1024 * 1024));
        } catch (RuntimeException e) {
            throw new ArchiveException("portal.archive.error.manifest", name);
        }
        if (!format.equals(manifest.path("format").asString(""))) {
            throw new ArchiveException("portal.archive.error.manifest", name);
        }
        if (manifest.path("version").asInt(0) != VERSION) {
            throw new ArchiveException("portal.archive.error.version", manifest.path("version").asInt(0), VERSION);
        }
        return manifest;
    }

    private static SiteEntry siteEntry(String prefix, JsonNode manifest) {
        JsonNode site = manifest.path("website");
        String id = site.path("id").asString("");
        String name = site.path("name").asString("").strip();
        String language = site.path("defaultLanguage").asString("en");
        String timezone = site.path("timezone").asString("UTC");
        if (!Website.ID.matcher(id).matches() || (!prefix.isEmpty() && !prefix.equals("sites/" + id + "/"))) {
            throw new ArchiveException("portal.archive.error.manifest", prefix + SITE_MANIFEST);
        }
        if (!LANGUAGE.matcher(language).matches()) {
            throw new ArchiveException("portal.archive.error.manifest", prefix + SITE_MANIFEST);
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            timezone = "UTC";
        }
        String templateSet = site.path("templateSet").isString() ? site.path("templateSet").asString() : null;
        Map<String, Long> collections = new LinkedHashMap<>();
        manifest.path("collections").properties().forEach(e -> collections.put(e.getKey(), e.getValue().asLong(0)));
        return new SiteEntry(prefix, id, name.isEmpty() ? id : name, language, timezone, templateSet,
                site.path("aiDisabled").asBoolean(false), Collections.unmodifiableMap(collections),
                manifest.path("files").asLong(0));
    }

    /** One entry: where it may be, what it may be; every document of a collection read and checked. */
    private void checkEntry(ZipFile zip, String raw, String orgManifest, List<String> prefixes, long[] total)
            throws IOException {
        String name = name(raw);
        if (name == null || !name.equals(raw)) {
            throw new ArchiveException("portal.archive.error.entry", raw);
        }
        if (name.equals(orgManifest)) {
            return;
        }
        String prefix = prefixes.stream().filter(name::startsWith).findFirst()
                .orElseThrow(() -> new ArchiveException("portal.archive.error.entry", raw));
        String inside = name.substring(prefix.length());
        if (inside.equals(SITE_MANIFEST)) {
            return;
        }
        if (inside.startsWith("db/")) {
            String collection = inside.substring(3);
            if (!collection.endsWith(".bson") || !COLLECTIONS.contains(collection.substring(0, collection.length() - 5))) {
                throw new ArchiveException("portal.archive.error.collection", raw);
            }
            try (InputStream in = zip.getInputStream(zip.getEntry(raw))) {
                readDocuments(in, raw, total, document -> {
                });
            }
            return;
        }
        if (inside.startsWith("files/")) {
            String path = inside.substring(6);
            String tree = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            if (!TREES.contains(tree) || !EXTENSIONS.contains(extension(path))) {
                throw new ArchiveException("portal.archive.error.file", raw);
            }
            try (InputStream in = zip.getInputStream(zip.getEntry(raw))) {
                total[0] += in.transferTo(OutputStream.nullOutputStream());
            }
            if (total[0] > maxBytes) {
                throw new ArchiveException("portal.archive.error.tooLarge", maxBytes / (1024 * 1024));
            }
            return;
        }
        throw new ArchiveException("portal.archive.error.entry", raw);
    }

    /** Reads concatenated BSON, checking each document, counting into {@code total}. */
    private void readDocuments(InputStream in, String entry, long[] total,
                               java.util.function.Consumer<RawBsonDocument> each) throws IOException {
        byte[] head = new byte[4];
        while (true) {
            int got = in.readNBytes(head, 0, 4);
            if (got == 0) {
                return;
            }
            int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (got < 4 || length < 5 || length > MAX_DOCUMENT) {
                throw new ArchiveException("portal.archive.error.document", entry);
            }
            byte[] bytes = new byte[length];
            System.arraycopy(head, 0, bytes, 0, 4);
            if (in.readNBytes(bytes, 4, length - 4) != length - 4) {
                throw new ArchiveException("portal.archive.error.document", entry);
            }
            total[0] += length;
            if (total[0] > maxBytes) {
                throw new ArchiveException("portal.archive.error.tooLarge", maxBytes / (1024 * 1024));
            }
            RawBsonDocument document;
            try {
                document = new RawBsonDocument(bytes);
                check(document, 0);
            } catch (ArchiveException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ArchiveException("portal.archive.error.document", entry);
            }
            each.accept(document);
        }
    }

    /**
     * What a stored document of this application may hold: no field named {@code $…} but a
     * reference's {@code $ref}/{@code $id}, a reference only to a collection of the same website
     * (never {@code $db}: Spring Data would read another website's database through it), and no
     * code or pointer values.
     */
    static void check(BsonValue value, int depth) {
        if (depth > 64) {
            throw new ArchiveException("portal.archive.error.value", "depth");
        }
        BsonType type = value.getBsonType();
        if (type == BsonType.JAVASCRIPT || type == BsonType.JAVASCRIPT_WITH_SCOPE || type == BsonType.DB_POINTER
                || type == BsonType.SYMBOL) {
            throw new ArchiveException("portal.archive.error.value", type.name());
        }
        if (value.isDocument()) {
            BsonDocument document = value.asDocument();
            for (String key : document.keySet()) {
                if (key.startsWith("$") && !key.equals("$ref") && !key.equals("$id")) {
                    throw new ArchiveException("portal.archive.error.value", key);
                }
            }
            if (document.containsKey("$ref")) {
                BsonValue ref = document.get("$ref");
                if (!ref.isString() || !COLLECTIONS.contains(ref.asString().getValue())) {
                    throw new ArchiveException("portal.archive.error.value", "$ref");
                }
            }
            for (BsonValue child : document.values()) {
                check(child, depth + 1);
            }
        } else if (value.isArray()) {
            for (BsonValue child : value.asArray()) {
                check(child, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------------------- restore

    /**
     * Writes one website of the archive into the website bound now -- a new one, between its
     * indexes and its seed steps ({@code SiteProvisioner.create(…, fill)}): every collection, every
     * file. The source's absolute address ({@code SiteSettings.baseUrl}) is dropped: it names
     * another website's host, and the new one answers at its own.
     */
    public void fill(Archive archive, SiteEntry site) {
        try (ZipFile zip = new ZipFile(archive.file().toFile())) {
            long[] total = {0};
            for (String collection : COLLECTIONS) {
                ZipEntry entry = zip.getEntry(site.prefix() + "db/" + collection + ".bson");
                if (entry == null) {
                    continue;
                }
                MongoCollection<RawBsonDocument> target = mongo.getDb().getCollection(collection, RawBsonDocument.class);
                List<RawBsonDocument> batch = new ArrayList<>();
                long[] batchBytes = {0};
                try (InputStream in = zip.getInputStream(entry)) {
                    readDocuments(in, entry.getName(), total, document -> {
                        batch.add(document);
                        batchBytes[0] += document.getByteBuffer().remaining();
                        if (batchBytes[0] >= BATCH_BYTES || batch.size() >= 1_000) {
                            target.insertMany(batch);
                            batch.clear();
                            batchBytes[0] = 0;
                        }
                    });
                }
                if (!batch.isEmpty()) {
                    target.insertMany(batch);
                }
            }
            mongo.getDb().getCollection(SiteSettings.COLLECTION)
                    .updateMany(new Document(), new Document("$unset", new Document("baseUrl", "")));

            Path root = SiteStorage.siteRoot(storageRoot);
            String files = site.prefix() + "files/";
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(files)) {
                    continue;
                }
                String relative = entry.getName().substring(files.length());
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || !TREES.contains(root.relativize(target).getName(0).toString())) {
                    throw new ArchiveException("portal.archive.error.file", entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target)) {
                    // Counted again as written: open() measured these bytes, and this is what lands on disk.
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total[0] += read;
                        if (total[0] > maxBytes) {
                            throw new ArchiveException("portal.archive.error.tooLarge", maxBytes / (1024 * 1024));
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The archive's staged file goes; a failure to delete it is only logged. */
    public void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete the staged archive {}: {}", file, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------- names

    /**
     * An entry name as written: forward slashes, relative, no {@code .} or {@code ..} segment, no
     * hidden file; null for anything else.
     */
    static String name(String raw) {
        if (raw == null || raw.isEmpty() || raw.contains("\\") || raw.startsWith("/") || raw.contains("//")
                || raw.contains("\u0000") || raw.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.startsWith(".")) {
                return null;
            }
        }
        return raw;
    }

    static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot <= slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
