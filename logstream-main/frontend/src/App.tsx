
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";

import MainLayout from "./layouts/MainLayout";
import Overview from "./pages/overview/Overview";
import Logs from "./pages/logs/Logs";
import LiveTail from "./pages/live-tail/LiveTail";
import Analytics from "./pages/analytics/Analytics";
import Alerts from "./pages/alerts/Alerts";
import Services from "./pages/services/Services";
import Settings from "./pages/settings/Settings";

function App() {
  return (
    <BrowserRouter>
      <MainLayout>
        <Routes>
          <Route path="/" element={<Navigate to="/overview" replace />} />

          <Route path="/overview" element={<Overview />} />
          <Route path="/logs" element={<Logs />} />
          <Route path="/live" element={<LiveTail />} />
          <Route path="/analytics" element={<Analytics />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/services" element={<Services />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </MainLayout>
    </BrowserRouter>
  );
}

export default App;
