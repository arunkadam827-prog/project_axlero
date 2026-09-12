import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./App.css";

const API_BASE = "http://localhost:8080";
const WS_URL = "ws://localhost:8080/ws/logs";

const emptyStats = {
  totalLogs: 0,
  info: 0,
  warning: 0,
  error: 0,
};

function normalizeLog(log) {
  return {
    id: log.id ?? crypto.randomUUID(),
    timestamp: log.timestamp ?? log.createdAt ?? new Date().toISOString(),
    level: String(log.level ?? "INFO").toUpperCase(),
    service: log.service ?? "unknown-service",
    message: log.message ?? "",
    host: log.host ?? "unknown",
  };
}

async function getJson(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.json();
}

function App() {
  const [page, setPage] = useState("overview");
  const [stats, setStats] = useState(emptyStats);

  // Historical/search logs
  const [logs, setLogs] = useState([]);

  // Live WebSocket logs
  const [liveLogs, setLiveLogs] = useState([]);

  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("ALL");
  const [service, setService] = useState("ALL");

  const [backendOnline, setBackendOnline] = useState(false);
  const [wsOnline, setWsOnline] = useState(false);
  const [livePaused, setLivePaused] = useState(false);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const socketRef = useRef(null);

  // ---------------------------------------------------------
  // LOAD DASHBOARD
  // ---------------------------------------------------------
  const loadDashboard = useCallback(async () => {
    try {
      setLoading(true);
      setError("");

      const [countData, statsData] = await Promise.all([
        getJson("/api/logs/count"),
        getJson("/api/logs/stats"),
      ]);

      setStats({
        ...emptyStats,
        ...statsData,
        totalLogs: countData.totalLogs ?? statsData.totalLogs ?? 0,
      });

      setBackendOnline(true);

      try {
        const data = await getJson("/api/logs?limit=100");
        const receivedLogs = data.logs ?? data ?? [];
        setLogs(receivedLogs.map(normalizeLog));
      } catch (err) {
        console.log("Log list endpoint unavailable:", err.message);
      }
    } catch (err) {
      setBackendOnline(false);
      setError(`Backend connection failed: ${err.message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  // ---------------------------------------------------------
  // SEARCH LOGS
  // ---------------------------------------------------------
  const searchLogs = useCallback(async () => {
    try {
      setError("");

      const params = new URLSearchParams();
      if (query.trim()) {
        params.set("q", query.trim());
      }
      if (level !== "ALL") {
        params.set("level", level);
      }
      if (service !== "ALL") {
        params.set("service", service);
      }
      params.set("limit", "100");

      const data = await getJson(
        `/api/logs/search?${params.toString()}`
      );
      const receivedLogs = data.logs ?? data ?? [];
      setLogs(receivedLogs.map(normalizeLog));
      setBackendOnline(true);
    } catch (err) {
      setError(`Search API failed: ${err.message}`);
      setBackendOnline(false);
    }
  }, [query, level, service]);

  // ---------------------------------------------------------
  // INITIAL DASHBOARD LOAD
  // ---------------------------------------------------------
  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  // ---------------------------------------------------------
  // SEARCH WHEN FILTER CHANGES
  // ---------------------------------------------------------
  useEffect(() => {
    if (
      page === "logs" &&
      (query.trim() || level !== "ALL" || service !== "ALL")
    ) {
      const timer = setTimeout(() => {
        searchLogs();
      }, 250);

      return () => clearTimeout(timer);
    }

    if (
      page === "logs" &&
      !query.trim() &&
      level === "ALL" &&
      service === "ALL"
    ) {
      loadDashboard();
    }
  }, [
    page,
    query,
    level,
    service,
    searchLogs,
    loadDashboard,
  ]);

  // ---------------------------------------------------------
  // WEBSOCKET LIVE TAIL
  // ---------------------------------------------------------
  useEffect(() => {
    let reconnectTimer;
    let stopped = false;

    const connectWebSocket = () => {
      if (stopped) return;

      const ws = new WebSocket(WS_URL);
      socketRef.current = ws;

      ws.onopen = () => {
        console.log("WebSocket connected");
        setWsOnline(true);
      };

      ws.onclose = () => {
        console.log("WebSocket disconnected");
        setWsOnline(false);
        if (!stopped) {
          reconnectTimer = setTimeout(
            connectWebSocket,
            3000
          );
        }
      };

      ws.onerror = () => {
        setWsOnline(false);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);

          // Ignore WebSocket connection status messages
          if (data.type === "connection") {
            return;
          }

          const incoming = normalizeLog(data);

          setLiveLogs((current) => [
            incoming,
            ...current,
          ].slice(0, 200));

          // Update dashboard statistics
          setStats((current) => {
            const levelKey = incoming.level.toLowerCase();
            return {
              ...current,
              totalLogs: current.totalLogs + 1,
              [levelKey]: (current[levelKey] ?? 0) + 1,
            };
          });
        } catch (err) {
          console.log(
            "Invalid WebSocket message:",
            err
          );
        }
      };
    };

    connectWebSocket();

    return () => {
      stopped = true;
      clearTimeout(reconnectTimer);
      if (socketRef.current) {
        socketRef.current.close();
      }
      socketRef.current = null;
    };
  }, []);

  // ---------------------------------------------------------
  // SERVICES
  // ---------------------------------------------------------
  const services = useMemo(() => {
    return [
      "ALL",
      ...new Set(
        logs
          .map((log) => log.service)
          .filter(Boolean)
      ),
    ];
  }, [logs]);

  // ---------------------------------------------------------
  // LOCAL FILTER
  // ---------------------------------------------------------
  const filteredLogs = useMemo(() => {
    return logs.filter(
      (log) =>
        (level === "ALL" || log.level === level) &&
        (service === "ALL" || log.service === service)
    );
  }, [logs, level, service]);

  // ---------------------------------------------------------
  // LIVE FILTER
  // ---------------------------------------------------------
  const visibleLiveLogs = useMemo(() => {
    return liveLogs.filter(
      (log) =>
        (level === "ALL" || log.level === level) &&
        (service === "ALL" || log.service === service)
    );
  }, [liveLogs, level, service]);

  // ---------------------------------------------------------
  // RENDER
  // ---------------------------------------------------------
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            LS
          </div>
          <div>
            <strong>LogStream</strong>
            <span>Observability</span>
          </div>
        </div>

        <nav>
          {[
            ["overview", "Overview", "⌂"],
            ["logs", "Search Logs", "⌕"],
            ["live", "Live Tail", "◉"],
            ["analytics", "Analytics", "▥"],
            ["alerts", "Alerting", "⚡"],
            ["services", "Services", "▦"],
            ["settings", "Settings", "⚙"],
          ].map(([id, label, icon]) => (
            <button
              key={id}
              className={
                page === id ? "nav-item active" : "nav-item"
              }
              onClick={() => setPage(id)}
            >
              <span>{icon}</span>
              {label}
            </button>
          ))}
        </nav>

        <div className="connection-box">
          <div>
            <span
              className={
                backendOnline ? "dot online" : "dot"
              }
            />
            REST :8080
          </div>
          <div>
            <span
              className={
                wsOnline ? "dot online" : "dot"
              }
            />
            WS /ws/logs
          </div>
          <small>
            gRPC ingestion: :9090
          </small>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <h1>
              {pageTitle(page)}
            </h1>
            <p>
              Distributed Log Analytics & Alerting Platform
            </p>
          </div>

          <div className="top-actions">
            <span
              className={
                backendOnline ? "status online-status" : "status"
              }
            >
              {backendOnline ? "● Backend Connected" : "● Backend Offline"}
            </span>
            <button
              className="refresh"
              onClick={loadDashboard}
            >
              ↻ Refresh
            </button>
          </div>
        </header>

        {error && (
          <div className="error-banner">
            {error}
          </div>
        )}

        {page === "overview" && (
          <Overview
            stats={stats}
            logs={logs}
            loading={loading}
            setPage={setPage}
          />
        )}

        {page === "logs" && (
          <LogsPage
            logs={filteredLogs}
            query={query}
            setQuery={setQuery}
            level={level}
            setLevel={setLevel}
            service={service}
            setService={setService}
            services={services}
            onSearch={searchLogs}
          />
        )}

        {page === "live" && (
          <LivePage
            logs={visibleLiveLogs}
            connected={wsOnline}
            paused={livePaused}
            setPaused={setLivePaused}
            clearLogs={() => setLiveLogs([])}
          />
        )}

        {page === "analytics" && (
          <Analytics
            stats={stats}
            logs={logs}
          />
        )}

        {page === "alerts" && (
          <Alerts />
        )}

        {page === "services" && (
          <Services logs={logs} />
        )}

        {page === "settings" && (
          <Settings />
        )}
      </main>
    </div>
  );
}

// =========================================================
// PAGE TITLE
// =========================================================
function pageTitle(page) {
  return {
    overview: "System Overview",
    logs: "Search Logs",
    live: "Live Tail",
    analytics: "Analytics",
    alerts: "Alerting Engine",
    services: "Services",
    settings: "Settings",
  }[page];
}

// =========================================================
// OVERVIEW
// =========================================================
function Overview({
  stats,
  logs,
  loading,
  setPage,
}) {
  return (
    <section>
      <div className="cards">
        <StatCard
          label="Total Logs"
          value={
            loading ? "…" : stats.totalLogs
          }
          icon="▤"
        />
        <StatCard
          label="INFO"
          value={stats.info}
          icon="●"
        />
        <StatCard
          label="WARNING"
          value={stats.warning}
          icon="▲"
        />
        <StatCard
          label="ERROR"
          value={stats.error}
          icon="!"
          danger
        />
      </div>

      <div className="grid two">
        <Panel title="Log Distribution">
          <div className="bars">
            {[
              ["INFO", stats.info],
              ["WARNING", stats.warning],
              ["ERROR", stats.error],
            ].map(([name, value]) => {
              const percentage = Math.min(
                100,
                Math.max(
                  3,
                  (value / Math.max(
                    stats.totalLogs,
                    1
                  )) * 100
                )
              );
              return (
                <div
                  className="bar-row"
                  key={name}
                >
                  <span>{name}</span>
                  <div className="bar-track">
                    <div
                      className={`bar ${name.toLowerCase()}`}
                      style={{
                        width: `${percentage}%`,
                      }}
                    />
                  </div>
                  <b>{value}</b>
                </div>
              );
            })}
          </div>
        </Panel>

        <Panel title="System Status">
          <div className="status-list">
            <StatusRow
              label="REST API"
              value=":8080"
              ok
            />
            <StatusRow
              label="gRPC Ingestion"
              value=":9090"
              ok
            />
            <StatusRow
              label="PostgreSQL"
              value="Connected via backend"
              ok
            />
            <StatusRow
              label="Lucene"
              value="Search index"
              ok
            />
          </div>
        </Panel>
      </div>

      <Panel
        title="Recent Events"
        action={
          <button
            onClick={() => setPage("logs")}
          >
            View all →
          </button>
        }
      >
        <LogTable logs={logs.slice(0, 8)} />
      </Panel>
    </section>
  );
}

// =========================================================
// SEARCH LOGS
// =========================================================
function LogsPage({
  logs,
  query,
  setQuery,
  level,
  setLevel,
  service,
  setService,
  services,
  onSearch,
}) {
  return (
    <section>
      <div className="search-panel">
        <input
          value={query}
          onChange={(e) =>
            setQuery(e.target.value)
          }
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              onSearch();
            }
          }}
          placeholder="Search log message..."
        />
        <select
          value={level}
          onChange={(e) =>
            setLevel(e.target.value)
          }
        >
          <option>ALL</option>
          <option>INFO</option>
          <option>WARNING</option>
          <option>ERROR</option>
        </select>
        <select
          value={service}
          onChange={(e) =>
            setService(e.target.value)
          }
        >
          {services.map((s) => (
            <option key={s}>
              {s}
            </option>
          ))}
        </select>
        <button
          className="primary"
          onClick={onSearch}
        >
          Search
        </button>
      </div>

      <Panel
        title={`${logs.length} results`}
      >
        <LogTable logs={logs} />
      </Panel>
    </section>
  );
}

// =========================================================
// LIVE TAIL
// =========================================================
function LivePage({
  logs,
  connected,
  paused,
  setPaused,
  clearLogs,
}) {
  return (
    <section>
      <div className="live-header">
        <span
          className={
            connected ? "pulse" : "dot"
          }
        />
        {connected
          ? "WebSocket connected — receiving live logs"
          : "WebSocket disconnected"}
      </div>

      <div className="live-controls">
        <button
          className="refresh"
          onClick={() =>
            setPaused(!paused)
          }
        >
          {paused ? "▶ Resume" : "⏸ Pause"}
        </button>
        <button
          className="refresh"
          onClick={clearLogs}
        >
          Clear
        </button>
        <span>
          {logs.length} live events
        </span>
      </div>

      <Panel title="Live events">
        {paused && (
          <div className="empty">
            Live Tail paused.
          </div>
        )}
        {!paused && (
          <LogTable logs={logs.slice(0, 50)} />
        )}
      </Panel>
    </section>
  );
}

// =========================================================
// ANALYTICS
// =========================================================
function Analytics({
  stats,
  logs,
}) {
  const errorRate = stats.totalLogs
    ? (
        (stats.error / stats.totalLogs) *
        100
      ).toFixed(2)
    : "0.00";

  return (
    <section>
      <div className="cards">
        <StatCard
          label="Total Events"
          value={stats.totalLogs}
          icon="▤"
        />
        <StatCard
          label="Error Rate"
          value={`${errorRate}%`}
          icon="!"
          danger
        />
        <StatCard
          label="Services Seen"
          value={
            new Set(
              logs.map(
                (x) => x.service
              )
            ).size
          }
          icon="▦"
        />
        <StatCard
          label="Indexed Logs"
          value={stats.totalLogs}
          icon="⌕"
        />
      </div>

      <Panel title="Level Analytics">
        <div className="analytics-grid">
          <Metric
            label="INFO"
            value={stats.info}
            total={stats.totalLogs}
          />
          <Metric
            label="WARNING"
            value={stats.warning}
            total={stats.totalLogs}
          />
          <Metric
            label="ERROR"
            value={stats.error}
            total={stats.totalLogs}
          />
        </div>
      </Panel>
    </section>
  );
}

// =========================================================
// ALERTS
// =========================================================
function Alerts() {
  return (
    <section>
      <Panel title="Alerting Engine">
        <div className="empty">
          Alert rules are ready for backend integration.
          <br />
          <small>
            Next backend step: evaluate thresholds and push alert events over WebSocket.
          </small>
        </div>
      </Panel>
    </section>
  );
}

// =========================================================
// SERVICES
// =========================================================
function Services({ logs }) {
  const rows = [
    ...new Set(
      logs.map((x) => x.service)
    ),
  ].map((service) => ({
    service,
    count: logs.filter(
      (x) => x.service === service
    ).length,
    errors: logs.filter(
      (x) =>
        x.service === service &&
        x.level === "ERROR"
    ).length,
  }));

  return (
    <section>
      <Panel title="Services">
        <div className="service-grid">
          {rows.length ? (
            rows.map((x) => (
              <div
                className="service-card"
                key={x.service}
              >
                <b>{x.service}</b>
                <span>
                  {x.count} visible logs
                </span>
                <span>
                  {x.errors} errors
                </span>
              </div>
            ))
          ) : (
            <div className="empty">
              No service logs loaded yet.
            </div>
          )}
        </div>
      </Panel>
    </section>
  );
}

// =========================================================
// SETTINGS
// =========================================================
function Settings() {
  return (
    <section>
      <Panel title="Connection Settings">
        <div className="settings">
          <label>
            REST API
            <input
              value={API_BASE}
              readOnly
            />
          </label>
          <label>
            WebSocket
            <input
              value={WS_URL}
              readOnly
            />
          </label>
          <label>
            gRPC ingestion
            <input
              value="localhost:9090"
              readOnly
            />
          </label>
        </div>
      </Panel>
    </section>
  );
}

// =========================================================
// STAT CARD
// =========================================================
function StatCard({
  label,
  value,
  icon,
  danger,
}) {
  return (
    <div
      className={`stat-card ${
        danger ? "danger" : ""
      }`}
    >
      <span className="stat-icon">
        {icon}
      </span>
      <div>
        <small>{label}</small>
        <strong>{value}</strong>
      </div>
    </div>
  );
}

// =========================================================
// STATUS ROW
// =========================================================
function StatusRow({
  label,
  value,
  ok,
}) {
  return (
    <div className="status-row">
      <span>
        <i
          className={
            ok ? "dot online" : "dot"
          }
        />
        {label}
      </span>
      <b>{value}</b>
    </div>
  );
}

// =========================================================
// METRIC
// =========================================================
function Metric({
  label,
  value,
  total,
}) {
  const pct = total
    ? ((value / total) * 100).toFixed(1)
    : "0.0";

  return (
    <div className="metric">
      <div>
        <b>{label}</b>
        <span>{pct}%</span>
      </div>
      <div className="metric-track">
        <div
          style={{
            width: `${Math.min(
              100,
              Number(pct)
            )}%`,
          }}
        />
      </div>
      <strong>{value}</strong>
    </div>
  );
}

// =========================================================
// PANEL
// =========================================================
function Panel({
  title,
  action,
  children,
}) {
  return (
    <div className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </div>
  );
}

// =========================================================
// LOG TABLE
// =========================================================
function LogTable({ logs }) {
  if (!logs.length) {
    return (
      <div className="empty">
        No logs available yet. Send a log through gRPC and refresh.
      </div>
    );
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Level</th>
            <th>Service</th>
            <th>Message</th>
            <th>Host</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.id}>
              <td className="mono">
                {formatTime(
                  log.timestamp
                )}
              </td>
              <td>
                <span
                  className={`badge ${log.level.toLowerCase()}`}
                >
                  {log.level}
                </span>
              </td>
              <td>
                {log.service}
              </td>
              <td>
                {log.message}
              </td>
              <td className="mono">
                {log.host}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// =========================================================
// FORMAT TIME
// =========================================================
function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(
    date.getTime()
  )
    ? value
    : date.toLocaleString();
}

export default App;