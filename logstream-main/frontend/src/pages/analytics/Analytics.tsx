import { useCallback, useEffect, useMemo, useState } from "react";
import { Activity, AlertCircle, Clock3, Server } from "lucide-react";

import "./Analytics.css";
import ErrorRateChart from "../../components/charts/ErrorRateChart";
import ServiceDistributionChart from "../../components/charts/ServiceDistributionChart";
import {
  getFacets,
  getHistogram,
  getLogCount,
  getStats,
  searchLogs,
  type FacetBucket,
  type HistogramBucket,
  type LogStats,
} from "../../services/api";

interface ServiceRow {
  name: string;
  requests: number;
  avgResponse: number | null;
  errorRate: number;
}

function Analytics() {
  const [totalLogs, setTotalLogs] = useState(0);
  const [stats, setStats] = useState<LogStats>({});
  const [buckets, setBuckets] = useState<HistogramBucket[]>([]);
  const [errorBuckets, setErrorBuckets] = useState<HistogramBucket[]>([]);
  const [serviceFacets, setServiceFacets] = useState<FacetBucket[]>([]);
  const [errorFacets, setErrorFacets] = useState<FacetBucket[]>([]);
  const [avgResponse, setAvgResponse] = useState<number | null>(null);
  const [serviceResponse, setServiceResponse] = useState<Record<string, number>>(
    {}
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [
        count,
        logStats,
        histogram,
        errorHistogram,
        services,
        errorServices,
        recent,
      ] = await Promise.all([
        getLogCount(),
        getStats(),
        getHistogram("", "15m", 360),
        getHistogram("level:ERROR", "15m", 360),
        getFacets("", "service", 10, 60),
        getFacets("level:ERROR", "service", 10, 60),
        searchLogs("", "", "", 500, 0),
      ]);

      setTotalLogs(count);
      setStats(logStats);
      setBuckets(histogram.buckets);
      setErrorBuckets(errorHistogram.buckets);
      setServiceFacets(services);
      setErrorFacets(errorServices);

      const byService: Record<string, { total: number; sum: number }> = {};
      let overallSum = 0;
      let overallCount = 0;

      for (const log of recent.logs) {
        if (log.responseTime == null) continue;
        overallSum += log.responseTime;
        overallCount += 1;

        const key = log.service || "(unknown)";
        if (!byService[key]) byService[key] = { total: 0, sum: 0 };
        byService[key].total += 1;
        byService[key].sum += log.responseTime;
      }

      setAvgResponse(
        overallCount > 0 ? Math.round(overallSum / overallCount) : null
      );

      const responseMap: Record<string, number> = {};
      for (const [key, value] of Object.entries(byService)) {
        responseMap[key] = Math.round(value.sum / value.total);
      }
      setServiceResponse(responseMap);
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load analytics");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Defer the initial load so the effect does not call setState
    // synchronously (which would trigger a cascading render on mount).
    const kickoff = window.setTimeout(load, 0);
    const timer = window.setInterval(load, 20000);
    return () => {
      window.clearTimeout(kickoff);
      window.clearInterval(timer);
    };
  }, [load]);

  const errorCount = Number(stats.error ?? 0);
  const errorRate = totalLogs > 0 ? (errorCount / totalLogs) * 100 : 0;

  const serviceRows: ServiceRow[] = useMemo(() => {
    const errorByService = new Map(
      errorFacets.map((facet) => [facet.key, facet.count])
    );

    return serviceFacets.map((facet) => {
      const errorsForService = errorByService.get(facet.key) ?? 0;
      return {
        name: facet.key || "(unknown)",
        requests: facet.count,
        avgResponse: serviceResponse[facet.key] ?? null,
        errorRate:
          facet.count > 0 ? (errorsForService / facet.count) * 100 : 0,
      };
    });
  }, [serviceFacets, errorFacets, serviceResponse]);

  return (
    <div className="analytics-page">
      {/* Header */}
      <div className="analytics-header">
        <div>
          <h1>Analytics</h1>
          <p>Analyze log patterns, errors, and service performance.</p>
        </div>

        <div className="analytics-actions">
          <button>Last 6 Hours</button>
          <button className="refresh-btn" onClick={load}>
            ↻ {loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
      </div>

      {error && <div className="alert-error">{error}</div>}

      {/* Summary Cards */}
      <div className="analytics-stats">
        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Total Events</span>
            <Activity size={18} />
          </div>

          <h2>{totalLogs.toLocaleString()}</h2>
          <p className="positive">Across all indexed logs</p>
        </div>

        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Error Rate</span>
            <AlertCircle size={18} />
          </div>

          <h2>{errorRate.toFixed(2)}%</h2>
          <p className="negative">{errorCount.toLocaleString()} errors</p>
        </div>

        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Avg. Response</span>
            <Clock3 size={18} />
          </div>

          <h2>{avgResponse != null ? `${avgResponse} ms` : "—"}</h2>
          <p className="positive">From recent sampled logs</p>
        </div>

        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Active Services</span>
            <Server size={18} />
          </div>

          <h2>{serviceFacets.length}</h2>
          <p className="positive">Reporting logs</p>
        </div>
      </div>

      {/* Analytics Charts */}
      <div className="analytics-grid">
        <div className="analytics-section">
          <div className="section-heading">
            <div>
              <h2>Error Rate Trend</h2>
              <p>Error percentage over the last 6 hours.</p>
            </div>
          </div>

          <ErrorRateChart buckets={buckets} errorBuckets={errorBuckets} />
        </div>

        <div className="analytics-section">
          <div className="section-heading">
            <div>
              <h2>Logs by Service</h2>
              <p>Log distribution across services.</p>
            </div>
          </div>

          <ServiceDistributionChart buckets={serviceFacets} />
        </div>
      </div>

      {/* Service Performance */}
      <div className="analytics-section performance-section">
        <div className="section-heading">
          <div>
            <h2>Service Performance</h2>
            <p>Performance overview of your active services.</p>
          </div>
        </div>

        <div className="performance-table">
          <div className="performance-header">
            <span>Service</span>
            <span>Requests</span>
            <span>Avg. Response</span>
            <span>Error Rate</span>
            <span>Status</span>
          </div>

          {serviceRows.length === 0 ? (
            <div className="performance-row">
              <span>{loading ? "Loading services…" : "No services yet"}</span>
            </div>
          ) : (
            serviceRows.map((row, index) => (
              <div className="performance-row" key={row.name ?? index}>
                <span>{row.name}</span>
                <span>{row.requests.toLocaleString()}</span>
                <span>
                  {row.avgResponse != null ? `${row.avgResponse} ms` : "—"}
                </span>
                <span className="error-text">{row.errorRate.toFixed(1)}%</span>
                <span
                  className={`status ${row.errorRate > 5 ? "degraded" : "healthy"
                    }`}
                >
                  {row.errorRate > 5 ? "Degraded" : "Healthy"}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

export default Analytics;
