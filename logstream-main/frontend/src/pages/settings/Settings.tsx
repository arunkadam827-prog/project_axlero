
import { useState } from "react";
import "./Settings.css";

export default function Settings() {
  const [darkMode, setDarkMode] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState("10");
  const [notifications, setNotifications] = useState(true);

  const saveSettings = () => {
    localStorage.setItem(
      "logstream-settings",
      JSON.stringify({
        darkMode,
        autoRefresh,
        refreshInterval,
        notifications,
      })
    );

    alert("Settings saved successfully");
  };

  return (
    <div className="settings-page">
      <div className="settings-header">
        <div>
          <h1>Settings</h1>
          <p>Manage your LogStream dashboard preferences.</p>
        </div>

        <button className="save-settings" onClick={saveSettings}>
          Save Settings
        </button>
      </div>

      <div className="settings-container">
        <section className="settings-card">
          <div className="settings-card-header">
            <h2>Appearance</h2>
            <span>Dashboard interface</span>
          </div>

          <div className="setting-row">
            <div>
              <strong>Dark Mode</strong>
              <p>Use the dark interface for LogStream.</p>
            </div>

            <button
              className={`toggle ${darkMode ? "on" : ""}`}
              onClick={() => setDarkMode(!darkMode)}
            >
              <span />
            </button>
          </div>
        </section>

        <section className="settings-card">
          <div className="settings-card-header">
            <h2>Log Monitoring</h2>
            <span>Live log behaviour</span>
          </div>

          <div className="setting-row">
            <div>
              <strong>Auto Refresh</strong>
              <p>Automatically refresh dashboard log information.</p>
            </div>

            <button
              className={`toggle ${autoRefresh ? "on" : ""}`}
              onClick={() => setAutoRefresh(!autoRefresh)}
            >
              <span />
            </button>
          </div>

          <div className="setting-row">
            <div>
              <strong>Refresh Interval</strong>
              <p>Time between automatic dashboard updates.</p>
            </div>

            <select
              value={refreshInterval}
              onChange={(e) => setRefreshInterval(e.target.value)}
            >
              <option value="5">5 seconds</option>
              <option value="10">10 seconds</option>
              <option value="30">30 seconds</option>
              <option value="60">60 seconds</option>
            </select>
          </div>
        </section>

        <section className="settings-card">
          <div className="settings-card-header">
            <h2>Notifications</h2>
            <span>Alert notifications</span>
          </div>

          <div className="setting-row">
            <div>
              <strong>Alert Notifications</strong>
              <p>
                Receive notifications when an alert rule is triggered.
              </p>
            </div>

            <button
              className={`toggle ${notifications ? "on" : ""}`}
              onClick={() => setNotifications(!notifications)}
            >
              <span />
            </button>
          </div>
        </section>

        <section className="settings-card">
          <div className="settings-card-header">
            <h2>Backend Connection</h2>
            <span>LogStream services</span>
          </div>

          <div className="connection-row">
            <span>REST API</span>
            <div>
              <span className="status-dot" />
              <strong>http://localhost:8080</strong>
            </div>
          </div>

          <div className="connection-row">
            <span>gRPC</span>
            <div>
              <span className="status-dot" />
              <strong>localhost:9090</strong>
            </div>
          </div>

          <div className="connection-row">
            <span>WebSocket</span>
            <div>
              <span className="status-dot" />
              <strong>ws://localhost:8080/ws/logs</strong>
            </div>
          </div>
        </section>

        <section className="settings-card about-card">
          <div className="settings-card-header">
            <h2>About LogStream</h2>
            <span>System information</span>
          </div>

          <div className="about-content">
            <strong>LogStream</strong>
            <p>Distributed Log Analytics & Alerting Platform</p>
            <p>Observability & Big Data</p>
            <small>Backend: Spring Boot · PostgreSQL · gRPC · Lucene</small>
          </div>
        </section>
      </div>
    </div>
  );
}

