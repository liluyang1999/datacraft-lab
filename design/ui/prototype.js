// 数艺工坊 design prototype: renders the console's shell and its six views from sample data.
// Glyph paths are the ones Icons.java draws (20 x 20 grid, strokes). Nothing here talks to a backend;
// the controls above the window only switch the sample state, the theme and the window width.

const GLYPHS = {
  overview: "M10 3.2a6.8 6.8 0 1 0 0.01 0Z M10 7.6a2.4 2.4 0 1 0 0.01 0Z M10 3.2v2.2",
  jobs: "M4.2 4.2h11.6v11.6H4.2Z M8.4 7.6l4.2 2.4-4.2 2.4Z",
  pipelines: "M2.8 4.6h5v3.4h-5Z M12.2 12h5v3.4h-5Z M7.8 6.3h2.4a1.6 1.6 0 0 1 1.6 1.6v4.1 M12.2 6.3h5",
  monitor: "M3.4 13.8a6.6 6.6 0 1 1 13.2 0 M10 13.8l3.2-3.6 M3.4 13.8h1.8 M14.8 13.8h1.8",
  activity: "M2.6 10h3.6l2-5.2 3.8 10.4 2-5.2h3.4",
  settings: "M3.5 6h13 M3.5 14h13 M7.5 3.8v4.4 M12.5 11.8v4.4",
  refresh: "M15.6 8.2A6 6 0 1 0 16 11.6 M16 4.2v4h-4",
  sidebar: "M3.6 4.4h12.8v11.2H3.6Z M8 4.4v11.2",
  engine: "M10 2.8l6.2 3.6v7.2L10 17.2l-6.2-3.6V6.4Z M3.8 6.4L10 10l6.2-3.6 M10 10v7.2",
  airflow: "M10 10C10 6.6 11.4 3.4 14.6 3.4 14.6 6.6 12.8 9 10 10Z M10 10C13.4 10 16.6 11.4 16.6 14.6 13.4 14.6 11 12.8 10 10Z M10 10C10 13.4 8.6 16.6 5.4 16.6 5.4 13.4 7.2 11 10 10Z M10 10C6.6 10 3.4 8.6 3.4 5.4 6.6 5.4 9 7.2 10 10Z",
  clock: "M10 3.4a6.6 6.6 0 1 0 0.01 0Z M10 6.4v3.8l2.6 1.6",
  manual: "M5.4 3.6l9 5.8-3.8 1 2.4 4.6-1.8 0.9-2.4-4.6-2.8 2.7Z",
  play: "M7 5l8 5-8 5Z",
  info: "M10 3.4a6.6 6.6 0 1 0 0.01 0Z M10 9.2v4.4 M10 6.6v0.2",
  check: "M5 10.4l3.2 3.2L15 6.8",
  cross: "M6.2 6.2l7.6 7.6 M13.8 6.2l-7.6 7.6",
  warning: "M10 3.6l7 12.4H3Z M10 8v3.8 M10 13.8v0.2",
  bell: "M5.4 13.6V9.4a4.6 4.6 0 0 1 9.2 0v4.2l1.4 1.6H4Z M8.4 16.6a1.6 1.6 0 0 0 3.2 0",
  pulse: "M2.6 11h3l1.6-4 2.6 8 2.2-6 1.4 2h4",
  all: "M4 4h5v5H4Z M11 4h5v5h-5Z M4 11h5v5H4Z M11 11h5v5h-5Z",
  log: "M5 3.4h6.8l3.2 3.2v10H5Z M7.6 9.4h4.8 M7.6 12.4h4.8",
  jvm: "M4.6 8h8.8v4.2a3.4 3.4 0 0 1-3.4 3.4H8a3.4 3.4 0 0 1-3.4-3.4Z M13.4 9h1.2a1.8 1.8 0 0 1 0 3.6h-1.2 M7.4 3.6v2.2 M10.4 3.6v2.2",
  spark: "M10 2.8v4.4 M10 12.8v4.4 M2.8 10h4.4 M12.8 10h4.4 M4.9 4.9l3.1 3.1 M12 12l3.1 3.1 M4.9 15.1L8 12 M12 8l3.1-3.1",
  builtin: "M4.6 4.6h10.8v10.8H4.6Z M10 7.8a2.2 2.2 0 1 0 0.01 0Z",
  link: "M8.6 11.4a3 3 0 0 0 4.2 0l2.4-2.4a3 3 0 0 0-4.2-4.2l-0.8 0.8 M11.4 8.6a3 3 0 0 0-4.2 0L4.8 11a3 3 0 0 0 4.2 4.2l0.8-0.8",
  sun: "M10 6.8a3.2 3.2 0 1 0 0.01 0Z M10 2.6v1.6 M10 15.8v1.6 M2.6 10h1.6 M15.8 10h1.6",
  database: "M4.4 5.6c0-1.4 2.5-2.4 5.6-2.4s5.6 1 5.6 2.4v8.8c0 1.4-2.5 2.4-5.6 2.4s-5.6-1-5.6-2.4Z M4.4 5.6c0 1.4 2.5 2.4 5.6 2.4s5.6-1 5.6-2.4",
};

