# 测试

## 分层

| 层 | 内容 | 测试类 | 需要 |
| --- | --- | --- | --- |
| 单元 | 退避与轮询（可控的时钟）、格式化、中英文间距、参数目录、只取最新值的移交 | `PollerTest`、`FormatsTest`、`JobCatalogTest` | 只需 JDK |
| 接口 | `EngineClient` 与 `AirflowClient` 对进程内的桩服务器（`com.sun.net.httpserver`，每个请求一个线程）：成功、401 与重新登录、403、404、409、422、503、5xx、等待超时、连接被拒、畸形 JSON、令牌续期、含 `null` 的运行配置、指标的顺序、映射任务的日志、关闭时不等待进行中的运行 | `EngineClientTest`、`AirflowClientTest` | 只需 JDK |
| 服务 | 连接监测（正常、令牌错误、调度器异常、不可达后退避与恢复、未配置、切换连接时旧的检查不算数）；作业服务（成功、失败结果、运行数已满、等待超时、停止等待会中断请求） | `ConnectionMonitorTest`、`JobServiceTest` | 只需 JDK |
| 视图模型 | 视图模型对脚本化的后台，界面线程由测试控制的队列代替：切换连接后不留旧数据、预设列表与失败提示、“使用此连接”采用未保存的修改、测试结果不错配、新建中的连接不被清空、日志加载被新的请求取代、先前所选运行的任务不显示、暂停前的轮询不拨回开关 | `JobsModelTest`、`SettingsModelTest`、`PipelinesModelTest` | 只需 JDK |
| 存储 | `LocalStore` 在临时目录：首次建库、迁移、拒绝更新的结构、设置往返、预设、缓存保留上限、清除；`SecretVault` 加解密往返与密钥文件权限 | `LocalStoreTest`、`VaultPlatformTest` | SQLite（`sqlite-jdbc` 自带本机库） |
| 界面 | 整个窗口对脚本化的后台：五个视图都显示数据，运行一个作业，打开触发面板与日志、Esc 关闭，浅色与深色都无 JavaFX 警告；重启后窗口位置、大小、主题与上次的视图恢复；预热后切换视图（50 ms）与主题（100 ms）、滚动 1500 行日志都没有超时的停顿。每个视图存为 `desktop/target/ui-snapshots/*.png` | `MainWindowTest` | JavaFX：Windows 直接运行，Linux CI 用 Xvfb |
| 端到端 | 对真实的 `serve-api` 与 Airflow：作业目录、`echo` 中文往返、`csv-profile` 指标、缺参数的失败结果、未知作业、错误令牌；Airflow 登录、健康、版本、三个 DAG、暂停与恢复、触发 `datacraft_engine_jobs` 并跟踪到成功、任务与日志；整个窗口对真实后台运行作业 | `EndToEndTest`、`EndToEndWindowTest`（标签 `e2e`） | 运行中的后台，见下文 |
| 打包 | 应用目录能生成并启动两次（训练 AOT 缓存、带缓存运行），首帧时间写入输出 | `cicd/desktop/package_desktop.py`；它自身的逻辑由 `tests/ci/test_package_desktop.py` 覆盖 | JDK 25 的 jlink 与 jpackage，显示器 |

测试代码在 `tests/jvm/datacraft-desktop/java/`（仓库的测试都在 `tests/` 下）。单元到界面测试由
`./mvnw -f desktop/pom.xml verify` 运行；CI 的 `desktop` 作业在 Xvfb 下运行它，用
`cicd/build/check_test_counts.py --project desktop` 核对实际执行的测试不少于 76 项（两个平台相同：各自只运行
`VaultPlatformTest` 里本平台的一项，`verify` 不运行 `e2e` 测试），通过时把数目写成公开注解；再运行打包脚本，
并把界面截图作为构件保存。

## 端到端

`-Pe2e` 只运行带 `e2e` 标签的测试，后台由环境变量给出（见 [README](README.md#端到端验证)）：

1. 引擎：`java -jar datacraft-cli.jar --command serve-api --host 127.0.0.1 --port 18088`，令牌放在
   环境变量 `DATACRAFT_API_TOKEN` 里；`DATACRAFT_E2E_DATA_DIR` 指向一个本机目录。
2. 引擎与 Airflow：`deploy/scripts/compose-up.sh` 部署的 Compose 栈，引擎 `http://127.0.0.1:8088`，
   Airflow `http://127.0.0.1:8080`，令牌与口令取自 `deploy/compose/.env`。部署里的 DAG 默认是暂停的，
   触发测试会先恢复调度、结束后再暂停。

每次的结果记在 [循环记录](loop/log.md)。

## 约定

- 单元到界面测试不访问网络与真实后台，不读写用户的 `~/.datacraft`（应用的数据目录指到临时目录）。
- 不依赖等待时间：轮询与退避用可控的时钟推进；界面测试等待的是界面上的条件，十秒为上限。
- 每个缺陷先写一个能复现它的失败测试，再修复。
- 构建零警告：编译、测试 JVM 与 JavaFX 运行时都不得输出警告（JavaFX 的本机访问通过
  `--enable-native-access=javafx.graphics,ALL-UNNAMED` 显式授权）；界面测试把 JavaFX 记录的警告当作失败。
