import ReactECharts from "echarts-for-react";

import type { HistogramBucket } from "../../services/api";

interface ErrorRateChartProps {
  /** Total logs per time bucket. */
  buckets?: HistogramBucket[];
  /** Error-only logs per time bucket (same labels as `buckets`). */
  errorBuckets?: HistogramBucket[];
  height?: number;
}

/**
 * Error-rate percentage over time computed from two histogram series
 * (total vs. level:ERROR). Values are derived, never hardcoded.
 */
function ErrorRateChart({
  buckets = [],
  errorBuckets = [],
  height = 300,
}: ErrorRateChartProps) {
  const errorByLabel = new Map(
    errorBuckets.map((bucket) => [bucket.label, bucket.count])
  );

  const labels = buckets.map((bucket) => bucket.label);

  const series = buckets.map((bucket) => {
    const errors = errorByLabel.get(bucket.label) ?? 0;
    if (bucket.count === 0) return 0;
    return Number(((errors / bucket.count) * 100).toFixed(2));
  });

  const option = {
    tooltip: {
      trigger: "axis",
      backgroundColor: "#0b1220",
      borderColor: "#1f2937",
      textStyle: { color: "#e2e8f0" },
      valueFormatter: (value: number) => `${value}%`,
    },

    grid: {
      left: "3%",
      right: "3%",
      bottom: "8%",
      top: "12%",
      containLabel: true,
    },

    xAxis: {
      type: "category",
      data: labels,
      axisLabel: { color: "#64748b" },
      axisLine: { lineStyle: { color: "#1f2937" } },
    },

    yAxis: {
      type: "value",
      name: "%",
      axisLabel: { color: "#64748b" },
      splitLine: { lineStyle: { color: "#1f2937" } },
    },

    series: [
      {
        name: "Error Rate",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: series,
        lineStyle: { width: 3, color: "#ef4444" },
        itemStyle: { color: "#ef4444" },
        areaStyle: { opacity: 0.08, color: "#ef4444" },
      },
    ],
  };

  return (
    <ReactECharts
      option={option}
      style={{ height, width: "100%" }}
    />
  );
}

export default ErrorRateChart;