const VIEWS = [
  ["overview", "总览"], ["jobs", "作业"], ["pipelines", "流水线"],
  ["monitor", "监控"], ["activity", "活动"], ["settings", "设置"],
];

const DAGS = [
  { id: "datacraft_engine_jobs", schedule: "clock", runs: "ssssfsssssss", when: "40 分钟前", tone: "success" },
  { id: "datacraft_sftp_ingest", schedule: "manual", runs: "", when: "尚未运行", tone: "none", paused: true },
  { id: "datacraft_spark_etl", schedule: "manual", runs: "sfsfssr", when: "3 分钟前", tone: "running" },
];

const JOBS = [
  ["csv-profile", "jvm"], ["csv-quality", "jvm"], ["file-checksum", "jvm"], ["csv-to-parquet", "spark"],
  ["row-count", "spark"], ["spark-version", "spark"], ["echo", "builtin"], ["noop", "builtin"],
];

const state = { view: "monitor", theme: "light", sample: "attention", compact: false };

const icon = (name, cls = "glyph") =>
  `<svg class="${cls}" viewBox="0 0 20 20" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" fill="none"><path d="${GLYPHS[name]}"/></svg>`;
const heading = (glyph, title, extra = "") => `<div class="heading">${icon(glyph)}<span>${title}</span>${extra}</div>`;
const dot = (tone) => `<span class="dot ${tone}"></span>`;

function ring(tone, glyph) {
  const colour = { success: "var(--dc-success)", failed: "var(--dc-failed)", warning: "var(--dc-warning)" }[tone] || "var(--dc-none)";
  return `<svg class="ring" viewBox="0 0 60 60"><circle class="track" cx="30" cy="30" r="28"/>
    <circle class="arc" cx="30" cy="30" r="28" stroke="${colour}" transform="rotate(-90 30 30)"/>
    <g transform="translate(17 17) scale(1.3)">${`<path class="glyph-in" stroke-width="1.3" d="${GLYPHS[glyph]}"/>`}</g></svg>`;
}

function bars(runs, big = false, total = big ? 25 : 10) {
  const heights = [20, 18, 22, 16, 24, 19, 21, 17, 23, 20, 18, 22];
  const cells = [];
  for (let index = 0; index < total - runs.length; index++) cells.push(`<i class="none"></i>`);
  [...runs].forEach((code, index) => {
    const tone = { s: "success", f: "failed", r: "running" }[code];
    const height = code === "r" ? 24 : heights[index % heights.length];
    const picked = big && index === runs.length - 1 ? " picked" : "";
    cells.push(`<i class="${tone}${picked}" style="height:${big ? height * 2 : height}px"></i>`);
  });
  return `<div class="bars${big ? " big" : ""}">${cells.join("")}</div>`;
}

function sidebar() {
  const alerts = state.sample === "attention" ? 3 : state.sample === "offline" ? 0 : 0;
  const items = VIEWS.filter(([id]) => id !== "settings").map(([id, title]) =>
    `<a class="${state.view === id ? "selected" : ""}" data-view="${id}">${icon(id)}<span>${title}</span>${
      id === "monitor" && alerts ? `<b class="badge">${alerts}</b>` : ""}</a>`).join("");
  return `<aside class="sidebar">
    <div class="wordmark">
      <svg class="brand" viewBox="0 0 32 32"><rect class="plate" x="0.5" y="0.5" width="31" height="31" rx="8"/>
        ${["ok", "ok", "run", "ok", "light"].map((tone, index) =>
          `<rect class="${tone}" x="${5.76 + index * 4.5}" y="9.6" width="2.5" height="12.8" rx="0.8"/>`).join("")}
      </svg>
      <div class="text"><div class="name">数艺工坊</div><div class="version latin">v1.0.0</div></div>
    </div>
    <nav class="nav">${items}</nav>
    <div class="grow"></div>
    <div class="divider"></div>
    <nav class="nav"><a class="${state.view === "settings" ? "selected" : ""}" data-view="settings">${icon("settings")}<span>设置</span></a></nav>
    <div class="profile"><span>本机部署</span><span>▾</span></div>
  </aside>`;
}

