# 云部署教程

当前交付范围是代码与部署准备，不包含购买云资源或正式上线。费用比较以用户指定的东京、新加坡为准，
同时覆盖按需与常开：[交互费用报告](../../CLOUD-DEPLOYMENT-ANALYSIS.html)、[价格依据](pricing-evidence.md)。
数据处理契约见 [data-processing.md](../data-processing.md)。当前的综合评估见
[项目评估报告](../../PROJECT-REVIEW.html)，本轮改动与验证记录见
[2026-09-25 工程复盘](../engineering-report/2026-09-25-review.md)。

## 1. 起步方案

单台 Linux 主机运行 PostgreSQL 18、Airflow 3.3.2（LocalExecutor、API、调度器、DAG 处理器、触发器）
与 JVM API；Spark 4.2.0 在 Airflow 容器内本地执行。Airflow 3.3.2 的约束文件把 FAB provider 从 3.8.0
升到 3.9.0，修复多项安全公告（编号见 [CHANGELOG](../../CHANGELOG.md)）。规划起点为 8 GB 内存、
至少 64 GB SSD，同时只运行一个任务。这个容量尚不是用户真实数据的性能结论。

常开优先评估腾讯云国际站的小型月付主机；偶尔运行可评估 OCI A1 按需。Oracle 免费额度、ARM 可用性
和库存需单独确认，当前自动化容器运行证据是 Linux amd64。完整价格和适用条件只维护在费用报告，
不将旧促销、不同地区、不同内存档混作同一比较。

需要 Docker Engine、Docker Compose v2（支持 `up --wait`）、Bash 和 Python 3。镜像内包含 Java 25 运行时、
Spark 和 Airflow；不要求在云主机上额外安装 Maven、JDK 或 Airflow。构建时要能访问 Docker Hub：
`build-images.sh` 每次先拉取基础镜像（Maven 构建镜像、`eclipse-temurin:25-jre`、`apache/airflow:3.3.2`），
使重建的镜像带上这些标签当前的 JDK 与系统补丁；`MAVEN_IMAGE`、`JRE_IMAGE`、`AIRFLOW_IMAGE`、
`SPARK_IMAGE` 可改用其他基础镜像。

只用 `bash deploy/scripts/build-images.sh` 构建镜像。`Dockerfile.jvm`、`Dockerfile.airflow`、
`Dockerfile.spark` 的 `JAR_IMAGE` 没有默认值，由脚本传入本机构建的 `datacraft/jar-builder:latest`；
`docker compose build`、`docker compose up --build` 或直接 `docker build` 缺少这个参数会失败，
不会去 Docker Hub 解析同名镜像。Compose 对 `datacraft/*` 镜像设置 `pull_policy: never`，
`docker compose pull` 也不拉取它们：这些名称在 Docker Hub 上属于本项目以外的命名空间。

8 GB 小主机构建时也会占用较多内存和磁盘，可在相同架构的构建机完成镜像。Compose 只使用本机名为
`datacraft/airflow:latest` 与 `datacraft/jvm:latest` 的镜像，因此要以这两个名称导入目标主机
（例如 `docker save` / `docker load`）。本地构建后检查剩余磁盘，只清理明确不再需要的构建缓存，
不清理生产数据卷。

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
`AIRFLOW_UID` 保持 50000 即可（Linux 也是）：没有绑定挂载主机目录，不需要与主机用户一致。

部署前会验证 Compose 最终生效配置（包括外部环境变量覆盖），拒绝占位符、空密钥、不一致的
Airflow 密钥和不适合直接放入数据库 URI 的凭据。当前模板的 Postgres 用户名/库名使用简单标识符，
数据库密码须为至少 32 位 URL 安全字符；自定义复杂密码必须先实现 URI 编码，不能直接拼入现有 URI。
这些强度检查只在 `compose-up.sh` 与 `swarm-deploy.sh` 中运行。两个栈文件对 `POSTGRES_PASSWORD`、
`AIRFLOW_FERNET_KEY`、`AIRFLOW_API_SECRET_KEY`、`AIRFLOW_JWT_SECRET` 使用 `${VAR:?...}`：任一值缺失
或为空时，直接执行的 `docker compose` 或 `docker stack deploy` 在变量插值阶段即报错，不再回退到公开
默认值；但直接执行不检查长度与格式。修改 `.env` 不会自动轮换已有数据库或管理员密码。

