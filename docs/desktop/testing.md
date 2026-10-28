# 测试

## 分层

| 层 | 内容 | 测试类 | 需要 |
| --- | --- | --- | --- |
| 单元 | 退避与轮询（可控的时钟）、格式化、中英文间距、参数目录与作业的运行位置、只取最新值的移交、DAG 图的布局（链、菱形、未知下游、环） | `PollerTest`、`FormatsTest`、`JobCatalogTest`、`GraphLayoutTest` | 只需 JDK |
| 接口 | `EngineClient` 与 `AirflowClient` 对进程内的桩服务器（`com.sun.net.httpserver`，每个请求一个线程）：成功、401 与重新登录、403、404、409、422、503、5xx、等待超时、连接被拒、畸形 JSON、令牌续期、含 `null` 的运行配置、指标的顺序、映射任务的日志、DAG 的形状（任务与下游任务）、关闭时不等待进行中的运行 | `EngineClientTest`、`AirflowClientTest` | 只需 JDK |
| 服务 | 连接监测（正常、令牌错误、调度器异常、不可达后退避与恢复、未配置、切换连接时旧的检查不算数）；作业服务（成功、失败结果、运行数已满、等待超时、停止等待会中断请求） | `ConnectionMonitorTest`、`JobServiceTest` | 只需 JDK |
| 视图模型 | 视图模型对脚本化的后台，界面线程由测试控制的队列代替：切换连接后不留旧数据、预设列表与失败提示、“使用此连接”采用未保存的修改、测试结果不错配、新建中的连接不被清空、日志加载被新的请求取代、先前所选运行的任务不显示、暂停前的轮询不拨回开关、DAG 形状每个只读一次且不显示到别的 DAG 下、总览的结果构成与成功率 | `JobsModelTest`、`SettingsModelTest`、`PipelinesModelTest`、`OverviewModelTest` | 只需 JDK |
| 存储 | `LocalStore` 在临时目录：首次建库、迁移、拒绝更新的结构、设置往返、预设、缓存保留上限、清除；`SecretVault` 加解密往返与密钥文件权限 | `LocalStoreTest`、`VaultPlatformTest` | SQLite（`sqlite-jdbc` 自带本机库） |
| 界面 | 整个窗口对脚本化的后台：五个视图都显示数据（两个后台的环为正常，甜甜圈的成功率，每条泳道的运行柱，时间线上的 8 个点与按名字列出的 4 次，作业结果的勾与指标数字，流水线的运行柱与 DAG 图）；总览的 ▷ 打开那条流水线的触发面板，点一根运行柱换成那次运行的图；运行中的作业按钮是转动的圆，视图隐藏时停转、回来再转，结果到达后换成叉；点任务卡打开日志，Esc 关闭；浅色与深色都无 JavaFX 警告；重启后窗口位置、大小、主题与上次的视图恢复；预热后、动效打开时切换视图（50 ms）与主题（100 ms）、滚动 1500 行日志都没有超时的停顿。每个视图存为 `desktop/target/ui-snapshots/*.png` | `MainWindowTest` | JavaFX：Windows 直接运行，Linux CI 用 Xvfb |
| 端到端 | 对真实的 `serve-api` 与 Airflow：作业目录、`echo` 中文往返、`csv-profile` 指标、缺参数的失败结果、未知作业、错误令牌；Airflow 登录、健康、版本、三个 DAG 及其形状（与 DAG 文件里的 `chain` 一致）、暂停与恢复、触发 `datacraft_engine_jobs` 并跟踪到成功、任务与日志；整个窗口对真实后台运行作业，流水线视图把最新的运行画成 noop → echo | `EndToEndTest`、`EndToEndWindowTest`（标签 `e2e`） | 运行中的后台，见下文 |
| 打包 | 应用目录能生成并启动两次（训练 AOT 缓存、带缓存运行），首帧时间写入输出 | `cicd/desktop/package_desktop.py`；它自身的逻辑由 `tests/ci/test_package_desktop.py` 覆盖 | JDK 25 的 jlink 与 jpackage，显示器 |

