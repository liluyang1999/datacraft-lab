# 架构

## 分层

```
┌──────────────────────────── ui ────────────────────────────┐
│ shell（窗口、侧栏、顶栏、通知）  views（总览、作业、流水线、活动、设置）│
│ components（状态环、运行柱、DAG 图、时间线、参数编辑器…） theme（CSS、字体、图标）│
└───────────────▲──────────────────────────────▲─────────────┘
                │ 属性绑定（只在 FX 线程）        │ 用户操作
┌───────────────┴────────── viewmodel ──────────┴────────────┐
│ 每个视图一个 ViewModel：JavaFX 属性 + 命令；不引用任何控件         │
└───────────────▲──────────────────────────────▲─────────────┘
                │ CompletableFuture（后台完成，结果回到 FX 线程）   │
┌───────────────┴─────────── service ──────────┴─────────────┐
│ ConnectionMonitor（健康与延迟）  JobService  PipelineService     │
│ Poller（只为可见视图轮询、失败退避）  ActivityLog                 │
└───────▲───────────────────────────────────────────▲────────┘
        │                                           │
┌───────┴──────── api ────────┐   ┌──────────── store ────────┴───┐
│ HttpTransport（java.net.http） │   │ LocalStore（SQLite，单线程）     │
│ EngineClient  AirflowClient   │   │ Settings / Profiles / Presets   │
│ 记录类型的模型、ApiError       │   │ RecentRuns / Activity  Vault     │
└───────────────────────────────┘   └─────────────────────────────────┘
```

依赖只向下：`ui → viewmodel → service → api / store`；`api` 与 `store` 互不依赖，也不依赖 JavaFX
（`store` 除外的所有下层都可以在没有 JavaFX 工具包的单元测试里运行）。组合根是 `app.AppContext`：
它创建全部服务并交给视图；测试用同一个组合根，只把后台换成进程内的桩服务器、数据目录换成临时目录。

## 包结构

| 包 | 职责 |
| --- | --- |
| `desktop` | `Launcher`（`main`：检查运行环境，`--smoke`，开始并行打开本机数据，再启动 JavaFX）与 `DesktopApp`（`Application`：窗口、启动计时、首帧后的工作） |
| `desktop.app` | `AppContext` 组合根、`AppPaths`（`~/.datacraft` 的位置）、`Preferences`（启动时读入内存的设置，后台写回）、`Logging`（日志文件与格式） |
| `desktop.api` | `HttpTransport`、`EngineClient`、`AirflowClient`、模型记录（`EngineModels`、`AirflowModels`）、`RunTone`（五类状态）、`ApiError` 与 `ApiException` |
| `desktop.store` | `LocalStore`（连接、迁移、写入串行化）、`SettingsRepository`、`ProfileRepository` 与 `Profile`、`PresetRepository`、`RecentRunRepository`、`ActivityRepository`、`SecretVault`（`DpapiVault`、`FileKeyVault`） |
| `desktop.service` | `Session`（当前连接与两个客户端）、`ProfileService`（连接配置、测试连接、内存中的秘密）、`ConnectionMonitor` 与 `BackendStatus`、`Poller` 与 `Ticker`（含失败退避）、`JobService`、`PipelineService`、`JobCatalog`（已知参数）、`ActivityLog`、`Messages`（出错时的说明） |
| `desktop.viewmodel` | `StatusModel`、`OverviewModel`、`JobsModel`、`PipelinesModel`、`ActivityModel`、`SettingsModel`；`DagRow`、`Labels`（状态与类型的中文名）、`Visibility` |
| `desktop.ui.shell` | `MainWindow`、`Navigator`（视图切换与懒加载）、`Sidebar`、`TopBar`、`Toasts`、`Drawer`（侧滑面板）、`Dialogs`、`AppIcon` |
| `desktop.ui.view` | 五个视图，各自只做布局与绑定；`Segments`（运行柱的高度、提示与选择键） |
| `desktop.ui.component` | 图形：`StatusRing`（状态环）、`Donut`（结果构成）、`RunBars`（运行柱）、`TimeStrip`（最近运行的时间线）、`TaskGraph` 与 `GraphLayout`（DAG 图及其纯函数布局）、`RunMark`（勾与叉）、`BusyRing`（等待中的转圈）；日志：`LogPager`（每页 100 行、末页跟随、关闭释放监听）；表单与布局：`ParamEditor`、`EmptyState`、`Rows`（短列表的就地更新）、`Controls`（含提示用的 ⓘ 与标签片）、`Icons`（`Glyph` 枚举的线条图标）、`Tones` |
| `desktop.ui.theme` | `ThemeManager`（浅色、深色、跟随系统）、`Fonts`（中文用系统字体，数字用内置 Barlow）；样式表 `base.css`、`light.css`、`dark.css` |
| `desktop.ui` | `Motion`（全部动效与“减少动效”）、`FxWatchdog`（FX 线程停顿监测） |
| `desktop.util` | `Fx`（FX 线程的执行器）、`Latest`（只移交最新值）、`Lists`（按主键就地更新）、`Maps`（保序、可含 null 的不可变副本）、`Formats` |

