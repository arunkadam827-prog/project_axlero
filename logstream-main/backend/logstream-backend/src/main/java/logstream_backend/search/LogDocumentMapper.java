package logstream_backend.search;

import com.logstream.grpc.LogRequest;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Converts between inbound {@link LogRequest} protobufs and Lucene
 * {@link Document}s. Field types follow {@code docs/INDEXING_STRATEGY.md} §3.2.
 *
 * <p>
 * Level/service/host dimensions are additionally written as
 * {@link SortedSetDocValuesFacetField}s so {@code LogAnalyticsService} can
 * compute distributions in a single pass. Such documents MUST be finalised with
 * {@link FacetsConfig#build(Document)} before being handed to the writer, which
 * this mapper does.
 * </p>
 */
@Component
public class LogDocumentMapper {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_DATE_TIME;

    private final FacetsConfig facetsConfig;

    public LogDocumentMapper(LuceneIndexManager indexManager) {
        this.facetsConfig = indexManager.facetsConfig();
    }

    /**
     * Builds an indexable document. {@code tenant} overrides any value on the
     * request so the resolved tenant always wins (defence in depth).
     */
    public Document toDocument(LogRequest request, String tenant) throws IOException {

        String level = LogFields.canonicalLevel(request.getLevel());
        String timestamp = request.getTimestamp();
        long epochMillis = toEpochMillis(timestamp);
        String message = request.getMessage() == null ? "" : request.getMessage();
        String service = nullToEmpty(request.getService());

        Document doc = new Document();

        // Exact-match fields -> StringField, stored for display.
        doc.add(new StringField(
                LogFields.TENANT_ID, tenant, Field.Store.YES));
        doc.add(new StringField(
                LogFields.LEVEL, level, Field.Store.YES));
        doc.add(new StringField(
                LogFields.SERVICE, service, Field.Store.YES));
        doc.add(new StringField(
                LogFields.HOST,
                nullToEmpty(request.getHost()), Field.Store.YES));
        doc.add(new StringField(
                LogFields.TRACE_ID,
                nullToEmpty(request.getTraceId()), Field.Store.YES));
        doc.add(new StringField(
                LogFields.ERROR_CODE,
                nullToEmpty(request.getErrorCode()), Field.Store.YES));

        // Numeric range fields (docvalues power range facets).
        int responseTime = (int) Math.min(
                Integer.MAX_VALUE, Math.max(0, request.getResponseTime()));
        doc.add(new IntPoint(LogFields.RESPONSE_TIME, responseTime));
        doc.add(new NumericDocValuesField(
                LogFields.RESPONSE_TIME, responseTime));
        doc.add(new StoredField(LogFields.RESPONSE_TIME, responseTime));

        doc.add(new LongPoint(LogFields.EPOCH_MILLIS, epochMillis));
        doc.add(new NumericDocValuesField(
                LogFields.EPOCH_MILLIS, epochMillis));

        // Analyzed free text.
        doc.add(new TextField(
                LogFields.MESSAGE, message, Field.Store.YES));

        // Facet dimensions for single-pass aggregations.
        doc.add(new SortedSetDocValuesFacetField(LogFields.LEVEL, level));
        doc.add(new SortedSetDocValuesFacetField(LogFields.SERVICE, service));

        // Stored-only human readable timestamp.
        doc.add(new StoredField(LogFields.TIMESTAMP, timestamp == null
                ? Instant.ofEpochMilli(epochMillis).toString()
                : timestamp));

        // FacetsConfig.build must be the final step before writing.
        return facetsConfig.build(doc);
    }

    /** Rebuilds a {@link LogHit} from a stored document. */
    public LogHit toHit(Document doc) {
        return new LogHit(
                doc.get(LogFields.TENANT_ID),
                doc.get(LogFields.TIMESTAMP),
                toEpochMillis(doc.get(LogFields.TIMESTAMP)),
                doc.get(LogFields.LEVEL),
                doc.get(LogFields.SERVICE),
                doc.get(LogFields.MESSAGE),
                doc.get(LogFields.HOST),
                doc.get(LogFields.TRACE_ID),
                doc.get(LogFields.ERROR_CODE),
                parseStoredInt(doc.get(LogFields.RESPONSE_TIME)));
    }

    /** Converts a stored {@link LogHit} into the protobuf representation. */
    public LogRequest toProto(LogHit hit) {
        return LogRequest.newBuilder()
                .setTenantId(nullToEmpty(hit.tenantId()))
                .setTimestamp(nullToEmpty(hit.timestamp()))
                .setLevel(nullToEmpty(hit.level()))
                .setService(nullToEmpty(hit.service()))
                .setMessage(nullToEmpty(hit.message()))
                .setHost(nullToEmpty(hit.host()))
                .setTraceId(nullToEmpty(hit.traceId()))
                .setErrorCode(nullToEmpty(hit.errorCode()))
                .setResponseTime(hit.responseTime())
                .build();
    }

    private static int parseStoredInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Parses a variety of timestamp shapes to epoch millis, defaulting to "now"
     * when the value is missing or unparseable. Accepts ISO-8601 (with or
     * without zone) and a bare number interpreted as epoch millis.
     */
    public static long toEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return System.currentTimeMillis();
        }
        String value = timestamp.trim();

        // Bare epoch millis.
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try local variant below
        }
        try {
            return LocalDateTime.parse(value, ISO)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // give up
        }
        return System.currentTimeMillis();
    }
}
