# datacraft-lab

[![CI](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml)

`datacraft-lab` is a personal data-processing factory built as a Maven multi-module JVM project. It
combines Java and Scala, uses Spark as the heavy processing engine and plain JVM code for
small-scenario work, exposes one execution engine through a CLI and an HTTP API, and uses **Apache
Airflow 3.x** as the orchestrator. It ships with Docker Compose (single host) and Docker Swarm
(multi-host) deployments.

## Module layout

| Module | Package | Responsibility |
| --- | --- | --- |
| `datacraft-common` | `…common` | Shared constants, lifecycle model, base exception. |
| `datacraft-config` | `…config` | UTF-8 properties loading, typed access, prefix slicing. |
| `datacraft-io` | `…io` | `StorageService` (local FS port) + `LocalFiles`, interface-backed SFTP (`RemoteFileTransfer`/`SftpClient`), and dependency-free `CsvFiles`. |
| `datacraft-engine` | `…engine` | The universal `DataJob` contract, registry, execution engine, result/metrics model, shared `ParameterKeys`. |
| `datacraft-spark` | `…spark` | Spark session lifecycle, reusable `DataFrames` IO, and `AbstractSparkDataJob` bridging Spark jobs onto `DataJob` (`spark-version`, `csv-to-parquet`, `row-count`). |
| `datacraft-api` | `…api` | Thin JDK HTTP server exposing the engine; Jackson serialization. |
| `datacraft-cli` | `…cli` | Process entrypoint; a **generic engine dispatcher** used by shells and Airflow. |

Airflow assets live in [`orchestration/airflow`](orchestration/airflow); deployment assets in
[`deploy/`](deploy). Architecture details: [`docs/architecture.md`](docs/architecture.md).
Deployment + the Cloudflare-vs-AWS decision: [`docs/deployment/cloud-and-deployment.md`](docs/deployment/cloud-and-deployment.md).
A consolidated review report (Chinese, standalone HTML) is at [`PROJECT-REVIEW.html`](PROJECT-REVIEW.html).
The cloud vendor evaluation, cost estimates and decision tree are in [`CLOUD-DEPLOYMENT-ANALYSIS.html`](CLOUD-DEPLOYMENT-ANALYSIS.html).

## Prerequisites

- **JDK 25+** to build and run (the build targets Java 25 bytecode). This workspace has JDK 25 at
  `D:\Java`. Use **25.0.3 or newer**: Spark 4.2.0 deprecates Java 25 releases older than 25.0.3.
- **Maven 3.9+** (or the bundled `mvnw`/`mvnw.cmd`).
- **Docker Engine + Compose v2** — only for the container deployment (not required to build/test).
- **Python 3.10+** — only to syntax-check the Airflow DAGs locally.

On Windows, set `JAVA_HOME` before invoking Maven if it is not already configured:

```powershell
$env:JAVA_HOME = 'D:\Java'
```

## Build & test

```powershell
.\mvnw.cmd test           # unit tests
.\mvnw.cmd verify         # format check + checkstyle + tests + package (the full gate)
.\mvnw.cmd spotless:apply # auto-format Java + Scala
```

Build the CLI jar (Spark stays **provided**, so the jar is ~9 MB):

```powershell
.\mvnw.cmd -pl datacraft-cli -am package -DskipTests
```

## Running the CLI

Two control commands (`list-jobs`, `serve-api`) are handled directly; **every other command name is
dispatched through the engine as a job**, so the CLI, the API, and Airflow share one execution path.

```powershell
$jar = 'datacraft-cli\target\datacraft-cli.jar'

java -jar $jar --command list-jobs                       # list jobs + descriptions
java -jar $jar --command echo --param message=hello      # built-in echo job
java -jar $jar --command noop --lifecycle prod           # reachability check
java -jar $jar --command serve-api --host 0.0.0.0 --port 8080
```

`--param key=value` is repeatable; `--config path.properties` merges a properties file into the job
parameters (CLI params win). Exit codes: `0` success, `1` job failed, `2` unknown command / bad args.

### Spark jobs run via `spark-submit`

Because Spark is a **provided** dependency, Spark jobs are launched with `spark-submit` (which puts
Spark on the classpath), not plain `java -jar`:

```bash
spark-submit --master 'local[*]' --class com.example.datacraft.cli.Runner \
  datacraft-cli/target/datacraft-cli.jar \
  --command csv-to-parquet --master 'local[*]' \
  --param input=data/in.csv --param output=data/out.parquet
```

(For laptop experiments without `spark-submit`, the `bundled-spark` Maven profile produces a
self-contained — but large — jar: `mvnw -Pbundled-spark -pl datacraft-cli -am package`.)

## HTTP API

```
GET  /health
GET  /jobs
POST /jobs/{jobName}/runs?lifecycle=dev&key=value
```

## Orchestration & deployment

- Airflow DAGs and helpers: [`orchestration/airflow`](orchestration/airflow/README.md).
- Single host (Compose) / multi-host (Swarm), with scripts and a full tutorial including the cloud
  recommendation and cost analysis: [`docs/deployment/cloud-and-deployment.md`](docs/deployment/cloud-and-deployment.md).

```bash
bash deploy/scripts/build-images.sh   # build images (compiles the jar in a container)
bash deploy/scripts/compose-up.sh     # start the single-host stack
```

## Configuration policy

Build/dependency/formatter/checker/compiler/test plugin versions are centralized in the parent
`pom.xml`. Tool configuration lives in root files / `config/`: `.scalafmt.conf`, `.editorconfig`,
`.gitattributes`, `config/checkstyle/checkstyle.xml`.
