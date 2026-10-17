package io.pacod.cms.core.design;

import lombok.Data;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One section in a layout: its type and its settings, as stored -- unchecked until
 * {@link SectionSettings} has seen them.
 *
 * <p>The id is generated, never typed ({@link #newId()}): the studio's editor addresses a section by
 * it. Stored as {@code _id}, as any embedded property named {@code id} is.
 */
@Data
public class Section {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private String id;
    private String type;
    private Map<String, Object> settings = new LinkedHashMap<>();

    public Section() {
    }

    public Section(String id, String type, Map<String, ?> settings) {
        this.id = id;
        this.type = type;
        this.settings = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
    }

    public static Section of(SectionType type, Map<String, ?> settings) {
        return new Section(newId(), type.id(), settings);
    }

    /** {@code s} and seven letters or digits. */
    public static String newId() {
        StringBuilder id = new StringBuilder("s");
        for (int i = 0; i < 7; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    public Section copy() {
        return new Section(id, type, settings);
    }
}
