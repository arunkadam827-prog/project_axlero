package logstream_backend.tenant;

import java.util.regex.Pattern;

/**
 * Holds the tenant id for the duration of a single request.
 *
 * <p>For REST calls the value is populated by {@link TenantContextFilter};
 * for gRPC calls it is read from the {@code io.grpc.Context} populated by
 * {@link TenantGrpcInterceptor}. Both paths default to {@link #DEFAULT_TENANT}
 * so a request is always scoped — never "all tenants".</p>
 *
 * <p>The ThreadLocal MUST be cleared in a finally block to avoid leaking
 * tenant state across pooled request threads.</p>
 */
public final class TenantContext {

    public static final String DEFAULT_TENANT = "default";

    /**
     * Tenant ids are lower-case, start with an alphanumeric character and may
     * contain dashes. This keeps them safe to use directly as index directory
     * names while remaining human readable.
     */
    private static final Pattern VALID_TENANT =
            Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(normalize(tenantId));
    }

    public static String get() {
        String tenant = CURRENT.get();
        return tenant == null ? DEFAULT_TENANT : tenant;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Normalises an incoming tenant id: trims, lower-cases and falls back to
     * the default tenant when the value is missing or malformed.
     */
    public static String normalize(String tenantId) {
        if (tenantId == null) {
            return DEFAULT_TENANT;
        }
        String candidate = tenantId.trim().toLowerCase();
        if (candidate.isEmpty() || !VALID_TENANT.matcher(candidate).matches()) {
            return DEFAULT_TENANT;
        }
        return candidate;
    }

    public static boolean isValid(String tenantId) {
        return tenantId != null
                && VALID_TENANT.matcher(tenantId.trim().toLowerCase()).matches();
    }
}
