package io.pacod.cms.core.identity.authz;

/**
 * Why a manifest was refused. {@link #code()} is the same in every port of pacod-id's rules
 * engine -- the vectors in {@code policy/engine-vectors/manifest-errors.yaml} compare it.
 */
public class ManifestException extends IllegalArgumentException {

    private final String code;

    public ManifestException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** One of {@code bad_manifest}, {@code bad_product}, {@code bad_name}, {@code unknown_parent}, ... */
    public String code() {
        return code;
    }
}
