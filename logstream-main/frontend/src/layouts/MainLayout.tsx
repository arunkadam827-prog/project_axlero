import type { ReactNode } from "react";
import Sidebar from "../components/sidebar/Sidebar";
import "./MainLayout.css";

interface MainLayoutProps {
  children: ReactNode;
}

function MainLayout({ children }: MainLayoutProps) {
  return (
    <div className="main-layout">
      <Sidebar />

      <main className="main-content">
        {children}
      </main>
    </div>
  );
}

export default MainLayout;