启动脚本先检查 `.env`，再确认本机存在 `datacraft/airflow:latest` 与 `datacraft/jvm:latest`，缺少时
提示先运行 `build-images.sh` 并退出。随后等待容器运行及已配置的健康检查通过，默认最长 300 秒；
迁移失败、检查失败或超时会返回失败。镜像构建不计入这个等待时间。增加等待可设置
`DATACRAFT_START_TIMEOUT`。失败时保留容器便于诊断，不自动删除数据。命令示例：

```bash
docker compose -f deploy/compose/docker-compose.yml ps -a
docker compose -f deploy/compose/docker-compose.yml logs --tail 100 airflow-init airflow-scheduler
```

不要公开粘贴日志中的连接信息或凭据。`airflow-init` 成功退出是正常的一次性初始化状态。它经标准输入把
管理员密码交给 `airflow users create`，密码不出现在进程参数中；日志里的
`Warning: Password input may be echoed.` 无害。账号已存在时只输出
`Airflow admin account already exists; password unchanged.`，不会按 `.env` 改密。

DAG 与 CLI jar 都打包在 Airflow 镜像中，Compose 不再绑定挂载 `orchestration/airflow/dags`。
`git pull` 之后先运行 `build-images.sh` 再运行 `compose-up.sh`，否则运行的仍是旧 DAG 与旧 jar；
不能再通过挂载目录在线修改 DAG。

PostgreSQL 18 数据卷挂在 `/var/lib/postgresql`，数据写入 `/var/lib/postgresql/18/docker`。
旧版挂载到 `/var/lib/postgresql/data` 的现有卷须先检查并备份/恢复，不能假定改挂载点完成迁移。

### 升级已有部署

本轮有不兼容变化：必需密钥缺失即拒绝启动，DAG 改由镜像提供，`datacraft_sftp` 连接必须校验主机公钥，
DAG 数据路径必须位于 `DATACRAFT_DATA_ROOT` 内。已有部署不要直接套用新初始化文件，按顺序执行：

1. 暂停 DAG 并等待运行中的任务结束。在 `git pull` 之前按第 5 节做 `pg_dump` 备份并另存 `.env`：
   缺少必需密钥时，新的 Compose 文件连 `exec`、`ps`、`down` 都无法解析。
2. 需要回退能力时先保留当前镜像，例如 `docker tag datacraft/airflow:latest datacraft/airflow:pre-upgrade`，
   `datacraft/jvm` 同理；重建会覆盖 `latest`。回退需要旧版代码、旧镜像与升级前的数据库备份三者一致。
3. `git pull` 后运行 `bash deploy/scripts/build-images.sh`。
4. 检查 `.env`：四个必需密钥都要有值；`compose-up.sh` 还要求它们与 `AIRFLOW_ADMIN_PASSWORD` 通过上文的
   强度检查：每个值至少 32 个字符、五个值互不相同，Fernet 密钥须是 32 字节的 URL 安全 base64 编码。
   旧 Compose 曾回退到 `airflow`、空 Fernet 密钥或 `please-change-me`。新值可用
   `python3 deploy/scripts/deployment_env.py init backups/new.env` 生成到一个新文件（`backups/` 已在
   第 1 步创建），只复制需要替换的项，用完删除该文件。
   - 数据库密码：先把新值写入 `.env`，再进入 psql 运行 `\password` 并两次输入同一新值；
     密码不经过命令行参数。

     ```bash
     docker compose -f deploy/compose/docker-compose.yml exec postgres \
       sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
     ```

   - `AIRFLOW_API_SECRET_KEY` 与 `AIRFLOW_JWT_SECRET`：在没有运行中任务时直接替换。
   - Fernet 密钥：保留原值，否则已加密的连接和变量无法解密。旧文件从未设置时补一个新密钥；此前未加密
     保存的连接和变量仍可读取，但要重新保存才会加密。
   - 管理员密码：见第 6 步。
5. `AIRFLOW_UID` 不是 50000 时，已有的 `datacraft-data` 卷不会得到新镜像的组写权限（Docker 只为空卷
   复制镜像内容）。执行一次下面的命令，或把 `AIRFLOW_UID` 改回 50000：

   ```bash
   docker compose -f deploy/compose/docker-compose.yml run --rm --no-deps --user 0:0 \
     --entrypoint chmod airflow-scheduler g+rwx /opt/datacraft/data
   ```

6. 运行 `bash deploy/scripts/compose-up.sh`。若替换了 `AIRFLOW_ADMIN_PASSWORD`，已有账号仍是旧密码，
   执行下面的命令并两次输入 `.env` 中的新值：

   ```bash
   docker compose -f deploy/compose/docker-compose.yml exec airflow-scheduler \
     airflow users reset-password --username "<管理员用户名>"
   ```

