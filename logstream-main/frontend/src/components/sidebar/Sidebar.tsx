import {
  LayoutDashboard,
  FileText,
  Radio,
  BarChart3,
  Bell,
  Server,
  Settings,
} from "lucide-react";

import { NavLink } from "react-router-dom";

import "./Sidebar.css";

function Sidebar() {
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <h2>LogStream</h2>
        <p>Observability Platform</p>
      </div>

      <nav className="sidebar-nav">
        <NavLink
          to="/overview"
          className={({ isActive }) =>
            `sidebar-item ${isActive ? "active" : ""}`
          }
        >
          <LayoutDashboard size={18} />
          Overview
        </NavLink>

        <NavLink
          to="/logs"
          className={({ isActive }) =>
            `sidebar-item ${isActive ? "active" : ""}`
          }
        >
          <FileText size={18} />
          Logs
        </NavLink>

        <NavLink
          to="/live"
          className={({ isActive }) =>
            `sidebar-item ${isActive ? "active" : ""}`
          }
        >
          <Radio size={18} />
          Live Tail
        </NavLink>

        <NavLink
  to="/analytics"
  className={({ isActive }) =>
    `sidebar-item ${isActive ? "active" : ""}`
  }
>
  <BarChart3 size={18} />
  Analytics
</NavLink>

        <button className="sidebar-item">
          <Bell size={18} />
          Alerts
        </button>

        <button className="sidebar-item">
          <Server size={18} />
          Services
        </button>
      </nav>

      <div className="sidebar-bottom">
        <button className="sidebar-item">
          <Settings size={18} />
          Settings
        </button>
      </div>
    </aside>
  );
}

export default Sidebar;