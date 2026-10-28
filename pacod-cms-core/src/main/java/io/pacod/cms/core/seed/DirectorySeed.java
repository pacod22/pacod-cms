package io.pacod.cms.core.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

/**
 * A seed in a folder on this machine: {@value #SEED_FILE} at its root, and the files it names by
 * paths relative to it -- {@code photos/launch.jpg}, {@code banners/pacod-gw}. What
 * {@code --seed-dir} reads; {@code make seed SEED_DIR=…} mounts the folder read-only at
 * {@code /seed} in the one-off studio container.
 *
 * <p>The folder is the only thing it reads. A path is refused when it is absolute, empty, or has a
 * {@code .}, {@code ..} or empty segment, a backslash or a NUL; a file is read only when it is a
 * regular file reached without a symbolic link anywhere on the way, and no larger than
 * {@value #MAX_FILE_BYTES} bytes. A listing skips links, and names starting with a dot
 * ({@code .DS_Store}), which no creative's import would take.
 */
public final class DirectorySeed implements SeedSource {

    public static final String SEED_FILE = "seed.json";

    /** Larger than any photograph or creative a seed needs; the seed file itself included. */
    static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private final Path root;

    /**
     * @throws IllegalArgumentException when {@code folder} is not a folder that exists
     */
    public DirectorySeed(Path folder) {
        try {
            this.root = folder.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("No seed folder " + folder + ": " + e.getMessage(), e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("The seed folder " + folder + " is not a folder");
        }
    }

    @Override
    public String id() {
        return "folder " + root;
    }

    @Override
    public String seedFile() {
        return SEED_FILE;
    }

    @Override
    public String templateSet() {
        return null;
    }

    @Override
    public boolean external() {
        return true;
    }

    @Override
    public byte[] read(String path) {
        Path file = inside(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalStateException("The seed file " + path + " is not a file");
            }
            if (attributes.size() > MAX_FILE_BYTES) {
                throw new IllegalStateException("The seed file " + path + " is larger than " + MAX_FILE_BYTES + " bytes");
            }
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The seed file " + path + " is missing", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the seed file " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> files(String folder) {
        Path dir = inside(folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(dir::relativize)
                    .filter(p -> !hidden(p))
                    .map(p -> p.toString().replace(p.getFileSystem().getSeparator(), "/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list the seed folder " + folder + ": " + e.getMessage(), e);
        }
    }

    /**
     * The path inside the folder, its every segment checked; a path that leaves the folder, or
     * reaches what it names through a link, is refused.
     */
    Path inside(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalStateException("The seed names a path it may not: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalStateException("The seed names a path it may not: " + path);
            }
        }
        Path resolved = root.resolve(path);
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // The root is a real path, so any link on the way makes the two differ.
                if (!resolved.toRealPath().equals(resolved)) {
                    throw new IllegalStateException("The seed path " + path + " goes through a link");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not resolve the seed path " + path + ": " + e.getMessage(), e);
            }
        }
        return resolved;
    }

    private static boolean hidden(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }
}
