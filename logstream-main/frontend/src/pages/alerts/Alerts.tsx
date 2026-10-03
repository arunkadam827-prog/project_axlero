import { useCallback, useEffect, useState } from "react";

import {
  createAlertRule,
  deleteAlertRule,
  evaluateAlerts,
  getAlertHistory,
  getAlertRules,
  getCurrentAlerts,
  updateAlertRule,
  type AlertEvent,
  type AlertRule,
  type CurrentAlert,
} from "../../services/api";
import "./Alerts.css";

const EMPTY_FORM: AlertRule = {
  name: "",
  service: "",
  level: "",
  query: "",
  severity: "warning",
  channel: "email",
  webhookUrl: "",
  threshold: 5,
  timeWindowMinutes: 5,
  enabled: true,
};

function levelClass(level?: string): string {
  return (level || "info").toLowerCase();
}

export default function Alerts() {
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [alerts, setAlerts] = useState<CurrentAlert[]>([]);
  const [history, setHistory] = useState<AlertEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [form, setForm] = useState<AlertRule>(EMPTY_FORM);

  const loadData = useCallback(async () => {
    try {
      const [rulesData, currentData, historyData] = await Promise.all([
        getAlertRules(),
        getCurrentAlerts(),
        getAlertHistory(),
      ]);

      setRules(rulesData);
      setAlerts(currentData.alerts);
      setHistory(historyData);
      setError("");
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to connect to alert service"
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Defer the initial load so the effect does not call setState
    // synchronously (which would trigger a cascading render on mount).
    const kickoff = window.setTimeout(loadData, 0);
    const timer = window.setInterval(loadData, 10000);
    return () => {
      window.clearTimeout(kickoff);
      window.clearInterval(timer);
    };
  }, [loadData]);

  const createRule = async () => {
    if (!form.name.trim()) {
      setError("Alert name is required");
      return;
    }

    if (!form.query.trim() && !form.service.trim() && !form.level) {
      setError("Provide a query or at least a service/level filter");
      return;
    }

    try {
      setError("");
      await createAlertRule(form);
      setForm(EMPTY_FORM);
      await loadData();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to create alert rule"
      );
    }
  };

  const toggleRule = async (rule: AlertRule) => {
    if (!rule.id) return;

    try {
      await updateAlertRule(rule.id, { ...rule, enabled: !rule.enabled });
      await loadData();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to update alert rule"
      );
    }
  };

  const deleteRule = async (id?: number) => {
    if (!id) return;
    if (!window.confirm("Delete this alert rule?")) return;

    try {
      await deleteAlertRule(id);
      await loadData();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to delete alert rule"
      );
    }
  };

  const runEvaluation = async () => {
    try {
      await evaluateAlerts();
      await loadData();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to evaluate alerts"
      );
    }
  };

  return (
    <div className="alerts-page">
      <div className="page-title">
        <div>
          <h1>Alerts</h1>
          <p>
            Query-based alerting rules evaluated against your tenant's live log
            index.
          </p>
        </div>

        <div className="page-actions">
          <button className="refresh-btn" onClick={runEvaluation}>
            Evaluate now
          </button>
          <button className="refresh-btn" onClick={loadData}>
            {loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
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
          <strong>{rules.filter((rule) => rule.enabled).length}</strong>
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
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </label>

            <label>
              Query (LogStream expression)
              <input
                value={form.query}
                placeholder="level:ERROR AND response_time > 1000"
                onChange={(e) => setForm({ ...form, query: e.target.value })}
              />
            </label>

            <label>
              Service (optional)
              <input
                value={form.service}
                placeholder="billing-api"
                onChange={(e) => setForm({ ...form, service: e.target.value })}
              />
            </label>

            <label>
              Log Level (optional)
              <select
                value={form.level}
                onChange={(e) => setForm({ ...form, level: e.target.value })}
              >
                <option value="">Any</option>
                <option value="ERROR">ERROR</option>
                <option value="WARNING">WARNING</option>
                <option value="INFO">INFO</option>
              </select>
            </label>

            <label>
              Severity
              <select
                value={form.severity}
                onChange={(e) => setForm({ ...form, severity: e.target.value })}
              >
                <option value="info">info</option>
                <option value="warning">warning</option>
                <option value="critical">critical</option>
              </select>
            </label>

            <label>
              Channel
              <select
                value={form.channel}
                onChange={(e) => setForm({ ...form, channel: e.target.value })}
              >
                <option value="email">email</option>
                <option value="webhook">webhook</option>
                <option value="both">both</option>
              </select>
            </label>

            <label>
              Webhook URL
              <input
                value={form.webhookUrl}
                placeholder="https://hooks.example.com/..."
                onChange={(e) =>
                  setForm({ ...form, webhookUrl: e.target.value })
                }
              />
            </label>

            <label>
              Threshold
              <input
                type="number"
                min="1"
                value={form.threshold}
                onChange={(e) =>
                  setForm({ ...form, threshold: Number(e.target.value) })
                }
              />
            </label>

            <label>
              Time Window (minutes)
              <input
                type="number"
                min="1"
                value={form.timeWindowMinutes}
                onChange={(e) =>
                  setForm({
                    ...form,
                    timeWindowMinutes: Number(e.target.value),
                  })
                }
              />
            </label>

            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) =>
                  setForm({ ...form, enabled: e.target.checked })
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
            <span className="active-count">{alerts.length} active</span>
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
                <div className="current-alert" key={alert.ruleId ?? index}>
                  <div className="alert-icon">!</div>

                  <div className="current-alert-content">
                    <strong>{alert.ruleName || "Alert Triggered"}</strong>

                    <span>{alert.message || "Threshold exceeded."}</span>

                    <small>severity: {alert.severity || "warning"}</small>

                    {alert.firedAt && <small>{alert.firedAt}</small>}
                  </div>
                </div>
              ))}
            </div>
          )}

          <div className="panel-title history-title">
            <h2>Recent History</h2>
          </div>

          {history.length === 0 ? (
            <div className="alert-empty">
              <span>No alert events recorded yet.</span>
            </div>
          ) : (
            <div className="current-alerts">
              {history.slice(0, 8).map((event, index) => (
                <div className="current-alert" key={event.id ?? index}>
                  <div
                    className={`alert-icon ${(event.state || "").toLowerCase()}`}
                  >
                    {event.state === "CLEARED" ? "✓" : "!"}
                  </div>

                  <div className="current-alert-content">
                    <strong>{event.ruleName || "Rule"}</strong>
                    <span>{event.message}</span>
                    <small>
                      {event.state} · {event.observedCount ?? 0}/
                      {event.threshold ?? 0}
                      {event.createdAt ? ` · ${event.createdAt}` : ""}
                    </small>
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
                  <th>Query</th>
                  <th>Severity</th>
                  <th>Threshold</th>
                  <th>Window</th>
                  <th>Channel</th>
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
                        {rule.query ||
                          [rule.service, rule.level]
                            .filter(Boolean)
                            .join(" / ")}
                      </span>
                    </td>

                    <td>
                      <span className={`level-badge ${levelClass(rule.severity)}`}>
                        {rule.severity}
                      </span>
                    </td>

                    <td>{rule.threshold}</td>

                    <td>{rule.timeWindowMinutes} min</td>

                    <td>{rule.channel}</td>

                    <td>
                      <button
                        className={`rule-status ${rule.enabled ? "enabled" : "disabled"
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
