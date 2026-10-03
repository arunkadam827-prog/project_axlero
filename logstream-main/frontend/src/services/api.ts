export const API_BASE = "http://localhost:8080";
export const WS_URL = "ws://localhost:8080/ws/logs";

export interface AlertRule {
  id?: number;
  tenantId?: string;
  name: string;
  service: string;
  level: string;
  query: string;
  severity: string;
  channel: string;
  webhookUrl: string;
  threshold: number;
  timeWindowMinutes: number;
  enabled: boolean;
  lastTriggeredAt?: string;
}

export interface CurrentAlert {
  ruleId?: number;
  ruleName?: string;
  severity?: string;
  message?: string;
  firedAt?: string;
}

export interface CurrentAlertsResponse {
  hasAlert: boolean;
  alerts: CurrentAlert[];
}

const TENANT_STORAGE_KEY = "logstream.tenant";

/** Default tenant used when none has been selected yet. */
export const DEFAULT_TENANT = "default";

let currentTenant = DEFAULT_TENANT;

/**
 * Restore the persisted tenant selection (called once at module load).
 */
try {
  const stored =
    typeof window !== "undefined"
      ? window.localStorage.getItem(TENANT_STORAGE_KEY)
      : null;
  if (stored) {
    currentTenant = stored;
  }
} catch {
  currentTenant = DEFAULT_TENANT;
}

/** Currently selected tenant id. */
export function getTenant(): string {
  return currentTenant;
}

/** Update the active tenant and persist it for subsequent requests. */
export function setTenant(tenantId: string): string {
  const normalized = (tenantId || DEFAULT_TENANT).trim() || DEFAULT_TENANT;
  currentTenant = normalized;
  try {
    if (typeof window !== "undefined") {
      window.localStorage.setItem(TENANT_STORAGE_KEY, normalized);
    }
  } catch {
    /* ignore storage failures */
  }
  return currentTenant;
}

export interface LogItem {
  id?: number;
  timestamp: string;
  level: string;
  service: string;
  message: string;
  host: string;
  tenantId?: string;
  responseTime?: number;
  traceId?: string;
  errorCode?: string;
  createdAt?: string;
}

export interface LogStats {
  totalLogs?: number;
  info?: number;
  warning?: number;
  warn?: number;
  error?: number;
  tenant?: string;
  [key: string]: unknown;
}

export interface SearchResponse {
  logs: LogItem[];
  count: number;
  totalHits: number;
  tookMs: number;
  query?: string;
  tenant?: string;
}

export interface HistogramBucket {
  label: string;
  startMillis: number;
  count: number;
}

export interface HistogramResponse {
  interval: string;
  buckets: HistogramBucket[];
  total: number;
}

export interface FacetBucket {
  key: string;
  count: number;
}

function tenantHeaders(): Record<string, string> {
  return { "X-Tenant-Id": currentTenant };
}

function withTenantHeaders(headers?: HeadersInit): Record<string, string> {
  const merged: Record<string, string> = { "X-Tenant-Id": currentTenant };

  if (!headers) return merged;

  if (headers instanceof Headers) {
    headers.forEach((value, key) => {
      merged[key] = value;
    });
    return merged;
  }

  if (Array.isArray(headers)) {
    for (const [key, value] of headers) {
      merged[key] = value;
    }
    return merged;
  }

  return { ...merged, ...headers };
}

async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: withTenantHeaders(init?.headers),
  });
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

function buildSearchParams(
  query: string,
  level: string,
  service: string,
  limit: number,
  offset: number
): string {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  if (level) params.set("level", level);
  if (service) params.set("service", service);
  params.set("limit", String(limit));
  params.set("offset", String(offset));
  return params.toString();
}

