package logstream_backend.tenant;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.logging.Logger;

/**
 * Reads the {@code tenant-id} key from gRPC metadata and attaches it to the
 * gRPC {@link Context}, where {@link #tenant()} can retrieve it from service
 * implementations.
 *
 * <p>
 * Unlike the REST filter (which tolerates a missing header), gRPC ingestion
 * is explicit: when {@code requireTenant} is true a missing tenant is rejected
 * with {@code INVALID_ARGUMENT}. This prevents accidental "unscoped" writes.
 * </p>
 */
public class TenantGrpcInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(TenantGrpcInterceptor.class.getName());

    public static final Metadata.Key<String> TENANT_KEY = Metadata.Key.of("tenant-id",
            Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC context key exposing the resolved tenant to handlers. */
    public static final Context.Key<String> TENANT_CONTEXT_KEY = Context.key("logstream-tenant");

    private final boolean requireTenant;

    public TenantGrpcInterceptor(boolean requireTenant) {
        this.requireTenant = requireTenant;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String rawTenant = headers.get(TENANT_KEY);

        if (requireTenant
                && (rawTenant == null || rawTenant.isBlank())) {
            call.close(
                    Status.INVALID_ARGUMENT.withDescription(
                            "Missing gRPC metadata key 'tenant-id'"),
                    new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        String tenant = TenantContext.normalize(rawTenant);

        LOG.fine(() -> "gRPC call bound to tenant=" + tenant);

        Context context = Context.current()
                .withValue(TENANT_CONTEXT_KEY, tenant);

        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * Returns the tenant bound to the current gRPC call, defaulting when the
     * interceptor was not applied (e.g. in unit tests).
     */
    public static String tenant() {
        String tenant = TENANT_CONTEXT_KEY.get();
        return tenant == null ? TenantContext.DEFAULT_TENANT : tenant;
    }
}