7. 解除暂停前按第 3 节为 `datacraft_sftp` 连接设置 `host_key`、检查触发参数中的路径，再做样本任务验收。

Swarm 部署另需检查旧 `.env`：`swarm-deploy.sh` 拒绝空的或为 `latest` 的 `DATACRAFT_TAG`（旧模板为
`latest`）；旧模板的 `AIRFLOW_WORKER_REPLICAS=2` 仍然生效（新模板为 1）。`airflow-init.sh` 不再进入调度器
容器执行初始化，改为等待一次性服务 `datacraft_airflow-init`，见第 6 节。

## 3. 访问与任务验收

默认 Airflow 8080 与无认证的 JVM API 8088 都只绑定 `127.0.0.1`。`compose-up.sh` 与 `swarm-deploy.sh`
对 Compose 生效配置执行的检查拒绝把 `datacraft-api` 发布到 `127.0.0.1`、`::1` 以外的地址，也拒绝它的
非只读挂载（tmpfs 除外）。从自己的电脑建立 SSH 转发：

```bash
ssh -L 8080:127.0.0.1:8080 -L 8088:127.0.0.1:8088 your-user@your-vm
```

随后打开 `http://localhost:8080`，用 `.env` 的管理员账号登录；`http://localhost:8088/health`
检查 JVM API。云防火墙仅允许自己的管理来源访问 SSH，不需要公开应用端口。需要团队访问时可配置
Cloudflare Tunnel 与 Access 或其他认证网关；Tunnel 要有可用出站路由，私网 NAT、域名或额外服务可能收费。
`AIRFLOW_WEB_BIND` 可显式修改，但应先完成相应访问控制。Swarm 的发布端口行为与此不同，见后文。

JVM API 容器以非 root 用户（uid 10001）运行，jar 对它只读。它把 `datacraft-data` 卷只读挂载在
`/opt/datacraft/data`，并把 `DATACRAFT_DATA_ROOT` 设为该目录，`csv-profile` 与 `file-checksum` 只能读取
该目录以下的文件，例如
`curl -X POST 'http://localhost:8088/jobs/file-checksum/runs?input=/opt/datacraft/data/<文件>'`
（非 ASCII 文件名须先做百分号编码，API 以 400 拒绝未编码的非 ASCII 字节）。
JVM 镜像本身就设置了这个变量：不挂载数据卷直接 `docker run` 时，这两个作业因目录不存在而失败。
在镜像外运行 `serve-api` 时，若 `--host` 不是回环地址且没有非空的 `DATACRAFT_DATA_ROOT`，进程以退出码 2
拒绝启动。JVM 镜像不含 Spark 运行时（Spark 是 provided 依赖），在其中运行的 Spark 作业返回 FAILED
结果，经 HTTP API 调用时状态码为 500。

先解除暂停并触发 `datacraft_engine_jobs`，确认 noop、echo 两个任务成功；再运行：

