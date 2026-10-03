import ReactECharts from "echarts-for-react";

import type { HistogramBucket } from "../../services/api";

interface LogVolumeChartProps {
  buckets?: HistogramBucket[];
  height?: number;
  color?: string;
}

/**
 * Time-series log volume chart backed by the Lucene range-facet histogram
 * endpoint. Falls back to an empty axis when no data is available yet.
 */
function LogVolumeChart({
  buckets = [],
  height = 300,
  color = "#38bdf8",
}: LogVolumeChartProps) {
  const option = {
    tooltip: {
      trigger: "axis",
      backgroundColor: "#0b1220",
      borderColor: "#1f2937",
      textStyle: { color: "#e2e8f0" },
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
      data: buckets.map((bucket) => bucket.label),
      axisLabel: { color: "#64748b" },
      axisLine: { lineStyle: { color: "#1f2937" } },
    },

    yAxis: {
      type: "value",
      axisLabel: { color: "#64748b" },
      splitLine: { lineStyle: { color: "#1f2937" } },
    },

    series: [
      {
        name: "Logs",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: buckets.map((bucket) => bucket.count),
        lineStyle: { width: 2, color },
        itemStyle: { color },
        areaStyle: { opacity: 0.12, color },
      },
    ],
  };

  return <ReactECharts option={option} style={{ height }} />;
}

export default LogVolumeChart;
