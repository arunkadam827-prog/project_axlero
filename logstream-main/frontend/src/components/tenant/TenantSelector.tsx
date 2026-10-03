import { useState } from "react";
import { Building2 } from "lucide-react";

import { getTenant, setTenant } from "../../services/api";
import "./TenantSelector.css";

const KNOWN_TENANTS = ["default", "acme", "globex", "initech"];

/**
 * Selector for the active tenant. The chosen tenant is persisted and
 * attached to every backend request via the `X-Tenant-Id` header.
 */
function TenantSelector() {
    const [tenant, setTenantState] = useState(getTenant());
    const [custom, setCustom] = useState("");

    const applyTenant = (value: string) => {
        const next = setTenant(value);
        setTenantState(next);
        // Reload so every page refetches data for the new tenant.
        window.location.reload();
    };

    return (
        <div className="tenant-selector">
            <label className="tenant-label">
                <Building2 size={14} />
                Tenant
            </label>

            <select
                className="tenant-select"
                value={KNOWN_TENANTS.includes(tenant) ? tenant : "__custom__"}
                onChange={(e) => {
                    const value = e.target.value;
                    if (value === "__custom__") {
                        return;
                    }
                    applyTenant(value);
                }}
            >
                {KNOWN_TENANTS.map((id) => (
                    <option key={id} value={id}>
                        {id}
                    </option>
                ))}
                <option value="__custom__">custom…</option>
            </select>

            <div className="tenant-custom-row">
                <input
                    className="tenant-input"
                    value={custom}
                    placeholder="tenant-id"
                    onChange={(e) => setCustom(e.target.value)}
                    onKeyDown={(e) => {
                        if (e.key === "Enter" && custom.trim()) {
                            applyTenant(custom.trim());
                        }
                    }}
                />
                <button
                    className="tenant-apply"
                    onClick={() => custom.trim() && applyTenant(custom.trim())}
                    disabled={!custom.trim()}
                >
                    Apply
                </button>
            </div>

            <span className="tenant-active">
                active: <strong>{tenant}</strong>
            </span>
        </div>
    );
}

export default TenantSelector;
