package io.pacod.cms.site.testing;

import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.time.ZoneId;

/**
 * Runs every test method -- and its {@code @BeforeEach} and {@code @AfterEach} -- with the website
 * {@link #TENANT} bound, as a request on the platform always is.
 *
 * <p>Tenant-scoped code refuses to run without a website ({@code TenantContext.current()} throws),
 * which is the point of it, and almost every test here exercises such code. A test about the
 * absence of one, or about two websites at once, binds its own with {@code TenantContext.runAs}:
 * the innermost binding wins.
 *
 * <p>The tenant names no language of its own, so the website's language is the platform default,
 * which {@code OriginalLanguageExtension} sets for the module.
 *
 * <p>Registered through {@code META-INF/services}, as {@code OriginalLanguageExtension} is.
 */
public class TestTenantExtension implements InvocationInterceptor {

    public static final Tenant TENANT = new Tenant("test", "org_test", "site_test", null, ZoneId.of("UTC"));

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        bound(invocation);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        bound(invocation);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        bound(invocation);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        bound(invocation);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext,
                                     ExtensionContext extensionContext) throws Throwable {
        bound(invocation);
    }

    private static void bound(Invocation<Void> invocation) throws Throwable {
        TenantContext.callChecked(TENANT, () -> {
            invocation.proceed();
            return null;
        });
    }
}