function topBar(title) {
  const down = state.sample === "offline";
  const engine = state.sample === "attention" ? ["warning", "状态异常"] : down ? ["none", "未连接"] : ["success", "4 ms"];
  const airflow = down ? ["none", "未连接"] : ["success", "6 ms"];
  return `<header class="top-bar">
    <span class="icon-button">${icon("sidebar")}</span>
    <span class="title">${title}</span>
    <span class="backend">${dot(engine[0])}<span>引擎</span><span class="latency latin">${engine[1]}</span></span>
    <span class="backend">${dot(airflow[0])}<span>Airflow</span><span class="latency latin">${airflow[1]}</span></span>
    <span class="icon-button">${icon("refresh")}</span>
  </header>`;
}

function empty() {
  return `<div class="empty"><div class="art">${icon("link")}</div><div>先连接你的引擎与 Airflow</div>
    <span class="primary">＋ 添加连接</span></div>`;
}

function overview() {
  const tile = (name, glyph, figure, detail, tone) => `<div class="tile backend">${ring(tone, glyph)}
    <div><div class="title">${name}</div><div class="figure">${figure}</div><div class="caption">${detail}</div></div>
    <span class="corner">${icon("info")}</span></div>`;
  const lanes = DAGS.map((dag, index) => `<div class="lane${dag.paused ? " paused" : ""}${index === 0 ? "" : ""}">
    ${icon(dag.schedule)}<span class="name">${dag.id}</span>${bars(dag.runs)}${dot(dag.tone)}
    <span class="when secondary">${dag.when}</span>${dag.paused ? `<span class="chip">Ⅱ 已暂停</span>` : "<span></span>"}
    ${icon("play")}</div>`).join("");
  const marks = [[2, "success"], [4, "success"], [20, "failed"], [41, "success"], [60, "success"], [80, "failed"], [97, "success"], [99, "running"]]
    .map(([at, tone]) => `<span class="mark" style="left:calc(28px + (100% - 28px) * ${at / 100});top:8px;background:var(--dc-${tone})"></span>`).join("");
  return `
    <div class="tiles three">
      ${tile("引擎 API", "engine", "4 ms", "作业 8 个", "success")}
      ${tile("Airflow", "airflow", "6 ms", "调度器心跳 刚刚", "success")}
      <div class="tile backend">${ring("success", "pulse")}<div><div class="title">近期成功率</div>
        <div class="figure">82%</div><div class="caption">${dot("success")} 14　${dot("failed")} 3　${dot("running")} 1</div></div></div>
    </div>
    <div class="section">${heading("pipelines", "流水线")}${lanes}</div>
    <div class="section">${heading("activity", "最近运行")}
      <div class="timeline"><span class="rail" style="top:8px"></span><span class="rail" style="top:34px"></span>${marks}</div>
      ${[["pipelines", "datacraft_spark_etl", "running", "02:43:00", ""], ["pipelines", "datacraft_engine_jobs", "success", "02:06:00", "48 s"],
         ["jobs", "csv-profile", "success", "01:58:12", "1.2 s"]].map(([glyph, name, tone, at, took]) =>
        `<div class="recent">${icon(glyph)}<span class="latin">${name}</span>${dot(tone)}<span class="latin">${at}</span><span class="secondary latin">${took}</span></div>`).join("")}
    </div>`;
}

