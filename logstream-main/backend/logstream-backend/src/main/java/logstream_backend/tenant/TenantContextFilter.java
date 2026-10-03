package logstream_backend.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the {@code X-Tenant-Id} request header to {@link TenantContext} for the
 * duration of a REST request and guarantees it is cleared afterwards.
 *
 * <p>
 * When the header is absent the {@code default} tenant is used. Unknown or
 * malformed tenant ids are normalised (never rejected here) because header
 * values are a routing hint until authentication is added — see
 * {@code docs/MULTI_TENANCY.md} §9.
 * </p>
 */
@Component
@Order(1)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String tenant = request.getHeader(TENANT_HEADER);
        TenantContext.set(tenant);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Critical: pooled threads must not leak tenant state.
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Static/health endpoints are tenant-agnostic.
        return path.startsWith("/actuator");
    }
}