- `datacraft_spark_etl`：输入与独立输出都必须是 `/opt/datacraft/data` 下的绝对路径，不含 `.`、`..`
  段或 `{}[]*?\` 等字符（完整规则见 [Airflow 说明](../../orchestration/airflow/README.md)），否则触发时
  即被拒绝；确认转换与行数校验均成功。
- `datacraft_sftp_ingest`：在 Airflow 的 `datacraft_sftp` 连接 extra 中设置 `host_key`（或设
  `"no_host_key_check": false` 并为 Airflow 用户提供 known_hosts），否则下载任务不重试、直接失败；
  再验证下载内容、转换、行数。

数据持久化位置为 `/opt/datacraft/data`，由命名卷保存；主机绝对路径不会自动出现在容器内。
先将验收样本放入该数据卷，或把只读输入挂载到该目录以下：DAG 只接受 `/opt/datacraft/data` 以下的路径。
不要用 `compose exec` 的临时容器目录作为长期存储。
两个栈文件都在 `environment:` 中固定 `DATACRAFT_DATA_ROOT=/opt/datacraft/data`，它优先于 `env_file`，
`.env` 中的同名值不生效；要改数据根目录，需同时修改栈文件中的变量和卷挂载。
重复执行 overwrite 场景不应产生重复数据；不可让输入和输出目录相同或相互包含。
完整参数规则见数据处理文档。

信任边界：每个 Airflow 容器都通过 `env_file` 收到完整 `.env`（数据库密码、管理员密码、Fernet、API 与
JWT 密钥），DAG 任务进程也继承这些环境变量。LocalExecutor 下 DAG 代码、CLI jar 和 Spark 依赖以同一用户
在 Airflow 容器内运行，等同管理员权限，只能部署可信代码。有权触发 DAG 的用户可以替换或删除数据根目录
中的数据。

`compose-up` 的服务检查不代替 DAG 执行验收。CI 另运行真实 Compose 下的调度器、Execution API 和
LocalExecutor 流程，并验证 PostgreSQL 备份恢复；正式云主机还需用实际网络、SFTP、数据量复测。

## 4. 资源限制与持续运行

Compose 默认 `AIRFLOW_PARALLELISM=1`、`AIRFLOW_PARSING_PROCESSES=1`、API worker 为 1，
`DATACRAFT_SPARK_MASTER=local[2]`。减少小主机上多个 DAG 同时启动 Spark JVM 的内存竞争。
前三项只在 Compose 生效，Swarm 的并发见第 6 节。
DAG 自身还有 `max_active_runs=1`；它只是每个 DAG 的限制，不能代替全局并发限制。
如果直接调用 JVM API 或手动启动 Spark，它们不受 Airflow 并发控制，应避免和大任务同时运行。

记录代表性数据的耗时、峰值内存、磁盘余量、CPU 积分（如适用）和出站量，再决定提高并发或升配。
本轮不承诺固定空闲/峰值内存，也不承诺 4 GB 一定够用。Airflow 调度器关闭时不会按时调度任务；
按需停机方案适合手动/批次运行，不适合要求全天准点执行的流程。

Compose 与 Swarm 的每个服务都使用 json-file 日志，每个容器最多 3 份、每份 10 MB。
**这不清理 Airflow 任务日志、数据库历史或数据输出。**
为这些持久数据单独制定保留期和容量告警：先备份、确认过期范围，再用对应工具清理。
不要把失败任务记录全部删掉来表示系统健康，也不要无限累积备份版本。R2 是备份候选；当前 Spark
没有配置 S3A，不能直接把本地路径换成 `s3://` 就视为已经支持。

## 5. 备份、恢复和停机

需要同时保护：PostgreSQL 元数据、Fernet/JWT 等配置、所用镜像版本（DAG 与 jar 已打包在镜像中）、
`datacraft-data` 中的业务文件，以及需要保留的任务日志。存储快照与数据库一致性备份不是同一回事。

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

仓库保留 Swarm/Celery 模板，但小规模优先使用 Compose。默认本地卷要求把所有服务（数据库、Redis、
Airflow 各组件、worker 与 JVM API）固定在 `DATACRAFT_DATA_NODE`，并非多机高可用。要跨节点扩展，
必须先解决共享输入输出、任务日志、数据库高可用、密钥管理和网络认证；不能仅增加 worker 数就宣称完成扩展。
该模板只经过 `docker stack config` 与静态测试，尚未在真实多机 Swarm 上运行。

在可信 `.env` 中填写实际 registry、精确版本标签和数据节点主机名；Swarm 脚本使用 shell 加载此受信配置，
不要将外部不可信文本当作 `.env`。所有节点必须能拉取相同架构的镜像。发布操作由运维者在 manager 上执行：

```bash
bash deploy/scripts/build-images.sh
registry=registry.example.com:5000
tag=YOUR_VERIFIED_VERSION   # 精确版本，不能是 latest
docker login "$registry"    # 需要认证的 registry
docker tag datacraft/airflow:latest "$registry/datacraft-airflow:$tag"
docker tag datacraft/jvm:latest "$registry/datacraft-jvm:$tag"
docker push "$registry/datacraft-airflow:$tag"
docker push "$registry/datacraft-jvm:$tag"
# 将同样的 DATACRAFT_REGISTRY、DATACRAFT_TAG 与 DATACRAFT_DATA_NODE 填入 deploy/compose/.env，然后：
bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
```

`swarm-deploy.sh` 在 `DATACRAFT_TAG` 为空或为 `latest` 时于部署前退出；它用 Compose 文件的生效配置检查
同一份 `.env`，并以 `--with-registry-auth` 部署，把 manager 的 registry 登录转发给各节点。
数据库迁移和管理员账号由一次性服务 `datacraft_airflow-init` 完成：失败时间隔 10 秒重试，固定在数据节点；
它完成前其他 Airflow 服务会退出并由 Swarm 重启。更换镜像的重新部署会再次运行它，迁移可重复执行。
`airflow-init.sh` 同样在 manager 上执行，只负责等待当前服务配置产生的最新任务变为 Complete：
默认最长 600 秒（`DATACRAFT_INIT_TIMEOUT`），每 5 秒检查一次（`DATACRAFT_INIT_POLL_SECONDS`）；
超时时提示用 `docker service ps --no-trunc datacraft_airflow-init` 与
`docker service logs datacraft_airflow-init` 排查。