function jobs() {
  return `<div class="split">
    <div class="job-list"><div class="filters"><div class="input">名称或说明</div><div class="input value">类型　全部 ▾</div></div>
      ${JOBS.map(([name, kind], index) => `<div class="job${index === 0 ? " selected" : ""}">${icon(kind === "builtin" ? "builtin" : kind, `glyph ${kind}`)}<span class="latin">${name}</span></div>`).join("")}
    </div>
    <div class="job-detail">
      <div class="job-head"><span class="disc">${icon("jvm")}</span><div><div class="metric latin">csv-profile</div>
        <div class="secondary">在 JVM 中统计一个 UTF-8 CSV 文件：行数、列数、字节数与 SHA-256</div></div></div>
      ${heading("settings", "参数")}
      <div class="params">
        <span class="latin">input <span class="text-failed">*</span></span><span class="input value latin">/opt/datacraft/data/input.csv</span>
        <span class="latin">header</span><span class="input value">默认（true） ▾</span>
        <span class="latin">delimiter</span><span class="input">默认 ,</span>
        <span class="latin">expectedRows</span><span class="input">可不填</span>
      </div>
      <span class="run-button">${icon("play")} 运行作业</span>
      <div class="job-head"><span class="mark-disc" style="width:44px;height:44px">${icon("check")}</span>
        <div><div class="text-success">成功</div><div class="metric latin">1.2 s</div></div></div>
      <div class="metrics">${[["1.2 万", "行数"], ["6", "列数"], ["1.8 MB", "大小"]].map(([value, label]) =>
        `<div class="metric-tile"><div class="figure latin">${value}</div><div class="caption">${label}</div></div>`).join("")}</div>
      ${heading("activity", "最近运行")}${bars("ssssfssss", false, 20)}
    </div></div>`;
}

function pipelines() {
  return `
    <div class="section">${DAGS.map((dag, index) => `<div class="lane${index === 0 ? " selected" : ""}${dag.paused ? " paused" : ""}">
      ${icon(dag.schedule)}<span class="name">${dag.id}</span>${bars(dag.runs)}${dot(dag.tone)}<span class="when secondary">${dag.when}</span>
      <span class="chip">${dag.paused ? "已暂停" : "调度中"}</span>${icon("play")}</div>`).join("")}</div>
    <div class="section">${heading("pipelines", "datacraft_engine_jobs", `<span style="flex:1"></span><span class="primary">▷ 触发运行</span>`)}
      ${heading("activity", "运行")}${bars("ssssssssfsss", true)}
      <div style="display:flex;gap:8px">${["成功", "定时", "01:39:00", "48 s"].map((text) => `<span class="chip">${text}</span>`).join("")}</div>
      ${heading("pipelines", "任务")}
      <div class="graph">${["noop", "echo", "spark_version"].map((task) =>
        `<div class="card">${dot("success")}<div><div class="latin">${task}</div><div class="caption latin">18 s</div></div></div>`).join("")}</div>
    </div>`;
}

function spark(samples) {
  const width = 600, height = 40, step = (width - 6) / 119, offset = 120 - samples.length;
  const worst = Math.max(...samples.filter((value) => value > 0)) * 1.25;
  let line = "", area = "", misses = "", open = false, start = 0, last = 0, lastY = 0;
  samples.forEach((value, index) => {
    const x = 3 + (offset + index) * step;
    if (value <= 0) {
      if (open) { area += `L${last},${height - 3}L${start},${height - 3}Z`; open = false; }
      misses += `<rect class="miss" x="${x - 1.5}" y="${height - 9}" width="3" height="6"/>`;
      return;
    }
    const y = height - 3 - Math.max(1.5, (value / worst) * (height - 6));
    if (!open) { line += `M${x},${y}`; area += `M${x},${height - 3}L${x},${y}`; start = x; open = true; }
    else { line += `L${x},${y}`; area += `L${x},${y}`; }
    last = x; lastY = y;
  });
  if (open) area += `L${last},${height - 3}L${start},${height - 3}Z`;
  return `<svg class="spark" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">
    <line class="base" x1="3" x2="${width - 3}" y1="${height - 2.5}" y2="${height - 2.5}"/>
    <path class="area" d="${area}"/><path class="line" d="${line}"/>${misses}
    <circle class="dot-last" cx="${last}" cy="${lastY}" r="2.6"/></svg>`;
}

