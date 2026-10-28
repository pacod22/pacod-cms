package io.pacod.cms.core.seed;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.TreeSet;

/**
 * Where a seed and every file it names are read from: a demo shipped in the jar
 * ({@link DemoContent.Demo}, from the classpath) or a folder on this machine ({@link DirectorySeed},
 * {@code --seed-dir}). The seed itself is the same JSON either way ({@link DemoContent.Seed}); its
 * photographs, its advertisers' creatives and its brand files are paths of the same source.
 *
 * <p>A shipped demo is ours: {@code SeedIntegrityTest} holds it to the demo's own rules at build
 * time. A folder is someone else's ({@link #external()}): it is read strictly, and
 * {@link SeedRules} checks the whole of it before anything is written.
 */
public interface SeedSource {

    /** What the logs and messages call it: a demo's id, or the folder. */
    String id();

    /** The seed's JSON, as a path of this source. */
    String seedFile();

    /**
     * The template set the seed's layouts are for, when the source says so without being read;
     * null otherwise -- the seed's own {@code layoutSet} then says it.
     */
    String templateSet();

    /** Whether the seed comes from outside the jar: read strictly, and checked before it is written. */
    boolean external();

    /**
     * A file's bytes.
     *
     * @throws IllegalStateException when it is missing, or a path this source refuses
     */
    byte[] read(String path);

    /** Every file under {@code folder}, relative to it, in name order; empty when there is none. */
    List<String> files(String folder);

    /** A classpath resource's bytes. */
    static byte[] classpath(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The seed file " + resource + " is missing: " + e.getMessage(), e);
        }
    }

    /** Every file under a classpath folder, relative to it, in name order. */
    static List<String> classpathFiles(String folder) {
        String base = folder.endsWith("/") ? folder : folder + "/";
        TreeSet<String> names = new TreeSet<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + base + "**")) {
                String url = resource.getURL().toString();
                int at = url.lastIndexOf(base);
                String name = at < 0 ? "" : url.substring(at + base.length());
                if (!name.isEmpty() && !name.endsWith("/") && resource.isReadable()) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + folder + ": " + e.getMessage(), e);
        }
        return List.copyOf(names);
    }
}
