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

import TenantSelector from "../tenant/TenantSelector";
import "./Sidebar.css";

const NAV_ITEMS = [
  { to: "/overview", label: "Overview", icon: LayoutDashboard },
  { to: "/logs", label: "Logs", icon: FileText },
  { to: "/live", label: "Live Tail", icon: Radio },
  { to: "/analytics", label: "Analytics", icon: BarChart3 },
  { to: "/alerts", label: "Alerts", icon: Bell },
  { to: "/services", label: "Services", icon: Server },
];

function Sidebar() {
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <h2>LogStream</h2>
        <p>Observability Platform</p>
      </div>

      <nav className="sidebar-nav">
        {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `sidebar-item ${isActive ? "active" : ""}`
            }
          >
            <Icon size={18} />
            {label}
          </NavLink>
        ))}
      </nav>

      <div className="sidebar-bottom">
        <TenantSelector />

        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `sidebar-item ${isActive ? "active" : ""}`
          }
        >
          <Settings size={18} />
          Settings
        </NavLink>
      </div>
    </aside>
  );
}

export default Sidebar;
