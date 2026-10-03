import ReactECharts from "echarts-for-react";

import type { FacetBucket } from "../../services/api";

interface ServiceDistributionChartProps {
    buckets?: FacetBucket[];
    height?: number;
}

const PALETTE = [
    "#38bdf8",
    "#a78bfa",
    "#34d399",
    "#fbbf24",
    "#f87171",
    "#22d3ee",
    "#f472b6",
    "#818cf8",
];

/**
 * Pie chart of log distribution across a facet field (default: service),
 * backed by the Lucene SortedSetDocValues facets endpoint.
 */
function ServiceDistributionChart({
    buckets = [],
    height = 300,
}: ServiceDistributionChartProps) {
    const option = {
        tooltip: {
            trigger: "item",
            backgroundColor: "#0b1220",
            borderColor: "#1f2937",
            textStyle: { color: "#e2e8f0" },
        },

        legend: {
            type: "scroll",
            orient: "vertical",
            right: 8,
            top: "center",
            textStyle: { color: "#94a3b8", fontSize: 11 },
        },

        series: [
            {
                name: "Logs",
                type: "pie",
                radius: ["45%", "72%"],
                center: ["38%", "50%"],
                avoidLabelOverlap: true,
                itemStyle: {
                    borderColor: "#0b1220",
                    borderWidth: 2,
                },
                label: { show: false },
                emphasis: {
                    label: { show: true, color: "#e2e8f0", fontWeight: "bold" },
                },
                color: PALETTE,
                data: buckets.map((bucket) => ({
                    name: bucket.key || "(unknown)",
                    value: bucket.count,
                })),
            },
        ],
    };

    return <ReactECharts option={option} style={{ height }} />;
}

export default ServiceDistributionChart;
