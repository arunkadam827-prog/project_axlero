import ReactECharts from "echarts-for-react";

function ErrorRateChart() {
  const option = {
    tooltip: {
      trigger: "axis",
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
      data: [
        "00:00",
        "04:00",
        "08:00",
        "12:00",
        "16:00",
        "20:00",
        "24:00",
      ],
      axisLabel: {
        color: "#64748b",
      },
      axisLine: {
        lineStyle: {
          color: "#1f2937",
        },
      },
    },

    yAxis: {
      type: "value",
      name: "%",
      axisLabel: {
        color: "#64748b",
      },
      splitLine: {
        lineStyle: {
          color: "#1f2937",
        },
      },
    },

    series: [
      {
        name: "Error Rate",
        type: "line",
        smooth: true,
        data: [1.2, 1.5, 1.1, 1.8, 2.4, 1.9, 1.65],

        lineStyle: {
          width: 3,
          color: "#ef4444",
        },

        itemStyle: {
          color: "#ef4444",
        },

        areaStyle: {
          opacity: 0.08,
        },
      },
    ],
  };

  return (
    <ReactECharts
      option={option}
      style={{ height: "300px", width: "100%" }}
    />
  );
}

export default ErrorRateChart;