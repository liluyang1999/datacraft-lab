# 数艺工坊（桌面控制台）

数艺工坊是 datacraft-lab 的桌面控制台：看两个后台（引擎 API 与 Airflow）是否健康，带参数运行作业，
触发与跟踪流水线，查看结果、任务与日志。它用 JavaFX 25 写成，应用在仓库的 `desktop/` 下，
测试在 `tests/jvm/datacraft-desktop/`，配置与缓存保存在用户主目录的 `.datacraft/` 里。

## 界面一览

界面以图形为主，文字只留名字与数字，解释在悬停提示里（设计见[界面设计](ui-design.md)）：

| 视图 | 看到什么、点什么 |
| --- | --- |
| 总览 | 两个后台各一个状态环，最近运行的成功率甜甜圈；每条流水线一条泳道（运行柱，▷ 直接触发）；最近运行的时间线 |
| 作业 | 按运行位置配图标的作业列表；参数表单、运行按钮（等待时转圈）、画出来的勾或叉与指标数字、最近运行的柱 |
| 流水线 | 泳道与调度开关；点一根运行柱选中那次运行，它的任务画成 DAG 图，点任务卡看日志 |
| 活动 | 按来源配图标的事件流，可筛选 |
| 设置 | 连接、主题预览、减少动效、刷新频率与本机数据 |

## 运行

日志面板每页显示 100 行，可用“首页 / 上一页 / 下一页 / 末页”查看全部已读取内容。
范围标签显示当前行号与总数；停在末页时跟随新日志，阅读前页时保持位置。“继续读取”获取服务端下一段。

需要 JDK 25.0.5 或更新的版本（与后端相同）。在仓库根目录：

```bash
./mvnw -B -ntp -Pdesktop -pl :datacraft-desktop -am verify
```

这个入口同时检查父工程里的测试源码格式；`make desktop-verify` 等价。`make format` 与 `make clean`
也启用 `desktop` profile，分别覆盖后端和桌面的全部源码格式、构建目录。

```bash
./mvnw -f desktop/pom.xml javafx:run
```

第一次打开时，在“设置”里添加连接：本机 Compose 部署的默认地址是引擎 API `http://127.0.0.1:8088`、
Airflow `http://127.0.0.1:8080`；引擎令牌是 `deploy/compose/.env` 里的 `DATACRAFT_API_TOKEN`，
Airflow 用户名与口令是其中的 `AIRFLOW_ADMIN_USERNAME` 与 `AIRFLOW_ADMIN_PASSWORD`。

## 打包

`cicd/desktop/package_desktop.py` 把应用做成自带 Java 运行时的应用目录 `desktop/target/dist/DataCraft`
（Windows 上双击 `DataCraft.exe`），运行时与 AOT 缓存大小随平台及 JDK 改变：

```bash
./mvnw -f desktop/pom.xml -B -ntp package
```

```bash
python3 -B cicd/desktop/package_desktop.py
```

脚本用 jdeps 与 jlink 生成精简运行时，用 jpackage 生成应用目录，然后以专用 `--smoke` 训练模式
生成 JDK 25 的 AOT 缓存（JEP 483/514）；训练过程遍历五视图的浅深主题，再执行 20 次普通缓存启动检查，
包含新建配置及恢复已保存页面。每次均确认 AOT 实际生效，标准错误中的未捕获异常也会让检查失败。
默认以每次 ≤ 1500 ms 验收，完整样本写到 `desktop/target/package/startup.json`，不丢弃首次启动或慢样本。
全部启动都使用 `desktop/target` 下的临时数据目录，不碰 `~/.datacraft`。
打包把同一批 JavaFX 平台 jar 的原生库放入运行时标准目录（Windows `bin/javafx`，Linux `lib`），
复制后的文件逐一与源 jar 比对 SHA-256，清单保存在 `desktop/target/package/javafx-natives.json`，
与启动样本一起上传为 CI 构件。每次启动将 JavaFX 缓存指向测试目录；出现缓存回退即失败，
防止遗漏原生库后仍借助用户目录启动成功，也省去每次启动的资源解压与缓存校验。
首个新配置为复用组建立数据库；复用组从首个样本起明确恢复页面及主题，不依赖机器的系统主题。
jpackage 选项通过 UTF-8 参数文件传递，避免 Windows 非中文环境把中文描述转为通配符后展开。
堆固定为 32/256 MiB；Windows 使用一级 JIT 编译降低启动竞争，Linux 保留默认分层编译。
开发入口、测试及包采用相同的平台策略，权衡和测量条件见 [流畅度](performance.md)。
AOT 缓存按文件时间戳核对应用的 jar：复制或压缩应用目录时保留时间戳；应用正常运行时允许 JVM 回退，
但打包检查使用 `AOTMode=on`，回退不能冒充缓存成功。
诊断机器负载时可传 `--startup-budget-ms 0` 仅记录结果，此模式不代表预算达标，CI 不使用它。
JavaFX 构建含 [版本锁定的无障碍修补](javafx-patch.md)。
构建还用同一版本 JavaFX 将三份主题 CSS 预编译为 `.bss`，保留 CSS 源文件。
[JavaFX 会优先加载相邻的二进制样式](https://github.com/openjdk/jfx/blob/jfx25/modules/javafx.graphics/src/main/java/com/sun/javafx/css/StyleManager.java)，
减少首屏解析；编译错误会阻止构建，完整界面门禁使用相同的生成资源。

## 端到端验证

单元、接口、存储与界面测试都在 `verify` 里，只访问本机测试桩，不连接外部服务。对真实后台的测试带 `e2e` 标签，默认不运行：

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
| [JavaFX 修补](javafx-patch.md) | 原生无障碍生命周期与属性契约、来源、构建校验与移除条件 |
| [测试](testing.md) | 测试分层、端到端步骤与约定 |
| [循环工程](loop/README.md) | 每一轮的闭环规则；[计划](loop/plan.md) 与 [记录](loop/log.md) |
