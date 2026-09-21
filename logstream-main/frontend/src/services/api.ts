export const API_BASE = "http://localhost:8080";
export const WS_URL = "ws://localhost:8080/ws/logs";

export interface LogItem {
  id?: number;
  timestamp: string;
  level: string;
  service: string;
  message: string;
  host: string;
  createdAt?: string;
}

export interface LogStats {
  totalLogs?: number;
  info?: number;
  warning?: number;
  error?: number;
  [key: string]: unknown;
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<T>;
}

export async function getLogs(limit = 100): Promise<LogItem[]> {
  const data = await getJson<{ logs?: LogItem[]; count?: number }>(
    `${API_BASE}/api/logs?limit=${limit}`
  );
  return data.logs ?? [];
}

export async function searchLogs(
  query: string,
  level = "",
  service = "",
  limit = 100
): Promise<LogItem[]> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  if (level) params.set("level", level);
  if (service) params.set("service", service);
  params.set("limit", String(limit));

  const data = await getJson<{ logs?: LogItem[]; count?: number; query?: string }>(
    `${API_BASE}/api/logs/search?${params.toString()}`
  );
  return data.logs ?? [];
}

export async function getStats(): Promise<LogStats> {
  return getJson<LogStats>(`${API_BASE}/api/logs/stats`);
}

export async function getLogCount(): Promise<number> {
  const data = await getJson<{ totalLogs?: number }>(
    `${API_BASE}/api/logs/count`
  );
  return Number(data.totalLogs ?? 0);
}
