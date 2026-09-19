# 云部署教程

当前交付范围是代码与部署准备，不包含购买云资源或正式上线。费用比较以用户指定的东京、新加坡为准，
同时覆盖按需与常开：[交互费用报告](../../CLOUD-DEPLOYMENT-ANALYSIS.html)、[价格依据](pricing-evidence.md)。
数据处理契约见 [data-processing.md](../data-processing.md)，项目验证记录见
[工程复盘](../engineering-report/2026-09-19-review.md)。

## 1. 起步方案

单台 Linux 主机运行 PostgreSQL 18、Airflow 3.3.1（LocalExecutor、API、调度器、DAG 处理器、触发器）
与 JVM API；Spark 4.2.0 在 Airflow 容器内本地执行。规划起点为 8 GB 内存、至少 64 GB SSD，
同时只运行一个任务。这个容量尚不是用户真实数据的性能结论。

常开优先评估腾讯云国际站的小型月付主机；偶尔运行可评估 OCI A1 按需。Oracle 免费额度、ARM 可用性
和库存需单独确认，当前自动化容器运行证据是 Linux amd64。完整价格和适用条件只维护在费用报告，
不将旧促销、不同地区、不同内存档混作同一比较。

需要 Docker Engine、Docker Compose v2（支持 `up --wait`）、Bash 和 Python 3。
容器内包含 JDK 25、Spark 和 Airflow；不要求在云主机上额外安装 Maven、JDK 或 Airflow。
8 GB 小主机构建时也会占用较多内存和磁盘，优先在相同架构的构建机完成镜像，再按不可变标签/摘要分发；
本地构建后检查剩余磁盘，只清理明确不再需要的构建缓存，不清理生产数据卷。

## 2. 初始化并启动单机部署

在已准备好 Docker 的 Linux 主机上，从仓库根目录执行：

```bash
python3 deploy/scripts/deployment_env.py init
bash deploy/scripts/build-images.sh
bash deploy/scripts/compose-up.sh
```

初始化会创建 `deploy/compose/.env`，为数据库、管理员、API、JWT 和 Fernet 分别生成独立随机值，
使用仅所有者可读写的权限，不在终端打印密钥，不覆盖已有文件或符号链接。将文件安全备份，
从中读取初始管理员密码。不要重新生成 Fernet 密钥，否则已有加密连接可能无法解密。

部署前会验证 Compose 最终生效配置（包括外部环境变量覆盖），拒绝占位符、空密钥、不一致的
Airflow 密钥和不适合直接放入数据库 URI 的凭据。当前模板的 Postgres 用户名/库名使用简单标识符，
数据库密码须为至少 32 位 URL 安全字符；自定义复杂密码必须先实现 URI 编码，不能直接拼入现有 URI。
已有部署不应直接套用新初始化文件；先备份并检查其配置。修改 `.env` 不会自动轮换已有数据库或管理员密码。

启动脚本等待容器运行及已配置的健康检查通过，默认最长 300 秒；迁移失败、检查失败或超时会返回失败。
首次拉取/构建镜像的时间独立于服务启动健康检查。增加等待可设置 `DATACRAFT_START_TIMEOUT`。
失败时保留容器便于诊断，不自动删除数据。命令示例：

```bash
docker compose -f deploy/compose/docker-compose.yml ps -a
docker compose -f deploy/compose/docker-compose.yml logs --tail 100 airflow-init airflow-scheduler
```

不要公开粘贴日志中的连接信息或凭据。`airflow-init` 成功退出是正常的一次性初始化状态。
PostgreSQL 18 数据卷挂在 `/var/lib/postgresql`，数据写入 `/var/lib/postgresql/18/docker`。
旧版挂载到 `/var/lib/postgresql/data` 的现有卷须先检查并备份/恢复，不能假定改挂载点完成迁移。

## 3. 访问与任务验收

