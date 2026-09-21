import { useEffect, useMemo, useRef, useState } from "react";
import { Pause, Play, Trash2, Wifi, WifiOff } from "lucide-react";
import { WS_URL, type LogItem } from "../../services/api";
import "./LiveTail.css";

function levelClass(level: string) {
  const value = level.toLowerCase();
  if (value === "error") return "error";
  if (value === "warn" || value === "warning") return "warning";
  return "info";
}

function LiveTail() {
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [connected, setConnected] = useState(false);
  const [paused, setPaused] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);
  const reconnectRef = useRef<number | null>(null);
  const pausedRef = useRef(false);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  useEffect(() => {
    let stopped = false;

    const connect = () => {
      if (stopped) return;

      const socket = new WebSocket(WS_URL);
      socketRef.current = socket;

      socket.onopen = () => {
        setConnected(true);
      };

      socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data) as LogItem & {
            type?: string;
          };

          if (data.type === "connection") return;
          if (pausedRef.current) return;

          setLogs((current) => [data, ...current].slice(0, 500));
        } catch {
          // Ignore malformed WebSocket messages.
        }
      };

      socket.onclose = () => {
        setConnected(false);
        if (!stopped) {
          reconnectRef.current = window.setTimeout(connect, 2000);
        }
      };

      socket.onerror = () => {
        setConnected(false);
      };
    };

    connect();

    return () => {
      stopped = true;
      if (reconnectRef.current) window.clearTimeout(reconnectRef.current);
      socketRef.current?.close();
    };
  }, []);

  const visibleLogs = useMemo(() => logs.slice(0, 200), [logs]);

  return (
    <div className="live-page">
      <div className="page-header">
        <div>
          <h1>Live Tail</h1>
          <p>Watch new logs arrive from the gRPC ingestion pipeline in real time.</p>
        </div>

        <div className={`connection-status ${connected ? "online" : "offline"}`}>
          {connected ? <Wifi size={15} /> : <WifiOff size={15} />}
          {connected ? "Connected" : "Disconnected"}
        </div>
      </div>

      <div className="live-header">
        <span className="pulse" />
        WebSocket: <strong>{connected ? "LIVE" : "WAITING FOR BACKEND"}</strong>
        <span className="live-url">{WS_URL}</span>
      </div>

      <div className="live-controls">
        <button className="refresh" onClick={() => setPaused((value) => !value)}>
          {paused ? <Play size={14} /> : <Pause size={14} />}
          {paused ? "Resume" : "Pause"}
        </button>

        <button className="refresh" onClick={() => setLogs([])}>
          <Trash2 size={14} />
          Clear
        </button>

        <span>{logs.length} live event{logs.length === 1 ? "" : "s"}</span>
      </div>

      <div className="panel">
        <div className="panel-head">
          <h2>Incoming Logs</h2>
          <span className="result-note">{paused ? "Paused" : "Listening"}</span>
        </div>

        {visibleLogs.length === 0 ? (
          <div className="empty">
            Waiting for logs...
            <br />
            <small>Send a log through gRPC on port 9090 and it will appear here.</small>
          </div>
        ) : (
          <div className="table-wrap live-table">
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
                {visibleLogs.map((log, index) => (
                  <tr key={`${log.timestamp}-${index}`}>
                    <td className="mono">{log.timestamp || "-"}</td>
                    <td>
                      <span className={`badge ${levelClass(log.level)}`}>
                        {log.level || "INFO"}
                      </span>
                    </td>
                    <td>{log.service || "-"}</td>
                    <td>{log.message || "-"}</td>
                    <td className="mono">{log.host || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

export default LiveTail;