## 线程模型

- **FX 线程**只做布局、绑定与动画。任何网络、磁盘、JSON 解析与加解密都不在这里执行；视图模型只经
  `Executor ui`（应用里是 `Fx.UI`）改动属性与列表，后台线程需要的选择（所选 DAG、运行）另存为 `volatile` 副本。
- **网络：**一个共享的 `HttpClient`（HTTP/1.1，连接复用，连接超时 3 s），执行器是虚拟线程；
  每个请求都有自己的总时限（读接口 10 s；作业运行由用户设定，默认 30 min，可取消）。
- **本机存储：**一个名为 `datacraft-store` 的单线程执行器持有唯一的 SQLite 连接，所有读写排队执行，
  因此没有锁竞争；数据库用 WAL 模式。
- **调度：**一个单线程的 `ScheduledExecutorService` 只负责“到点”，到点后把请求交给上面的执行器。
- **回到界面：**服务返回 `CompletableFuture`，`Poller` 经 `Latest` 和指定的界面执行器交付结果：
  新结果到达时若旧结果尚未应用则直接替换，不会堆积。实际应用时再次检查轮询代次，已排入界面队列的旧响应
  在停止或重启后同样作废，不能覆盖暂停操作或新连接的状态。
- **侧滑面板：**面板持有当前内容及一个可取消的动画；重新打开时释放旧内容并停止旧动画。
  触发请求提交和回调时均确认表单仍属于当前面板，关闭或切换连接后的旧表单不能继续操作新界面。

## 数据流：一次轮询

```
Poller 到点 ──► PipelineService.dags()  （后台）
                   │ AirflowClient：带 JWT 的 GET /api/v2/dags
                   │ 401 ──► 重新 POST /auth/token 一次 ──► 重试
                   ▼
              List<Dag>（不可变记录）
                   │ Latest → Fx.UI
                   ▼
PipelinesModel.applyDags(...)：按 dag_id 就地更新 ObservableList（只改变化的行）
                   ▼
Rows 只更新变化的流水线泳道
```

失败时 `Poller` 拉长间隔：连续失败后依次是基础间隔的 1、2、4 倍，最长 30 s；视图显示最近一次成功的数据
并标注“数据停在 hh:mm:ss”，恢复后立刻刷新。窗口最小化时所有轮询暂停，还原时立即刷新一次。

## 本机数据（`~/.datacraft/`）

| 文件 | 内容 |
| --- | --- |
| `datacraft.db` | SQLite：设置、连接配置、参数预设、最近运行（缓存）、活动记录（缓存） |
| `vault.key` | 非 Windows 系统上加密秘密用的密钥，权限只对当前用户开放（Windows 使用 DPAPI，没有这个文件） |
| `logs/desktop-0.log` | 应用日志（`-0` 最新，单个 1 MB、保留 3 个），含启动各阶段的时间，不含秘密 |
| `tmp/` | JDK 的 NIO 管道在 Windows 上创建的 AF_UNIX 套接字（随建随删）。有的机器上系统 TEMP 目录放不下这类文件，HTTP 客户端会因此无法创建，所以应用把 `jdk.net.unixdomain.tmpdir` 指到这里 |

可用环境变量 `DATACRAFT_DESKTOP_HOME` 改到其他目录（测试与便携使用）。表结构由 `PRAGMA user_version`
记录版本，启动时按顺序迁移；缓存类数据有保留上限（最近运行每个配置 200 条且不超过 14 天，活动记录 500 条），
“设置 → 本机数据”可以一键清除。