默认 Airflow 8080 与无认证的 JVM API 8088 都只绑定 `127.0.0.1`。从自己的电脑建立 SSH 转发：

```bash
ssh -L 8080:127.0.0.1:8080 -L 8088:127.0.0.1:8088 your-user@your-vm
```

随后打开 `http://localhost:8080`，用 `.env` 的管理员账号登录；`http://localhost:8088/health`
检查 JVM API。云防火墙仅允许自己的管理来源访问 SSH，不需要公开应用端口。需要团队访问时可配置
Cloudflare Tunnel 与 Access 或其他认证网关；Tunnel 要有可用出站路由，私网 NAT、域名或额外服务可能收费。
`AIRFLOW_WEB_BIND` 可显式修改，但应先完成相应访问控制。Swarm 的发布端口行为与此不同，见后文。

先解除暂停并触发 `datacraft_engine_jobs`，确认 noop、echo 两个任务成功；再运行：

- `datacraft_spark_etl`：给定容器中存在的 CSV 输入与独立输出路径，确认转换与行数校验均成功。
- `datacraft_sftp_ingest`：在 Airflow 配置 `datacraft_sftp` 连接与可信主机公钥，验证下载内容、转换、行数。

数据持久化位置为 `/opt/datacraft/data`，由命名卷保存；主机绝对路径不会自动出现在容器内。
先将验收样本放入该数据卷，或显式增加只读输入挂载。不要用 `compose exec` 的临时容器目录作为长期存储。
重复执行 overwrite 场景不应产生重复数据；不可让输入和输出目录相同或相互包含。
完整参数规则见数据处理文档。

`compose-up` 的服务检查不代替 DAG 执行验收。CI 另运行真实 Compose 下的调度器、Execution API 和
LocalExecutor 流程，并验证 PostgreSQL 备份恢复；正式云主机还需用实际网络、SFTP、数据量复测。

## 4. 资源限制与持续运行

默认 `AIRFLOW_PARALLELISM=1`、`AIRFLOW_PARSING_PROCESSES=1`、API worker 为 1，
`DATACRAFT_SPARK_MASTER=local[2]`。减少小主机上多个 DAG 同时启动 Spark JVM 的内存竞争。
DAG 自身还有 `max_active_runs=1`；它只是每个 DAG 的限制，不能代替全局并发限制。
如果直接调用 JVM API 或手动启动 Spark，它们不受 Airflow 并发控制，应避免和大任务同时运行。

记录代表性数据的耗时、峰值内存、磁盘余量、CPU 积分（如适用）和出站量，再决定提高并发或升配。
本轮不承诺固定空闲/峰值内存，也不承诺 4 GB 一定够用。Airflow 调度器关闭时不会按时调度任务；
按需停机方案适合手动/批次运行，不适合要求全天准点执行的流程。

Docker 标准输出日志每个容器最多 3 份、每份 10 MB。**这不清理 Airflow 任务日志、数据库历史或数据输出。**
为这些持久数据单独制定保留期和容量告警：先备份、确认过期范围，再用对应工具清理。
不要把失败任务记录全部删掉来表示系统健康，也不要无限累积备份版本。R2 是备份候选；当前 Spark
没有配置 S3A，不能直接把本地路径换成 `s3://` 就视为已经支持。

## 5. 备份、恢复和停机

需要同时保护：PostgreSQL 元数据、Fernet/JWT 等配置、DAG 源码版本、`datacraft-data` 中的业务文件，
以及需要保留的任务日志。存储快照与数据库一致性备份不是同一回事。

以下演示数据库备份；目标文件必须是新路径，`noclobber` 防止覆盖。目录权限限制为当前用户：

```bash
umask 077
mkdir -p backups
backup="backups/airflow-$(date -u +%Y%m%dT%H%M%SZ).dump"
(set -o noclobber; docker compose -f deploy/compose/docker-compose.yml exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup")
```