worker 默认 `AIRFLOW_WORKER_REPLICAS=1`、`AIRFLOW_WORKER_CONCURRENCY=1`。所有副本都在数据节点上，
该节点同时最多运行“副本数 × 并发数”个任务，每个都可能是一个 Spark JVM；提高任一值前先测量该节点内存。
`AIRFLOW_PARALLELISM`、`AIRFLOW_PARSING_PROCESSES` 等 Compose 设置不限制 Swarm。worker 设置
`DUMB_INIT_SETSID=0`，停止信号只发给 Celery 主进程；重新部署或更换镜像时，旧 worker 的停止宽限期为
`DATACRAFT_TASK_TIMEOUT_MINUTES`（默认 60）分钟加 30 秒，让运行中的任务完成，只有 1 个副本时排队任务在此
期间等待。Airflow API 服务器、调度器、DAG 处理器和触发器使用与 Compose 相同的健康检查，Celery worker
没有健康检查。

Swarm 模板的 Airflow UI 端口经路由网格在各节点发布，不是 Compose 的回环绑定，必须在云防火墙/认证网关处
限制访问。JVM API 不发布端口，只在 overlay 网络内，同样只读挂载数据卷并设置 `DATACRAFT_DATA_ROOT`。
Redis 与数据库不对公网发布。节点间的 2377/tcp、7946/tcp+udp 与 4789/udp 只允许 swarm 节点互访：
overlay 网络 `datacraft-net` 可被附加且未加密，而 Redis 与 JVM API 都没有认证。

worker 同样通过 `env_file` 收到完整 `.env`，持有 JWT 签名密钥、带密码的数据库与 Celery 结果后端地址、
Fernet 密钥和管理员密码。让 worker 运行不可信代码或部署到不完全可信的主机之前，必须先把这些密钥与 worker
隔离；生产密钥优先迁移到 secret 管理方案。

使用真实 Spark 集群时，把 `DATACRAFT_SPARK_MASTER` 设为集群地址（或在 DAG 代码中给 `spark_task` 传
`master=`），driver 以 client 模式运行在执行任务的 Airflow 容器内。目前只测试过 local 模式。`spark://`
集群还需要：每台主机在相同的 `DATACRAFT_DATA_ROOT` 路径挂载共享存储（未配置 S3A）；executor 能回连
driver（经 `extra_conf` 或 `spark-defaults.conf` 设置 `spark.driver.host`、`spark.driver.port`、
`spark.blockManager.port` 并开放这些端口）；executor 上有 Java 25 与 Spark 4.2.0。详见
[Airflow 说明](../../orchestration/airflow/README.md)。

## 7. 验证与已知范围

```bash
node --test deploy/tests/cloud-costs.test.cjs
python3 -B -m unittest discover -s deploy/tests -v
```

Linux CI（ubuntu-24.04）是权威门禁：完整 JVM verify 与各模块测试数下限、jar 冒烟与退出码、真实
Airflow 3.3.2 流程、ShellCheck、Compose/Swarm 解析与缺失密钥拒绝、镜像构建、实际 Compose 任务与备份恢复。
镜像检查还包括 Airflow ≥ 3.3.2、FAB ≥ 3.9.0，以及 API 以非 root 运行、数据挂载只读。`compose_smoke.sh`
仅限隔离 CI，会销毁其专用测试项目的数据卷，拒绝覆盖已有 `.env`。

在 Windows 上本地运行 JVM 测试的前提：NIO Selector 需要在临时目录创建 AF_UNIX 套接字，部分 Windows 用户
TEMP 目录做不到，这是主机状态而非 JDK 缺陷；构建为测试设置
`-Djdk.net.unixdomain.tmpdir=${project.build.directory}`（根 POM 属性 `test.nio.jvm.args`），仍打不开
Selector 的主机上 Spark 套件取消，而 CI 中 Spark 套件出现取消即失败。符号链接测试需要开发者模式或管理员
权限，否则中止或取消；写本地文件的 Spark 测试需要 winutils（`HADOOP_HOME` 或 `hadoop.home.dir`），否则
取消；两个 SFTP 测试在 Windows 上禁用。全部 262 个测试中，Linux 执行 256 个（6 个 junction 测试只在
Windows 运行）；未开启开发者模式、未配置 winutils 的 Windows 主机执行 248 个。

当前尚未执行真实云部署、ARM 主机测试或多机 Swarm 故障切换；这些边界与“部署资料已准备”分开记录，
不作为已完成上线报告。
