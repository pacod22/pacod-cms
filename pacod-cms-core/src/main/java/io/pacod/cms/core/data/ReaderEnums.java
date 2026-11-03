package io.pacod.cms.core.data;

/** The lenient lookup {@link ReaderBrowser}, {@link ReaderOs} and {@link ReaderDevice} share. */
final class ReaderEnums {

    private ReaderEnums() {
    }

    static <E extends Enum<E>> E of(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(name.strip())) {
                return constant;
            }
        }
        return null;
    }
}
