import { useCallback, useEffect, useState } from "react";
import {
  Database,
  Info,
  TriangleAlert,
  CircleAlert,
} from "lucide-react";

import "./Overview.css";
import LogVolumeChart from "../../components/charts/LogVolumeChart";
import {
  getHistogram,
  getLogCount,
  getStats,
  searchLogs,
  type HistogramBucket,
  type LogItem,
  type LogStats,
} from "../../services/api";
import { Link } from "react-router-dom";

const LEVEL_CLASS: Record<string, string> = {
  ERROR: "error",
  WARN: "warning",
  WARNING: "warning",
  INFO: "info",
  DEBUG: "info",
};

function Overview() {
  const [totalLogs, setTotalLogs] = useState(0);
  const [stats, setStats] = useState<LogStats>({});
  const [buckets, setBuckets] = useState<HistogramBucket[]>([]);
  const [recent, setRecent] = useState<LogItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadStats = useCallback(async () => {
    try {
      const [count, logStats, histogram, recentResult] = await Promise.all([
        getLogCount(),
        getStats(),
        getHistogram("", "5m", 60),
        searchLogs("", "", "", 8, 0),
      ]);

      setTotalLogs(count);
      setStats(logStats);
      setBuckets(histogram.buckets);
      setRecent(recentResult.logs);
      setError("");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to load log statistics"
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Defer the initial load so the effect does not call setState
    // synchronously (which would trigger a cascading render on mount).
    const kickoff = window.setTimeout(loadStats, 0);
    const timer = window.setInterval(loadStats, 15000);
    return () => {
      window.clearTimeout(kickoff);
      window.clearInterval(timer);
    };
  }, [loadStats]);

  const warnings = Number(stats.warning ?? stats.warn ?? 0);
  const errors = Number(stats.error ?? 0);

  return (
    <div className="overview-page">
      <div className="overview-header">
        <h1>Overview</h1>
        <p>Monitor your logs and system activity.</p>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="stats-grid">
        {/* TOTAL LOGS */}
        <div className="stat-card">
          <div className="stat-top">
            <span className="stat-label">Total Logs</span>
            <Database size={20} className="stat-icon total" />
          </div>

          <h2>{loading ? "Loading..." : totalLogs.toLocaleString()}</h2>
        </div>

        {/* INFO */}
        <div className="stat-card">
          <div className="stat-top">
            <span className="stat-label">Info</span>
            <Info size={20} className="stat-icon info" />
          </div>

          <h2>
            {loading
              ? "Loading..."
              : Number(stats.info ?? 0).toLocaleString()}
          </h2>
        </div>

        {/* WARNINGS */}
        <div className="stat-card">
          <div className="stat-top">
            <span className="stat-label">Warnings</span>
            <TriangleAlert size={20} className="stat-icon warning" />
          </div>

          <h2>{loading ? "Loading..." : warnings.toLocaleString()}</h2>
        </div>

        {/* ERRORS */}
        <div className="stat-card">
          <div className="stat-top">
            <span className="stat-label">Errors</span>
            <CircleAlert size={20} className="stat-icon error" />
          </div>

          <h2>{loading ? "Loading..." : errors.toLocaleString()}</h2>
        </div>
      </div>

      <div className="chart-section">
        <div className="section-title">
          <h2>Log Volume</h2>
          <p>Log activity over the last hour.</p>
        </div>

        <LogVolumeChart buckets={buckets} />
      </div>

      <div className="recent-logs">
        <div className="section-header">
          <div>
            <h2>Recent Logs</h2>
            <p>Latest activity across your services.</p>
          </div>

          <Link to="/logs">
            <button>View All Logs</button>
          </Link>
        </div>

        <div className="log-header">
          <span>Time</span>
          <span>Level</span>
          <span>Service</span>
          <span>Message</span>
          <span>Host</span>
        </div>

        {recent.length === 0 ? (
          <div className="log-row">
            <span className="log-message">
              {loading ? "Loading recent logs…" : "No logs ingested yet."}
            </span>
          </div>
        ) : (
          recent.map((log, index) => (
            <div className="log-row" key={log.id ?? `${log.timestamp}-${index}`}>
              <span className="log-time">
                {log.timestamp
                  ? new Date(log.timestamp).toLocaleTimeString()
                  : "-"}
              </span>
              <span
                className={`log-level ${LEVEL_CLASS[(log.level || "").toUpperCase()] ?? "info"
                  }`}
              >
                {log.level || "INFO"}
              </span>
              <span className="log-service">{log.service}</span>
              <span className="log-message">{log.message}</span>
              <span className="log-host">{log.host}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export default Overview;
