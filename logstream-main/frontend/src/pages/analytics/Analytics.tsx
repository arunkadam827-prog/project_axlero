import {
  Activity,
  AlertCircle,
  Clock3,
  Server,
} from "lucide-react";

import "./Analytics.css";

function Analytics() {
  return (
    <div className="analytics-page">

      {/* Header */}
      <div className="analytics-header">
        <div>
          <h1>Analytics</h1>
          <p>Analyze log patterns, errors, and service performance.</p>
        </div>

        <div className="analytics-actions">
          <button>Last 24 Hours</button>
          <button className="refresh-btn">↻ Refresh</button>
        </div>
      </div>


      {/* Summary Cards */}
      <div className="analytics-stats">

        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Total Events</span>
            <Activity size={18} />
          </div>

          <h2>1,284,532</h2>
          <p className="positive">↑ 12.4% from yesterday</p>
        </div>


        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Error Rate</span>
            <AlertCircle size={18} />
          </div>

          <h2>1.65%</h2>
          <p className="positive">↓ 0.3% from yesterday</p>
        </div>


        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Avg. Response</span>
            <Clock3 size={18} />
          </div>

          <h2>248 ms</h2>
          <p className="negative">↑ 8.2% from yesterday</p>
        </div>


        <div className="analytics-card">
          <div className="analytics-card-top">
            <span>Active Services</span>
            <Server size={18} />
          </div>

          <h2>12</h2>
          <p className="positive">All services healthy</p>
        </div>

      </div>


      {/* Analytics Charts */}
      <div className="analytics-grid">

        <div className="analytics-section">
          <div className="section-heading">
            <div>
              <h2>Error Rate Trend</h2>
              <p>Error percentage over the selected time period.</p>
            </div>
          </div>

          <div className="chart-placeholder">
            Error rate chart
          </div>
        </div>


        <div className="analytics-section">
          <div className="section-heading">
            <div>
              <h2>Logs by Service</h2>
              <p>Log distribution across services.</p>
            </div>
          </div>

          <div className="chart-placeholder">
            Service distribution chart
          </div>
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

          <div className="performance-row">
            <span>api-gateway</span>
            <span>482,120</span>
            <span>182 ms</span>
            <span className="error-text">1.2%</span>
            <span className="status healthy">Healthy</span>
          </div>

          <div className="performance-row">
            <span>payment-service</span>
            <span>218,450</span>
            <span>264 ms</span>
            <span className="error-text">2.1%</span>
            <span className="status healthy">Healthy</span>
          </div>

          <div className="performance-row">
            <span>auth-service</span>
            <span>184,230</span>
            <span>142 ms</span>
            <span className="error-text">0.8%</span>
            <span className="status healthy">Healthy</span>
          </div>

        </div>

      </div>

    </div>
  );
}

export default Analytics;