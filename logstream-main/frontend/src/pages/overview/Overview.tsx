import { useEffect, useState } from "react";
import {
  Database,
  Info,
  TriangleAlert,
  CircleAlert,
} from "lucide-react";

import "./Overview.css";
import LogVolumeChart from "../../components/charts/LogVolumeChart";
import { getStats, getLogCount, type LogStats } from "../../services/api";

function Overview() {
  const [totalLogs, setTotalLogs] = useState(0);
  const [stats, setStats] = useState<LogStats>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadStats = async () => {
    try {
      setLoading(true);
      setError("");

      const [count, logStats] = await Promise.all([
        getLogCount(),
        getStats(),
      ]);

      setTotalLogs(count);
      setStats(logStats);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to load log statistics"
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadStats();
  }, []);

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

          <h2>
            {loading ? "Loading..." : totalLogs.toLocaleString()}
          </h2>
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

          <h2>
            {loading
              ? "Loading..."
              : Number(stats.warning ?? stats.warn ?? 0).toLocaleString()}
          </h2>
        </div>

        {/* ERRORS */}
        <div className="stat-card">
          <div className="stat-top">
            <span className="stat-label">Errors</span>
            <CircleAlert size={20} className="stat-icon error" />
          </div>

          <h2>
            {loading
              ? "Loading..."
              : Number(stats.error ?? 0).toLocaleString()}
          </h2>
        </div>
      </div>

      <div className="chart-section">
        <div className="section-title">
          <h2>Log Volume</h2>
          <p>Log activity over the last few minutes.</p>
        </div>

        <LogVolumeChart />
      </div>

      <div className="recent-logs">
        <div className="section-header">
          <div>
            <h2>Recent Logs</h2>
            <p>Latest activity across your services.</p>
          </div>

          <button>View All Logs</button>
        </div>

        <div className="log-header">
          <span>Time</span>
          <span>Level</span>
          <span>Service</span>
          <span>Message</span>
          <span>Host</span>
        </div>

        <div className="log-row">
          <span className="log-time">14:32:08</span>
          <span className="log-level error">ERROR</span>
          <span className="log-service">payment-service</span>
          <span className="log-message">
            Payment request failed for transaction ID TXN-48291
          </span>
          <span className="log-host">server-01</span>
        </div>

        <div className="log-row">
          <span className="log-time">14:31:52</span>
          <span className="log-level warning">WARN</span>
          <span className="log-service">api-gateway</span>
          <span className="log-message">
            High response time detected on /api/orders
          </span>
          <span className="log-host">server-02</span>
        </div>

        <div className="log-row">
          <span className="log-time">14:31:40</span>
          <span className="log-level info">INFO</span>
          <span className="log-service">auth-service</span>
          <span className="log-message">
            User authentication completed successfully
          </span>
          <span className="log-host">server-01</span>
        </div>

        <div className="log-row">
          <span className="log-time">14:31:25</span>
          <span className="log-level error">ERROR</span>
          <span className="log-service">database</span>
          <span className="log-message">
            Connection timeout while accessing PostgreSQL
          </span>
          <span className="log-host">server-03</span>
        </div>
      </div>
    </div>
  );
}

export default Overview;