## 错误模型

`ApiException` 携带 `ApiError`：`UNREACHABLE`（连接被拒、DNS、连接超时）、`TIMED_OUT`（已发出，时限内没有回答；
服务端可能仍在处理，作业运行就是这样）、`UNAUTHORIZED`（401）、
`FORBIDDEN`（403）、`NOT_FOUND`、`BUSY`（引擎 503，带 `Retry-After`）、`REJECTED`（400/409/422，附服务端说明）、
`SERVER`（5xx）、`PROTOCOL`（无法解析的响应）。作业失败（引擎返回 500 与结果体）不是异常，而是状态为 FAILED
的 `JobResult`。界面按错误种类给出原因与下一步，例如“令牌不正确：在设置里更新引擎令牌”。

## 启动顺序

1. `Launcher` 确认 Java 不低于 25、JavaFX 可用，否则打印一行原因并退出（退出码 2）。
2. `Launcher` 初始化 `~/.datacraft` 与日志，另起线程打开 SQLite 并迁移、读入设置、创建各服务，
   同时启动 JavaFX 工具包；两者大约同时就绪。打不开本机数据（例如结构比应用新）时，窗口只显示原因并退出。
3. 按屏幕可用区域计算初始尺寸或修正保存的位置，显示窗口与外壳并直接呈现首个视图；
   其余视图首次打开时才创建（懒加载）。后续切换才淡入，快速再次切换会停止旧动画并恢复原缓存策略。
4. 等待第一个布局脉冲结束，在下一脉冲记录首帧；这个软件计时点不等于显示器的物理呈现时间。
5. 随后绘制图标、恢复上次连接并启动健康检查；3 s 后启用 `FxWatchdog`。各阶段的 OS 进程年龄估计写入日志。

入口、日志配置、存储开始及后续阶段同时记录进程累计 CPU 时间，便于区分实际计算与启动等待；
入口观测保留当时的时间，不把稍后的日志配置时间误记为进入 `main` 的时刻。
Linux 的 `ProcessHandle.startInstant` 依赖秒级系统启动基准，不能用该估计执行毫秒预算。
打包父进程从调用启动器前用单调时钟计时，每 5 ms 观察首帧日志，向上取整到毫秒；
启动器、JVM、日志刷新和观察延迟都包含在内。成功、失败或超时后均停止观察线程。

HTTP 客户端及 TLS 仅在首次请求时由后台虚拟线程创建，多个请求共享同一初始化结果。
初始化前取消不会在客户端就绪后发出过期请求；关闭会拒绝等待者，并关闭迟到的客户端与自有执行器。
无连接的首帧不再为网络初始化等待；实际连接的第一批请求承担这项开销，界面不阻塞。

## 打包

`cicd/desktop/package_desktop.py` 在 `package` 之后生成应用目录：jdeps 找出应用与依赖用到的 JDK 模块，
jlink 生成只含这些模块与本平台 JavaFX 模块的运行时，jpackage 把应用 jar、依赖与运行时做成
`DataCraft`（Windows 上是 `DataCraft.exe`）。JavaFX 模块先经过版本与校验和锁定的
[无障碍生命周期与 Windows 属性契约修补](javafx-patch.md)，不修改依赖缓存。
平台 jar 内的原生库还需独立安装到 JavaFX 的 JRT 加载位置：Windows `runtime/bin/javafx`，
Linux `lib/runtime/lib`。jlink 只保留 jar 资源，不会完成这一步；缺失时 JavaFX 会解压和校验
用户缓存。脚本拒绝越界条目、重复库和运行时文件覆盖，jpackage 复制后按源 jar 的 SHA-256
再次验证；启动门禁拒绝缓存回退，确保交付物自身完整。
随后以隔离的 `--smoke` 训练模式遍历五视图及两种主题，生成 AOT 缓存
（`-XX:AOTCacheOutput`），把 `-XX:AOTCache=$APPDIR/datacraft.aot` 写进启动器的配置。
最后执行 10 次新配置和 10 次复用配置启动，后者覆盖不同已保存页面和主题；每次都确认 AOT 类实际生效，
任一次超过 1500 ms 即失败。CI 的 `desktop` 矩阵在 Windows 与 Linux（Xvfb）执行同样的步骤。