测试代码在 `tests/jvm/datacraft-desktop/java/`（仓库的测试都在 `tests/` 下）。单元到界面测试由
`./mvnw -Pdesktop -pl :datacraft-desktop -am verify` 运行；这个可选反应堆同时运行父工程，
因此包含位于 `tests/jvm/` 下的测试源码格式检查，默认后端反应堆仍不构建桌面。CI 的 `desktop` 作业在 Xvfb 下运行它，用
`cicd/build/check_test_counts.py --project desktop` 核对实际执行的测试不少于 97 项（两个平台相同：各自只运行
`VaultPlatformTest` 里本平台的一项，`verify` 不运行 `e2e` 测试），通过时把数目写成公开注解；再运行打包脚本，
并把界面截图作为构件保存。

本轮新增的回归覆盖：已排到界面队列的旧轮询不能覆盖暂停操作；切换连接会清空日志与续读状态；
成功响应必须为 JSON 对象，空白或非对象响应不能伪装成正常的空结果；HTTP 错误即使没有正文也保留状态码；
最小窗口宽度下，流水线操作可见且页面无需横向滚动；打包训练的 JVM 参数在路径含空格时仍是一个参数。
抽屉关闭过程中重新打开会取消旧动画；较早触发请求的响应不能关闭后来打开的面板；连接切换会关闭旧触发表单。
实际执行数与打包结果记在 [L7 循环记录](loop/log.md#l7-全面复核与边界打磨2026-10-28)，下限不是当前测试总数。

## 端到端

`-Pe2e` 只运行带 `e2e` 标签的测试，后台由环境变量给出（见 [README](README.md#端到端验证)）：

1. 引擎：`java -jar datacraft-cli.jar --command serve-api --host 127.0.0.1 --port 18088`，令牌放在
   环境变量 `DATACRAFT_API_TOKEN` 里；`DATACRAFT_E2E_DATA_DIR` 指向一个本机目录。
2. 引擎与 Airflow：`deploy/scripts/compose-up.sh` 部署的 Compose 栈，引擎 `http://127.0.0.1:8088`，
   Airflow `http://127.0.0.1:8080`，令牌与口令取自 `deploy/compose/.env`。部署里的 DAG 默认是暂停的，
   触发测试会先恢复调度、结束后再暂停。

窗口测试自行触发并等待 `datacraft_engine_jobs` 成功，在新部署上单独运行也不依赖其他测试的顺序。
测试仅应指向可写的测试部署；运行会保留测试 DAG 记录，暂停状态在成功或失败后均恢复。
完整桌面门禁使用与打包程序相同的 32 MiB 初始堆、256 MiB 最大堆，包含大日志与动效检查。

每次的结果记在 [循环记录](loop/log.md)。

## 约定

- 单元到界面测试只访问本机测试桩，不连接外部服务或真实后台，不读写用户的 `~/.datacraft`（应用的数据目录指到临时目录）。
- 不依赖等待时间：轮询与退避用可控的时钟推进；界面测试等待的是界面上的条件，十秒为上限。
- 截图与布局验证等待三个真实 JavaFX 脉冲，不把三个空的 `runLater` 回调当作已经完成布局。
- 要在动效打开时测的界面测试，经设置项打开动效（`reducedMotion(false)`）：每次写设置（包括导航时记住
  上次的视图）都会重新应用这个设置项，直接改 `Motion` 只能维持到下一次导航。
- 每个缺陷先写一个能复现它的失败测试，再修复。
- 构建零警告：编译、测试 JVM 与 JavaFX 运行时都不得输出警告（JavaFX 的本机访问通过
  `--enable-native-access=javafx.graphics,ALL-UNNAMED` 显式授权）；界面测试把 JavaFX 记录的警告当作失败。
- 每个窗口测试结束时还核对 FX 线程未捕获的异常；原生回调或事件异常会让 JUnit 失败，不能只打印到标准错误。
