import ReactECharts from "echarts-for-react";

function LogVolumeChart() {
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
        "14:00",
        "14:05",
        "14:10",
        "14:15",
        "14:20",
        "14:25",
        "14:30",
        "14:35",
      ],
    },

    yAxis: {
      type: "value",
    },

    series: [
      {
        name: "Logs",
        type: "line",
        smooth: true,
        data: [120, 180, 150, 260, 220, 310, 280, 360],
      },
    ],
  };

  return <ReactECharts option={option} style={{ height: "300px" }} />;
}

export default LogVolumeChart;