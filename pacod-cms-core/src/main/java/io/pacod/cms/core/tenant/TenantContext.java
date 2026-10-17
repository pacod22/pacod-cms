package io.pacod.cms.core.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The website the current unit of work belongs to.
 *
 * <p>A {@link ScopedValue}, bound for the duration of one request by the application's resolver
 * (the public site by Host, the studio by path) and for one unit of background work by whoever
 * runs it. Two properties follow, and both are the point:
 * <ul>
 *   <li><b>It cannot leak.</b> The binding ends with the scope that made it -- there is nothing to
 *       clear in a {@code finally}, so a pooled thread cannot carry one website's identity into
 *       the next request.</li>
 *   <li><b>It is not inherited by other threads.</b> Work handed to an executor does not see it.
 *       Anything that runs elsewhere -- a scheduled flush, a job -- receives the tenant explicitly
 *       and binds it with {@link #runAs} or {@link #callAs}.</li>
 * </ul>
 *
 * <p>Reading it where none is bound is an error, never a default: {@link #current()} throws. A
 * default website is exactly how one tenant's data ends up in another's database.
 */
public final class TenantContext {

    private static final ScopedValue<Tenant> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    /**
     * The bound tenant.
     *
     * @throws NoTenantException when no website is bound to this unit of work
     */
    public static Tenant current() {
        if (!CURRENT.isBound()) {
            throw new NoTenantException();
        }
        return CURRENT.get();
    }

    /** The bound tenant, or empty -- for code that legitimately runs both with and without one. */
    public static Optional<Tenant> find() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static void runAs(Tenant tenant, Runnable work) {
        ScopedValue.where(CURRENT, require(tenant)).run(work);
    }

    public static <T> T callAs(Tenant tenant, Supplier<T> work) {
        return ScopedValue.where(CURRENT, require(tenant)).call(work::get);
    }

    /**
     * For callers whose work throws checked exceptions -- a servlet filter chain. A separate name
     * rather than an overload: a lambda would fit both shapes and make every call ambiguous.
     */
    public static <T, X extends Throwable> T callChecked(Tenant tenant, ScopedValue.CallableOp<T, X> work) throws X {
        return ScopedValue.where(CURRENT, require(tenant)).call(work);
    }

    private static Tenant require(Tenant tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant");
        }
        return tenant;
    }

    /** Thrown when tenant-scoped data is touched with no website bound. */
    public static final class NoTenantException extends IllegalStateException {
        public NoTenantException() {
            super("No website is bound to this unit of work: tenant-scoped data cannot be read or "
                    + "written outside TenantContext.runAs / callAs");
        }
    }
}
