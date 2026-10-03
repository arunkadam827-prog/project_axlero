package logstream_backend.analytics;

import logstream_backend.search.LogFields;
import logstream_backend.search.LogQueryParser;
import logstream_backend.search.LuceneIndexManager;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TotalHitCountCollector;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation layer powering the ECharts dashboards.
 *
 * <p>
 * Uses Lucene's facet API so distributions and time-series histograms are
 * computed in a single index pass rather than by re-querying per bucket. See
 * {@code docs/IMPLEMENTATION_PLAN.md} week 3 and
 * {@code docs/INDEXING_STRATEGY.md} §3.5.
 * </p>
 */
@Service
public class LogAnalyticsService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private final LuceneIndexManager indexManager;
    private final LogQueryParser queryParser;

    public LogAnalyticsService(
            LuceneIndexManager indexManager,
            LogQueryParser queryParser) {
        this.indexManager = indexManager;
        this.queryParser = queryParser;
    }

    /** Counts documents matching a query within an optional time window. */
    public long count(String tenant, String query, Long fromMillis, Long toMillis)
            throws IOException {

        DirectoryReader reader = indexManager.reader(tenant);
        if (reader == null) {
            return 0;
        }
        IndexSearcher searcher = new IndexSearcher(reader);
        Query scoped = withTimeRange(
                queryParser.parse(query, tenant), fromMillis, toMillis);

        TotalHitCountCollector collector = new TotalHitCountCollector();
        searcher.search(scoped, collector);
        return collector.getTotalHits();
    }

    /** Level/service distribution for a query and window. */
    public List<FacetBucket> countByField(
            String tenant,
            String query,
            String field,
            int topN,
            Long fromMillis,
            Long toMillis) throws IOException {

        DirectoryReader reader = indexManager.reader(tenant);
        if (reader == null) {
            return List.of();
        }

        IndexSearcher searcher = new IndexSearcher(reader);
        Query scoped = withTimeRange(
                queryParser.parse(query, tenant), fromMillis, toMillis);
        FacetsCollector collector = new FacetsCollector();
        searcher.search(scoped, collector);

        try {
            SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                    reader, indexManager.facetsConfig());
            Facets facets = new SortedSetDocValuesFacetCounts(state, collector);
            FacetResult result = facets.getTopChildren(topN, field);

            List<FacetBucket> buckets = new ArrayList<>();
            if (result != null) {
                for (LabelAndValue lv : result.labelValues) {
                    buckets.add(new FacetBucket(lv.label, lv.value.longValue()));
                }
            }
            return buckets;
        } catch (IllegalArgumentException e) {
            // Index has no sorted-set facet data yet (empty/unmigrated index).
            return List.of();
        }
    }

    /**
     * Builds a fixed-interval time-series histogram over {@code epoch_millis}.
     */
    public HistogramResult histogram(
            String tenant,
            String query,
            long fromMillis,
            long toMillis,
            long intervalMillis) throws IOException {

        DirectoryReader reader = indexManager.reader(tenant);
        if (reader == null) {
            return new HistogramResult("", List.of(), 0);
        }

        long interval = Math.max(1_000L, intervalMillis);
        long start = floorToInterval(fromMillis, interval);
        long end = toMillis <= start ? start + interval : toMillis;

        List<LongRange> ranges = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Long> starts = new ArrayList<>();

        for (long cursor = start; cursor < end; cursor += interval) {
            long bucketEnd = cursor + interval;
            String label = HH_MM.format(Instant.ofEpochMilli(cursor));
            ranges.add(new LongRange(label, cursor, true, bucketEnd, false));
            labels.add(label);
            starts.add(cursor);
        }

        IndexSearcher searcher = new IndexSearcher(reader);
        Query scoped = withTimeRange(
                queryParser.parse(query, tenant), fromMillis, toMillis);

        FacetsCollector collector = new FacetsCollector();
        searcher.search(scoped, collector);

        LongRangeFacetCounts facetCounts = new LongRangeFacetCounts(
                LogFields.EPOCH_MILLIS,
                collector,
                ranges.toArray(new LongRange[0]));

        // Lucene 10 removed per-label lookups on range facets:
        // RangeFacetCounts#getSpecificValue throws UnsupportedOperationException.
        // Read the full result in a single pass and map counts back by label.
        Map<String, Long> countsByLabel = new LinkedHashMap<>();
        FacetResult rangeResult = facetCounts.getTopChildren(labels.size(), LogFields.EPOCH_MILLIS);
        if (rangeResult != null) {
            for (LabelAndValue lv : rangeResult.labelValues) {
                countsByLabel.put(lv.label, lv.value.longValue());
            }
        }

        List<HistogramBucket> buckets = new ArrayList<>(labels.size());
        long total = 0;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            long value = Math.max(0, countsByLabel.getOrDefault(label, 0L));
            total += value;
            buckets.add(new HistogramBucket(label, starts.get(i), value));
        }

        return new HistogramResult(interval + "ms", buckets, total);
    }

    /** Dashboard summary: total docs plus per-level counts. */
    public StatsResult stats(String tenant) throws IOException {

        DirectoryReader reader = indexManager.reader(tenant);
        if (reader == null) {
            return new StatsResult(0, Map.of());
        }

        Map<String, Long> byLevel = new LinkedHashMap<>();
        IndexSearcher searcher = new IndexSearcher(reader);

        for (String level : List.of("INFO", "WARN", "ERROR", "DEBUG")) {
            Query q = queryParser.parseWithFilters(
                    "level:" + level, tenant, null, null);
            TotalHitCountCollector collector = new TotalHitCountCollector();
            searcher.search(q, collector);
            byLevel.put(level, (long) collector.getTotalHits());
        }

        return new StatsResult(reader.numDocs(), byLevel);
    }

    private Query withTimeRange(Query base, Long from, Long to) {
        if (from == null && to == null) {
            return base;
        }
        long lower = from == null ? Long.MIN_VALUE : from;
        long upper = to == null ? Long.MAX_VALUE : to;

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(base, BooleanClause.Occur.MUST);
        builder.add(
                LongPoint.newRangeQuery(LogFields.EPOCH_MILLIS, lower, upper),
                BooleanClause.Occur.FILTER);
        return builder.build();
    }

    private static long floorToInterval(long value, long interval) {
        return value - Math.floorMod(value, interval);
    }
}