function monitor() {
  const attention = state.sample === "attention";
  const engineSamples = Array.from({ length: 96 }, (_, index) => (attention && index > 60 && index < 66 ? 0 : 4 + ((index * 7) % 5) + (index % 23 === 0 ? 9 : 0)));
  const airflowSamples = Array.from({ length: 96 }, (_, index) => 6 + ((index * 5) % 4) + (index % 31 === 0 ? 14 : 0));
  const alerts = attention ? [
    ["failed", "cross", "1 个 DAG 文件无法解析", "/opt/airflow/dags/broken_dag.py", "详情"],
    ["warning", "warning", "资源池 default_pool 已满", "共 16 个槽位，运行 16，排队 4，空闲 0。", "详情"],
    ["warning", "warning", "引擎拒绝了 2 次运行", "同时运行数已达上限 4，新的运行请求被拒绝。", "详情"],
  ] : [];
  const alertRows = alerts.length
    ? `<div class="alerts">${alerts.map(([level, glyph, title, detail, action]) => `<div class="alert ${level}">
        <span class="mark">${icon(glyph)}</span><div class="text"><div class="title">${title}</div><div class="caption">${detail}</div></div>
        <span class="quiet-button">${action}</span></div>`).join("")}</div>`
    : `<div class="all-clear"><span class="mark-disc">${icon("check")}</span><div><div class="title">一切正常</div><div class="caption">更新于 02:46:43</div></div></div>`;
  const cells = (filled, total) => `<div class="meter">${Array.from({ length: total }, (_, index) => `<i class="${index < filled ? "running" : ""}"></i>`).join("")}</div>`;
  const component = (name, beat) => `<div class="component">${dot("success")}<span>${name}</span><span class="grow"></span><span class="caption">${beat}</span></div>`;
  const latency = (glyph, name, samples, latest, spread, share, tone) => `<div class="latency-row">${icon(glyph)}<span>${name}</span>
    ${spark(samples)}<div class="right"><div class="metric latin">${latest}</div><div class="caption">${spread}</div></div>
    <div class="right"><div class="metric latin ${tone}">${share}</div><div class="caption">可用</div></div></div>`;
  const pool = (name, running, queued, slots) => `<div class="row"><span class="pool-name latin">${name}</span>
    <div class="meter bar pool-meter"><i class="running" style="width:${(running / slots) * 100}%"></i><i class="waiting" style="width:${(Math.min(queued, slots - running) / slots) * 100}%"></i></div>
    <span class="caption" style="width:168px;text-align:right">运行 ${running}  排队 ${queued}  ${slots - running - Math.min(queued, slots - running)} / ${slots} 空闲</span></div>`;
  return `
    <div class="section">${heading("bell", "需要关注", alerts.length ? `<span class="count">${alerts.length}</span>` : "")}${alertRows}</div>
    <div class="tiles two">
      <div class="tile"><div class="tile-head">${icon("engine")}<span class="title">引擎 API</span><span class="grow"></span><span class="chip latin">v1.0.0</span></div>
        <div><span class="figure latin">${attention ? "4 / 4" : "1 / 4"}</span> <span class="caption">同时运行</span></div>
        ${cells(attention ? 4 : 1, 4)}
        <div class="counters">${[["42", "开始"], ["38", "成功"], ["3", "失败"], [attention ? "2" : "0", "拒绝"]].map(([value, label]) =>
          `<div><div class="metric latin">${value}</div><div class="caption">${label}</div></div>`).join("")}</div>
        <div class="caption" style="margin-top:10px">已运行 3 h 00 min</div></div>
      <div class="tile"><div class="tile-head">${icon("airflow")}<span class="title">Airflow</span><span class="grow"></span><span class="chip latin">v3.3.2</span></div>
        ${component("元数据库", "正常")}${component("调度器", "心跳 刚刚")}${component("触发器", "心跳 2 秒前")}${component("DAG 处理器", "心跳 3 秒前")}</div>
    </div>
    <div class="section">${heading("pulse", "延迟与可用性")}
      ${latency("engine", "引擎 API", engineSamples, "6 ms", "中位 6 ms，最慢 17 ms", attention ? "94.8%" : "100.0%", attention ? "text-warning" : "text-success")}
      ${latency("airflow", "Airflow", airflowSamples, "7 ms", "中位 7 ms，最慢 21 ms", "100.0%", "text-success")}</div>
    <div class="section">${heading("play", "运行中的任务", `<span class="count quiet">2</span>`)}
      ${[["running", "datacraft_spark_etl", "csv_to_parquet", "运行中", "1 min 36 s"], ["waiting", "datacraft_engine_jobs", "spark_version", "排队", "12 秒前"]]
        .map(([tone, dag, task, label, time]) => `<div class="row clickable">${dot(tone)}<span class="grow"><span class="latin">${dag}</span> <span class="secondary">›</span> <span class="latin secondary">${task}</span></span>
        <span class="chip">${label}</span><span class="caption latin" style="width:72px;text-align:right">${time}</span></div>`).join("")}</div>
    <div class="section">${heading("all", "资源池")}${attention ? pool("default_pool", 16, 4, 16) : pool("default_pool", 2, 1, 16)}</div>
    <div class="section">${heading("log", "DAG 导入错误", attention ? `<span class="count">1</span>` : "")}
      ${attention ? `<div class="row"><span class="mark-disc" style="background:var(--dc-failed-wash);color:var(--dc-failed)">${icon("cross")}</span><span class="grow latin">broken_dag.py</span><span class="caption">4 分钟前</span><span class="quiet-button">查看错误</span></div>`
        : `<div class="row"><span class="mark-disc">${icon("check")}</span><span class="secondary">所有 DAG 文件都能解析</span></div>`}</div>`;
}

