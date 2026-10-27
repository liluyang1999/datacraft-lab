# 后台接口的对接

所有请求经 `api.HttpTransport` 发出：一个共享的 `java.net.http.HttpClient`（HTTP/1.1、连接复用、
连接超时 3 s、虚拟线程执行器、不跟随重定向），每个请求单独设总时限。请求不带 `Origin` 头
（引擎 API 对带 `Origin` 的运行请求一律返回 403）。响应体用 Jackson 在后台线程解析成不可变记录；
未知字段忽略，所需字段缺失视为 `PROTOCOL` 错误。

## 引擎 API（`EngineClient`）

接口与语义以 [design/data-contracts.md](../../design/data-contracts.md) 的 “HTTP status contract” 为准。

| 方法 | 请求 | 成功 | 映射 |
| --- | --- | --- | --- |
| `health()` | `GET /health`（不带令牌） | 200 `{"status":"UP","service":"datacraft-api"}` | `EngineHealth(up, latency)` |
| `jobs()` | `GET /jobs` | 200 `{"jobs":[{"name","description"}]}` | `List<JobInfo>` |
| `run(name, params, timeout)` | `POST /jobs/{name}/runs?k=v…` | 200 结果体；500 同样的结果体 | `JobResult(jobName, status, message, metrics)` |

- 令牌：`Authorization: Bearer <token>`，只用于 `/jobs` 下的请求。
- 参数：作为查询串发送，键与值都按 UTF-8 百分号编码（空格编码为 `%20`，不用 `+`）；`lifecycle`
  是请求自身的参数，不作为作业参数发送。
- 运行是同步的：请求一直等到作业结束。总时限默认 30 min，可在设置里调整；超过时限是 `TIMED_OUT`，
  不是“无法连接”。“停止等待”与超时取消的都是客户端的等待，作业在服务端继续，界面与活动记录如实说明这一点。
  关闭窗口时仍在等待的运行同样被取消，应用不会为它挂着不退出。
- 状态码：401 → `UNAUTHORIZED`（令牌缺失或错误）；403 → `FORBIDDEN`；404 `unknown_job` → `NOT_FOUND`；
  503 `busy` → `BUSY`（附 `Retry-After`，界面提示“运行数已满，稍后再试”，不自动重试，避免重复运行）；
  400 → `REJECTED`；其他 5xx 且没有结果体 → `SERVER`。
- 指标值在协议里是字符串（如 `"rows":"2"`），界面按键名格式化：`durationMillis` 显示为时长，
  `bytes` 显示为大小，`rows`、`columns` 按千分位。

## Airflow 3.3.2 REST API v2（`AirflowClient`）

接口取自 `apache/airflow:3.3.2` 镜像内的 OpenAPI 定义（`v2-rest-api-generated.yaml` 与 FAB 的
`v2-fab-auth-manager-generated.yaml`）。

| 方法 | 请求 | 用途 |
| --- | --- | --- |
| `login()` | `POST /auth/token` `{"username","password"}` → 201 `{"access_token"}` | 换取 JWT |
| `health()` | `GET /api/v2/monitor/health`（不需认证） | 元数据库、调度器、触发器、DAG 处理器的状态与心跳 |
| `version()` | `GET /api/v2/version` | 版本号 |
| `dags()` | `GET /api/v2/dags?limit=100&order_by=dag_id&exclude_stale=true` | DAG 列表 |
| `setPaused(dag, paused)` | `PATCH /api/v2/dags/{dag_id}` `{"is_paused":…}` | 暂停或恢复调度 |
| `runs(dag, limit)` | `GET /api/v2/dags/{dag_id}/dagRuns?limit=20&order_by=-run_after` | 最近的运行（运行柱） |
| `tasks(dag)` | `GET /api/v2/dags/{dag_id}/tasks` → `{"tasks":[{"task_id","task_display_name","downstream_task_ids","operator_name"…}]}` | DAG 的形状：任务与下游任务，画成流水线视图的图；每个 DAG 每个连接只读一次 |
| `trigger(dag, conf, note)` | `POST /api/v2/dags/{dag_id}/dagRuns` `{"logical_date":null,"conf":{…},"note":…}` | 触发一次运行 |
| `taskInstances(dag, run)` | `GET /api/v2/dags/{dag_id}/dagRuns/{run_id}/taskInstances` | 任务状态 |
| `log(dag, run, task, map_index, try, token)` | `GET …/taskInstances/{task_id}/logs/{try_number}?full_content=false[&map_index=…][&token=…]`，`Accept: application/json` | 分段读取日志，`continuation_token` 取下一段；映射任务带上 `map_index` |

- 认证：先用配置中的用户名与口令换取 JWT，放在内存里，按令牌里的 `exp` 提前 60 s 续期；
  任一请求得到 401 时重新登录一次并重试，仍是 401 即报 `UNAUTHORIZED`（“用户名或口令不正确”）。
  JWT 不写入磁盘。
- 路径中的 `dag_id`、`dag_run_id`、`task_id` 逐段百分号编码（手动触发的运行 ID 含 `:` 与 `+`）。
- 状态：DAG 运行 `queued`、`running`、`success`、`failed`；任务实例另有 `scheduled`、`up_for_retry`、
  `up_for_reschedule`、`upstream_failed`、`skipped`、`deferred`、`removed`、`restarting`、
  `awaiting_input`，以及尚未调度时的 `null`。界面把它们归为成功、运行中、等待、失败、无五类（见界面设计）。
- 错误：409（运行 ID 冲突）与 422（请求体校验失败）→ `REJECTED`，附服务端 `detail`（422 的字段列表写成一行，
  409 的对象只取 `reason`，不显示其中的 SQL 语句）；403 → `FORBIDDEN`
  （账号没有对应权限）；404 → `NOT_FOUND`。

## 已知参数目录

两个后台都不公布参数的定义。应用内置一份目录 `job-catalog.json`，内容取自数据契约与 DAG 代码：
每个作业（`csv-profile`、`file-checksum`、`csv-to-parquet`、`row-count`、`spark-version`、`echo`、`noop`）与
每个 DAG（`datacraft_engine_jobs`、`datacraft_spark_etl`、`datacraft_sftp_ingest`）的参数名、说明、
是否必填与默认值。目录只用于提示与表单，不限制输入：未知的作业与 DAG 仍可用自定义键值或 JSON 运行。
后台的作业目录由 `GET /jobs` 实时取得，目录里没有的作业照常显示。

## 轮询

| 数据 | 何时轮询 | 间隔 |
| --- | --- | --- |
| 两个后台的健康 | 始终（窗口最小化时暂停） | 5 s；连续失败后依次 5 s、10 s、20 s，最长 30 s；节能模式 15 s |
| 作业目录 | 作业视图可见时 | 60 s，另可手动刷新 |
| DAG 列表与运行柱 | 总览或流水线视图可见时 | 10 s |
| 所选 DAG 的形状 | 第一次选中它时读一次 | 不轮询；切换连接后重新读取 |
| 所选 DAG 的运行 | 流水线视图可见时 | 有 queued 或 running 时 3 s，否则 15 s |
| 所选运行的任务实例 | 同上 | 运行未结束时 3 s，结束后停止 |

同一类数据同时只有一个请求在途：上一次还没返回时跳过这一轮，不排队。节能模式把表中其余的间隔放慢到三倍；
每一类轮询失败时都按健康检查的方式退避，最长 30 s。
