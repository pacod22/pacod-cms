package io.pacod.cms.core.testing;

import io.pacod.cms.core.data.LanguageChain;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs every test in this module with Bulgarian as the website's own language.
 *
 * <p>The production default is English ({@code pacod.content.default-language}). The suites here
 * run with Bulgarian on purpose: a website whose original language is <em>not</em> English is the
 * configuration most likely to break quietly -- English is also {@link LanguageChain#FALLBACK},
 * so with an English original two different rules give the same answer and a test cannot tell
 * them apart.
 *
 * <p>Registered for every test through {@code META-INF/services} and
 * {@code junit.jupiter.extensions.autodetection.enabled} in {@code junit-platform.properties}. A
 * test about the English default sets {@link LanguageChain#useOriginal} itself; the previous value
 * is restored after each test either way.
 */
public class OriginalLanguageExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(OriginalLanguageExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put("previous", LanguageChain.useOriginal("bg"));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String previous = context.getStore(NAMESPACE).remove("previous", String.class);
        LanguageChain.useOriginal(previous != null ? previous : LanguageChain.DEFAULT_ORIGINAL);
    }
}
