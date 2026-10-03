import { useCallback, useEffect, useState } from "react";
import { Server } from "lucide-react";

import { getFacets, type FacetBucket } from "../../services/api";
import "./Services.css";

function Services() {
    const [services, setServices] = useState<FacetBucket[]>([]);
    const [errorServices, setErrorServices] = useState<FacetBucket[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    const load = useCallback(async () => {
        try {
            const [all, errors] = await Promise.all([
                getFacets("", "service", 25, 1440),
                getFacets("level:ERROR", "service", 25, 1440),
            ]);

            setServices(all);
            setErrorServices(errors);
            setError("");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to load services");
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        // Defer the initial load so the effect does not call setState
        // synchronously (which would trigger a cascading render on mount).
        const kickoff = window.setTimeout(load, 0);
        return () => window.clearTimeout(kickoff);
    }, [load]);

    const errorByService = new Map(
        errorServices.map((facet) => [facet.key, facet.count])
    );

    return (
        <div className="services-page">
            <div className="services-header">
                <div>
                    <h1>Services</h1>
                    <p>Log volume per service over the last 24 hours.</p>
                </div>

                <button
                    className="refresh-btn"
                    onClick={() => {
                        setLoading(true);
                        void load();
                    }}
                >
                    {loading ? "Refreshing..." : "Refresh"}
                </button>
            </div>

            {error && <div className="services-error">{error}</div>}

            {services.length === 0 ? (
                <div className="services-empty">
                    <Server size={28} />
                    <strong>{loading ? "Loading services…" : "No services reporting"}</strong>
                    <span>Ingest logs tagged with a service to populate this view.</span>
                </div>
            ) : (
                <div className="services-grid">
                    {services.map((service) => {
                        const errors = errorByService.get(service.key) ?? 0;
                        const errorRate =
                            service.count > 0 ? (errors / service.count) * 100 : 0;

                        return (
                            <div className="service-card" key={service.key}>
                                <div className="service-card-top">
                                    <Server size={18} />
                                    <span>{service.key || "(unknown)"}</span>
                                </div>

                                <strong className="service-count">
                                    {service.count.toLocaleString()}
                                </strong>
                                <span className="service-meta">log events</span>

                                <div className="service-error-row">
                                    <span>{errors.toLocaleString()} errors</span>
                                    <span
                                        className={`service-rate ${errorRate > 5 ? "high" : "low"
                                            }`}
                                    >
                                        {errorRate.toFixed(1)}%
                                    </span>
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}

export default Services;