只有命令成功且恢复测试通过才可标记为有效备份；失败会留下不完整文件，应核对该次新建路径后移除。
暂停写入任务后再备份业务文件与配置，以维持同一恢复点。按既定保留期加密上传到选定备份存储，
记录校验和，保留异机副本。示例外部备份费用不是已经配置了自动上传。

恢复时先在隔离环境创建新数据库，用 `pg_restore --exit-on-error` 恢复，核对 DAG 运行记录与业务输出，
再决定生产切换；不要直接对现有生产数据库执行 `--clean`。CI 的 `deploy/tests/compose_smoke.sh`
演示了备份到临时文件、恢复到独立测试库、检查成功运行记录、再移除测试资源的过程。

```bash
bash deploy/scripts/compose-down.sh
```

上面的命令停止并移除容器，保留命名数据卷。`-v` 会删除数据卷，仅适用于明确要销毁的测试部署。
停止容器不等于停止云主机计费；按需场景须另外在云平台停止/解除分配 VM，并核对计费状态。
数据库磁盘和备份仍需保留和计费。再次启动 VM 后，用启动脚本复核服务与样本任务。

## 6. 多机 Swarm 与真实 Spark 集群

仓库保留 Swarm/Celery 模板，但小规模优先使用 Compose。默认本地卷要求将数据库、相关服务与工作进程
固定在 `DATACRAFT_DATA_NODE`，并非多机高可用。要跨节点扩展，必须先解决共享输入输出、任务日志、数据库
高可用、密钥管理和网络认证；不能仅增加 worker 数就宣称完成扩展。

在可信 `.env` 中填写实际 registry、固定镜像版本和数据节点主机名；Swarm 脚本使用 shell 加载此受信配置，
不要将外部不可信文本当作 `.env`。所有节点必须能拉取相同架构的镜像。发布操作由运维者执行：

```bash
registry=registry.example.com:5000
tag=YOUR_VERIFIED_VERSION
docker tag datacraft/airflow:latest "$registry/datacraft-airflow:$tag"
docker tag datacraft/jvm:latest "$registry/datacraft-jvm:$tag"
docker push "$registry/datacraft-airflow:$tag"
docker push "$registry/datacraft-jvm:$tag"
# 将同样 registry/tag、DATACRAFT_DATA_NODE 填入 deploy/compose/.env，然后：
bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
```

`swarm-deploy.sh` 在 manager 执行；`airflow-init.sh` 要在 scheduler 容器所在的数据节点执行。
Swarm 模板的 Airflow UI 发布端口不是 Compose 的回环绑定，必须在云防火墙/认证网关处限制访问；
JVM API 仅保留在 overlay 网络内。Redis 与数据库不对公网发布。生产密钥优先迁移到 secret 管理方案。

使用真实 Spark 集群时，把 `DATACRAFT_SPARK_MASTER` 设为集群地址；提交端和执行端需兼容的 Java 25、
Spark 4.2.0 以及各自可访问的数据路径。现有脚本保证提交参数一致，不替代远程集群权限和存储配置。

## 7. 验证与已知范围

```bash
node --test deploy/tests/cloud-costs.test.cjs
python3 -B -m unittest discover -s deploy/tests -v
```

CI 另外执行完整 JVM verify、真实 Airflow 流程、ShellCheck、Compose/Swarm 解析、镜像构建、实际 Compose
任务与备份恢复。`compose_smoke.sh` 仅限隔离 CI，会销毁其专用测试项目的数据卷，拒绝覆盖已有 `.env`。

这台 Windows 主机的 JDK 25.0.2 NIO selector 曾失败，完整 JVM 和 Spark 测试使用 Linux/WSL JDK 25.0.4.1。
不能据此断言所有 Windows/JDK 25 都不支持。当前尚未执行真实云部署、ARM 主机测试或多机 Swarm 故障切换；
这些边界与“部署资料已准备”分开记录，不作为已完成上线报告。