export async function searchLogs(
  query: string,
  level = "",
  service = "",
  limit = 100,
  offset = 0
): Promise<SearchResponse> {
  const params = buildSearchParams(query, level, service, limit, offset);
  const data = await getJson<Partial<SearchResponse>>(
    `${API_BASE}/api/logs/search?${params}`
  );
  return {
    logs: data.logs ?? [],
    count: data.count ?? (data.logs?.length ?? 0),
    totalHits: data.totalHits ?? (data.logs?.length ?? 0),
    tookMs: data.tookMs ?? 0,
    query: data.query,
    tenant: data.tenant,
  };
}

export async function getStats(): Promise<LogStats> {
  return getJson<LogStats>(`${API_BASE}/api/logs/stats`);
}

export async function getLogCount(): Promise<number> {
  const data = await getJson<{ totalLogs?: number; count?: number }>(
    `${API_BASE}/api/logs/count`
  );
  return Number(data.totalLogs ?? data.count ?? 0);
}

export async function getHistogram(
  query = "",
  interval = "5m",
  minutes = 60
): Promise<HistogramResponse> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  params.set("interval", interval);
  params.set("minutes", String(minutes));

  const data = await getJson<Partial<HistogramResponse>>(
    `${API_BASE}/api/logs/histogram?${params}`
  );
  return {
    interval: data.interval ?? interval,
    buckets: data.buckets ?? [],
    total: data.total ?? 0,
  };
}

export async function getFacets(
  query = "",
  field = "service",
  topN = 10,
  minutes = 60
): Promise<FacetBucket[]> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  params.set("field", field);
  params.set("topN", String(topN));
  params.set("minutes", String(minutes));

  const data = await getJson<FacetBucket[] | { buckets?: FacetBucket[] }>(
    `${API_BASE}/api/logs/facets?${params}`
  );
  if (Array.isArray(data)) return data;
  return data.buckets ?? [];
}

export interface AlertEvent {
  id?: number;
  tenantId?: string;
  ruleId?: number;
  ruleName?: string;
  severity?: string;
  state?: string;
  message?: string;
  observedCount?: number;
  threshold?: number;
  createdAt?: string;
}

/** Fetch AlertEvent history for the active tenant. */
export async function getAlertHistory(): Promise<AlertEvent[]> {
  const data = await getJson<AlertEvent[] | { events?: AlertEvent[] }>(
    `${API_BASE}/api/alerts/history`
  );
  if (Array.isArray(data)) return data;
  return data.events ?? [];
}

// ---------------------------------------------------------------------------
// Alert rules (tenant-scoped CRUD)
// ---------------------------------------------------------------------------

export async function getAlertRules(): Promise<AlertRule[]> {
  const data = await getJson<AlertRule[] | { rules?: AlertRule[] }>(
    `${API_BASE}/api/alerts/rules`
  );
  if (Array.isArray(data)) return data;
  return data.rules ?? [];
}

export async function getCurrentAlerts(): Promise<CurrentAlertsResponse> {
  const data = await getJson<Partial<CurrentAlertsResponse> & {
    items?: CurrentAlert[];
  }>(`${API_BASE}/api/alerts/current`);
  return {
    hasAlert: data.hasAlert ?? (data.alerts?.length ?? 0) > 0,
    alerts: data.alerts ?? data.items ?? [],
  };
}

export async function createAlertRule(rule: AlertRule): Promise<AlertRule> {
  return getJson<AlertRule>(`${API_BASE}/api/alerts/rules`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(rule),
  });
}

export async function updateAlertRule(
  id: number,
  rule: AlertRule
): Promise<AlertRule> {
  return getJson<AlertRule>(`${API_BASE}/api/alerts/rules/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(rule),
  });
}

export async function deleteAlertRule(id: number): Promise<void> {
  const response = await fetch(`${API_BASE}/api/alerts/rules/${id}`, {
    method: "DELETE",
    headers: tenantHeaders(),
  });
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
}

export async function evaluateAlerts(): Promise<void> {
  await getJson(`${API_BASE}/api/alerts/evaluate`, { method: "POST" });
}