function activity() {
  const events = [["engine", "success", "已触发 datacraft_spark_etl", "02:43"], ["airflow", "warning", "Airflow 状态异常：调度器异常", "02:31"],
    ["engine", "success", "csv-profile 成功，1.2 s", "01:58"], ["app", "running", "已连接 本机部署", "01:55"]];
  return `<div class="section">${heading("activity", "活动")}${events.map(([glyph, tone, text, at]) =>
    `<div class="event">${icon(glyph === "app" ? "link" : glyph)}<span>${dot(tone)} ${text}</span><span class="caption latin">${at}</span></div>`).join("")}</div>`;
}

function settings() {
  const preview = (label, side, paper, selected) => `<div class="preview${selected ? " selected" : ""}"><div class="mini">
    <span style="width:28%;background:${side}"></span><span style="flex:1;background:${paper}"></span></div>${label}</div>`;
  return `
    <div class="section">${heading("link", "连接")}<div class="form">
      <span class="secondary">名称</span><span class="input value">本机部署</span>
      <span class="secondary">引擎地址</span><span class="input value latin">http://127.0.0.1:8088</span>
      <span class="secondary">引擎令牌</span><span class="input value">●●●●●●●●●●●●●●●●</span>
      <span class="secondary">Airflow 地址</span><span class="input value latin">http://127.0.0.1:8080</span></div></div>
    <div class="section">${heading("sun", "外观")}<div class="theme-previews">
      ${preview("浅色", "#12304A", "#EDF2F6", state.theme === "light")}${preview("深色", "#0A1F30", "#0E1822", state.theme === "dark")}
      ${preview("跟随系统", "#12304A", "linear-gradient(135deg,#EDF2F6 50%,#0E1822 50%)", false)}</div></div>
    <div class="section">${heading("database", "本机数据")}<div class="form">
      <span class="secondary">位置</span><span class="input value latin">C:\\Users\\you\\.datacraft</span>
      <span class="secondary">占用</span><span class="metric latin">1.4 MB</span></div></div>
    <div class="section">${heading("info", "关于")}<div class="about">
      <svg class="app-icon" viewBox="0 0 48 48"><defs><linearGradient id="plate" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#12304A"/><stop offset="1" stop-color="#0E4D5E"/></linearGradient></defs>
        <rect width="48" height="48" rx="10.5" fill="url(#plate)"/>
        ${["#4CC2B2", "#4CC2B2", "#5DB2E6", "#4CC2B2", "#E6EEF5"].map((colour, index) =>
          `<rect x="${8.64 + index * 6.53}" y="14.4" width="4.13" height="19.2" rx="1.2" fill="${colour}"/>`).join("")}</svg>
      <div><div class="metric">数艺工坊</div><div class="secondary latin">版本 1.0.0</div></div></div>
      <div class="form"><span class="secondary">引擎 API</span><span class="latin">1.0.0</span><span class="secondary">Airflow</span><span class="latin">3.3.2</span></div></div>`;
}

const RENDER = { overview, jobs, pipelines, monitor, activity, settings };

function render() {
  document.documentElement.dataset.theme = state.theme;
  const title = VIEWS.find(([id]) => id === state.view)[1];
  const offline = state.sample === "offline" && state.view !== "settings";
  document.getElementById("app").innerHTML = `<div class="window${state.compact ? " compact" : ""}">${sidebar()}
    <div class="main">${topBar(title)}<main class="content">${offline ? empty() : RENDER[state.view]()}</main></div></div>`;
  document.querySelectorAll("[data-view]").forEach((link) => link.addEventListener("click", () => { state.view = link.dataset.view; sync(); }));
}

function sync() {
  document.querySelectorAll(".stage-bar button").forEach((button) => {
    button.setAttribute("aria-pressed", String(String(state[button.dataset.key]) === button.dataset.value));
  });
  render();
}

document.querySelectorAll(".stage-bar button").forEach((button) => button.addEventListener("click", () => {
  const value = button.dataset.value;
  state[button.dataset.key] = value === "true" ? true : value === "false" ? false : value;
  sync();
}));
sync();
