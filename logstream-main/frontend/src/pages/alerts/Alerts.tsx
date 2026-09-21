
import { useEffect, useState } from "react";
import "./Alerts.css";

const API_BASE = "http://localhost:8080";

type AlertRule = {
  id?: number;
  name: string;
  service: string;
  level: string;
  threshold: number;
  windowMinutes: number;
  enabled: boolean;
};

type CurrentAlert = {
  id?: number;
  ruleName?: string;
  service?: string;
  level?: string;
  count?: number;
  threshold?: number;
  message?: string;
  triggeredAt?: string;
};

export default function Alerts() {
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [alerts, setAlerts] = useState<CurrentAlert[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const [form, setForm] = useState<AlertRule>({
    name: "",
    service: "",
    level: "ERROR",
    threshold: 5,
    windowMinutes: 5,
    enabled: true,
  });

  const loadData = async () => {
    try {
      setLoading(true);
      setError("");

      const [rulesResponse, alertsResponse] = await Promise.all([
        fetch(`${API_BASE}/api/alerts/rules`),
        fetch(`${API_BASE}/api/alerts/current`),
      ]);

      if (!rulesResponse.ok) {
        throw new Error("Failed to load alert rules");
      }

      if (!alertsResponse.ok) {
        throw new Error("Failed to load current alerts");
      }

      const rulesData = await rulesResponse.json();
      const alertsData = await alertsResponse.json();

      setRules(Array.isArray(rulesData) ? rulesData : []);
      setAlerts(Array.isArray(alertsData) ? alertsData : []);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to connect to alert service"
      );
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();

    const timer = window.setInterval(loadData, 10000);

    return () => window.clearInterval(timer);
  }, []);

  const createRule = async () => {
    if (!form.name.trim()) {
      setError("Alert name is required");
      return;
    }

    if (!form.service.trim()) {
      setError("Service is required");
      return;
    }

    try {
      setError("");

      const response = await fetch(`${API_BASE}/api/alerts/rules`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(form),
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || "Failed to create alert rule");
      }

      setForm({
        name: "",
        service: "",
        level: "ERROR",
        threshold: 5,
        windowMinutes: 5,
        enabled: true,
      });

      await loadData();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Failed to create alert rule"
      );
    }
  };

  const toggleRule = async (rule: AlertRule) => {
    if (!rule.id) return;

    try {
      const response = await fetch(
        `${API_BASE}/api/alerts/rules/${rule.id}`,
        {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            ...rule,
            enabled: !rule.enabled,
          }),
        }
      );

      if (!response.ok) {
        throw new Error("Failed to update alert rule");
      }

      await loadData();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Failed to update alert rule"
      );
    }
  };

  const deleteRule = async (id?: number) => {
    if (!id) return;

    if (!window.confirm("Delete this alert rule?")) {
      return;
    }

    try {
      const response = await fetch(
        `${API_BASE}/api/alerts/rules/${id}`,
        {
          method: "DELETE",
        }
      );

      if (!response.ok) {
        throw new Error("Failed to delete alert rule");
      }

      await loadData();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Failed to delete alert rule"
      );
    }
  };

  return (
    <div className="alerts-page">
      <div className="page-title">
        <div>
          <h1>Alerts</h1>
          <p>Configure log thresholds and monitor triggered alerts.</p>
        </div>

        <button className="refresh-btn" onClick={loadData}>
          {loading ? "Refreshing..." : "Refresh"}
        </button>
      </div>

      {error && <div className="alert-error">{error}</div>}

      <div className="alert-summary">
        <div className="alert-summary-card">
          <span>Alert Rules</span>
          <strong>{rules.length}</strong>
        </div>

        <div className="alert-summary-card danger">
          <span>Active Alerts</span>
          <strong>{alerts.length}</strong>
        </div>

        <div className="alert-summary-card">
          <span>Enabled Rules</span>
          <strong>
            {rules.filter((rule) => rule.enabled).length}
          </strong>
        </div>
      </div>

      <div className="alert-grid">
        <section className="alert-panel">
          <div className="panel-title">
            <h2>Create Alert Rule</h2>
          </div>

          <div className="alert-form">
            <label>
              Alert Name
              <input
                value={form.name}
                placeholder="High Error Rate"
                onChange={(e) =>
                  setForm({
                    ...form,
                    name: e.target.value,
                  })
                }
              />
            </label>

            <label>
              Service
              <input
                value={form.service}
                placeholder="billing-api"
                onChange={(e) =>
                  setForm({
                    ...form,
                    service: e.target.value,
                  })
                }
              />
            </label>

            <label>
              Log Level
              <select
                value={form.level}
                onChange={(e) =>
                  setForm({
                    ...form,
                    level: e.target.value,
                  })
                }
              >
                <option value="ERROR">ERROR</option>
                <option value="WARNING">WARNING</option>
                <option value="INFO">INFO</option>
              </select>
            </label>

            <label>
              Threshold
              <input
                type="number"
                min="1"
                value={form.threshold}
                onChange={(e) =>
                  setForm({
                    ...form,
                    threshold: Number(e.target.value),
                  })
                }
              />
            </label>

            <label>
              Time Window (minutes)
              <input
                type="number"
                min="1"
                value={form.windowMinutes}
                onChange={(e) =>
                  setForm({
                    ...form,
                    windowMinutes: Number(e.target.value),
                  })
                }
              />
            </label>

            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) =>
                  setForm({
                    ...form,
                    enabled: e.target.checked,
                  })
                }
              />
              Enabled
            </label>

            <button className="create-btn" onClick={createRule}>
              Create Alert
            </button>
          </div>
        </section>

        <section className="alert-panel">
          <div className="panel-title">
            <h2>Current Alerts</h2>

            <span className="active-count">
              {alerts.length} active
            </span>
          </div>

          {alerts.length === 0 ? (
            <div className="alert-empty">
              <div>✓</div>
              <strong>No active alerts</strong>
              <span>
                Everything is currently within configured thresholds.
              </span>
            </div>
          ) : (
            <div className="current-alerts">
              {alerts.map((alert, index) => (
                <div className="current-alert" key={alert.id ?? index}>
                  <div className="alert-icon">!</div>

                  <div className="current-alert-content">
                    <strong>
                      {alert.ruleName || "Alert Triggered"}
                    </strong>

                    <span>
                      {alert.message ||
                        `${alert.service || "service"} has exceeded the configured threshold.`}
                    </span>

                    <small>
                      {alert.level || "ERROR"} · Count:{" "}
                      {alert.count ?? 0} / Threshold:{" "}
                      {alert.threshold ?? 0}
                    </small>

                    {alert.triggeredAt && (
                      <small>{alert.triggeredAt}</small>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      <section className="alert-panel rules-panel">
        <div className="panel-title">
          <h2>Alert Rules</h2>
          <span>{rules.length} rules</span>
        </div>

        {rules.length === 0 ? (
          <div className="alert-empty">
            <strong>No alert rules configured</strong>
            <span>Create your first rule above.</span>
          </div>
        ) : (
          <div className="rules-table-wrap">
            <table className="rules-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Service</th>
                  <th>Level</th>
                  <th>Threshold</th>
                  <th>Window</th>
                  <th>Status</th>
                  <th>Action</th>
                </tr>
              </thead>

              <tbody>
                {rules.map((rule, index) => (
                  <tr key={rule.id ?? index}>
                    <td>{rule.name}</td>

                    <td>
                      <span className="service-name">
                        {rule.service}
                      </span>
                    </td>

                    <td>
                      <span
                        className={`level-badge ${rule.level.toLowerCase()}`}
                      >
                        {rule.level}
                      </span>
                    </td>

                    <td>{rule.threshold}</td>

                    <td>{rule.windowMinutes} min</td>

                    <td>
                      <button
                        className={`rule-status ${
                          rule.enabled ? "enabled" : "disabled"
                        }`}
                        onClick={() => toggleRule(rule)}
                      >
                        {rule.enabled ? "Enabled" : "Disabled"}
                      </button>
                    </td>

                    <td>
                      <button
                        className="delete-btn"
                        onClick={() => deleteRule(rule.id)}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

