# 数艺工坊（桌面控制台）

数艺工坊是 datacraft-lab 的桌面控制台：看两个后台（引擎 API 与 Airflow）是否健康，带参数运行作业，
触发与跟踪流水线，查看结果、任务与日志。它用 JavaFX 25 写成，应用在仓库的 `desktop/` 下，
测试在 `tests/jvm/datacraft-desktop/`，配置与缓存保存在用户主目录的 `.datacraft/` 里。

## 运行

需要 JDK 25.0.5 或更新的版本（与后端相同）。在仓库根目录：

```bash
./mvnw -f desktop/pom.xml -B -ntp verify
```

```bash
./mvnw -f desktop/pom.xml javafx:run
```

第一次打开时，在“设置”里添加连接：本机 Compose 部署的默认地址是引擎 API `http://127.0.0.1:8088`、
Airflow `http://127.0.0.1:8080`；引擎令牌是 `deploy/compose/.env` 里的 `DATACRAFT_API_TOKEN`，
Airflow 用户名与口令是其中的 `AIRFLOW_ADMIN_USERNAME` 与 `AIRFLOW_ADMIN_PASSWORD`。

## 打包

`cicd/desktop/package_desktop.py` 把应用做成自带 Java 运行时的应用目录 `desktop/target/dist/DataCraft`
（Windows 上双击 `DataCraft.exe`），约 116 MB，另含约 45 MB 的 AOT 缓存：

```bash
./mvnw -f desktop/pom.xml -B -ntp package
```

```bash
python3 -B cicd/desktop/package_desktop.py
```

脚本用 jdeps 与 jlink 生成精简运行时，用 jpackage 生成应用目录，然后以 `--smoke`（首帧后 2 秒自动退出）
启动一次训练 JDK 25 的 AOT 缓存（JEP 483/514），再带着缓存启动一次检查。两次启动都使用 `desktop/target`
下的临时数据目录，不碰 `~/.datacraft`。AOT 缓存按文件时间戳核对应用的 jar：复制或压缩应用目录时保留
时间戳即可继续使用；时间戳变化时应用照常运行，只是不用缓存、启动慢约三分之一。

## 端到端验证

单元、接口、存储与界面测试都在 `verify` 里，不访问网络。对真实后台的测试带 `e2e` 标签，默认不运行：

```bash
./mvnw -f desktop/pom.xml -B -ntp -Pe2e test
```

它从环境变量读取后台：`DATACRAFT_E2E_ENGINE_URL`、`DATACRAFT_E2E_ENGINE_TOKEN`、
`DATACRAFT_E2E_DATA_DIR`（引擎与测试都能读的目录，用于 `csv-profile`）、`DATACRAFT_E2E_AIRFLOW_URL`、
`DATACRAFT_E2E_AIRFLOW_USER`、`DATACRAFT_E2E_AIRFLOW_PASSWORD`；没有设置的后台对应的测试会跳过。

## 文档

| 文档 | 内容 |
| --- | --- |
| [章程](charter.md) | 目标、对接的后台、约束、非目标与成功标准 |
| [架构](architecture.md) | 分层、包结构、线程模型、数据流、本机数据、错误模型、启动顺序、打包 |
| [界面设计](ui-design.md) | 视觉概念、色彩、字体、布局、组件、动效与文案 |
| [接口对接](api-integration.md) | 引擎 API 与 Airflow REST API 的请求、认证、错误与轮询 |
| [流畅度](performance.md) | 预算、实测、手段与回归 |
| [测试](testing.md) | 测试分层、端到端步骤与约定 |
| [循环工程](loop/README.md) | 每一轮的闭环规则；[计划](loop/plan.md) 与 [记录](loop/log.md) |
