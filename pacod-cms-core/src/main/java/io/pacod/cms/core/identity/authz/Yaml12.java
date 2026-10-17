package io.pacod.cms.core.identity.authz;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * YAML as every port of the rules engine reads it: YAML 1.2's core schema, where only
 * {@code true} and {@code false} are booleans. YAML 1.1 -- and so SnakeYAML 1.x, which Spring
 * bundles -- also reads {@code yes}, {@code no}, {@code on} and {@code off} as booleans, and a
 * manifest saying {@code implicit: yes} would then mean something the other ports refuse.
 */
public final class Yaml12 {

    private Yaml12() {
    }

    public static Object load(String text) {
        LoadSettings settings = LoadSettings.builder()
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .setCodePointLimit(1 << 20)
                .build();
        return new Load(settings).loadFromString(text);
    }
}
