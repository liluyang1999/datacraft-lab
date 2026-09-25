# datacraft-lab

[![CI](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml)

`datacraft-lab` is a personal data-processing factory built as a Maven multi-module JVM project. It
combines Java and Scala, uses Spark as the heavy processing engine and plain JVM jobs
(`csv-profile`, `file-checksum`) for small files, exposes one execution engine through a CLI and an
HTTP API, and uses **Apache Airflow 3.x** as the orchestrator. It ships with a Docker Compose
deployment (single host) and a Docker Swarm template (CeleryExecutor); the Swarm stack pins every
service to one data node (`DATACRAFT_DATA_NODE`) because its volumes are node-local.

## Module layout

| Module | Package | Responsibility |
| --- | --- | --- |
| `datacraft-common` | `…common` | Shared constants, lifecycle model, base exception. |
| `datacraft-config` | `…config` | UTF-8 `java.util.Properties` loading (BOM optional), typed access, prefix slicing. |
| `datacraft-io` | `…io` | `StorageService` (root-bound local FS port) + `LocalFiles`, interface-backed SFTP with literal remote paths (`RemoteFileTransfer`/`SftpClient`), and a dependency-free CSV reader/writer (`CsvFiles`, RFC 4180 quoting, LF record terminators). |
| `datacraft-engine` | `…engine` | The universal `DataJob` contract, registry, execution engine, result/metrics model, shared `ParameterKeys`, built-in `echo`/`noop`. |
| `datacraft-jobs` | `…jobs` | Plain-JVM data jobs on the same `DataJob` contract, built on `datacraft-io` (`csv-profile`, `file-checksum`). |
| `datacraft-spark` | `…spark` | Spark session lifecycle, reusable `DataFrames` IO, path guards, and `AbstractSparkDataJob` bridging Spark jobs onto `DataJob` (`spark-version`, `csv-to-parquet`, `row-count`). |
| `datacraft-api` | `…api` | Thin JDK HTTP server exposing the engine; Jackson serialization. |
| `datacraft-cli` | `…cli` | Process entrypoint; a **generic engine dispatcher** used by shells and Airflow. |

Airflow assets live in [`orchestration/airflow`](orchestration/airflow); deployment assets in
[`deploy/`](deploy). Architecture details: [`docs/architecture.md`](docs/architecture.md). Data
contracts: [`docs/data-processing.md`](docs/data-processing.md). Deployment tutorial (Chinese):
[`docs/deployment/cloud-and-deployment.md`](docs/deployment/cloud-and-deployment.md).

- [`PROJECT-REVIEW.html`](PROJECT-REVIEW.html): the consolidated, current evaluation report
  (Chinese, standalone HTML).
- [`docs/engineering-report/2026-09-25-review.md`](docs/engineering-report/2026-09-25-review.md):
  the latest dated review record (Chinese); earlier records stay in the same folder.
- [`docs/engineering-report/index.html`](docs/engineering-report/index.html): engineering overview
  (Chinese).
- [`CLOUD-DEPLOYMENT-ANALYSIS.html`](CLOUD-DEPLOYMENT-ANALYSIS.html): Tokyo/Singapore cost
  comparison with an interactive calculator (Chinese).

## Prerequisites

- **JDK 25+** to build and run (the build targets Java 25 bytecode). Use **25.0.3 or newer**: Spark
  4.2.0 deprecates Java 25 releases older than 25.0.3, and the Enforcer logs a warning on them.
- **Maven 3.9+**, or the bundled wrapper (`./mvnw`, `mvnw.cmd`), which downloads Maven 3.9.16 and
  verifies its SHA-256. On Linux/macOS/WSL the wrapper needs `unzip`.
- **Docker Engine + Compose v2** — only for the container deployment (not required to build/test).
- **Python 3.12 on Linux/WSL** — for the Airflow contract tests, the full pipeline smoke test and
  the deployment script tests.

On Windows, point `JAVA_HOME` at a JDK 25.0.3+ installation before invoking Maven if it is not
already configured:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25.0.3'
```

## Build & test

```bash
./mvnw test             # unit tests
./mvnw verify           # the full gate: format check, Checkstyle, Enforcer, tests, package
./mvnw spotless:apply   # auto-format Java + Scala
```

On Windows use `.\mvnw.cmd` (it runs its logic in `powershell.exe`). The Maven `make` targets
call `./mvnw`; `make MVN=mvn ...` uses a Maven on `PATH` instead.

- **Linux CI is the authoritative gate**: GitHub-hosted `ubuntu-24.04` runs `./mvnw -B -ntp verify`
  and then checks that every module executed at least its floor of tests
  (`.github/scripts/check_test_counts.py`; skipped and canceled tests do not count).
- Tests run with `-Djdk.net.unixdomain.tmpdir=<module target directory>` (root `pom.xml` property
  `test.nio.jvm.args`), so NIO selectors do not depend on the profile TEMP directory. Some Windows
  TEMP trees cannot host the AF_UNIX wakeup socket, which made the HTTP and Spark tests fail.
- On Windows, the symbolic-link tests are skipped (aborted or canceled) without Developer Mode or
  elevation, the Parquet-writing Spark tests cancel without winutils (`HADOOP_HOME` or
  `hadoop.home.dir`), and two SFTP tests are disabled; the directory-junction tests run only on
  Windows. The test-count floors are Linux counts and CI runs `check_test_counts.py` on Linux
  only; a Windows build that skips those tests falls below some of them.
- The first `./mvnw` run verifies the Maven download against `distributionSha256Sum` in
  `.mvn/wrapper/maven-wrapper.properties`. Without `unzip`, the Unix script fetches the `.tar.gz`,
  whose checksum does not match the pinned zip, and stops with "Failed to validate Maven
  distribution SHA-256".
- Surefire fails a module whose JUnit scan runs no tests (`surefire.failIfNoTests`; the ScalaTest
  modules `datacraft-cli` and `datacraft-spark` set it to `false`). An ad-hoc multi-module
  `-Dtest=...` run needs `-Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.failIfNoTests=false`.

Build the CLI jar (Spark stays **provided**, so the jar is ~9 MB):

```bash
./mvnw -pl datacraft-cli -am package -DskipTests
```

## Running the CLI

Two control commands (`list-jobs`, `serve-api`) are handled directly; **every other command name is
dispatched through the engine as a job**, so the CLI, the API, and Airflow share one execution path.
The catalog has seven jobs: `echo` and `noop` (built-in), `csv-profile` and `file-checksum` (plain
JVM), and `spark-version`, `csv-to-parquet` and `row-count` (Spark).

```bash
JAR=datacraft-cli/target/datacraft-cli.jar

java -jar $JAR --command list-jobs                        # list jobs + descriptions
java -jar $JAR --command echo --param message=hello       # built-in echo job
java -jar $JAR --command noop --lifecycle prod            # reachability check
java -jar $JAR --command csv-profile --param input=data/in.csv --param expectedRows=2 --json
java -jar $JAR --command file-checksum --param input=data/in.csv
java -jar $JAR --command serve-api --port 8080            # binds 127.0.0.1 by default
```

- **Exit codes**: `0` success; `1` the job failed (including a job rejecting its parameters), or its
  `--result-file` could not be written (the result is still printed first); `2` invalid usage, i.e.
  a parse error (including job options given to a control command), an unknown command or job, an
  unreadable or malformed `--config` file (one stderr line, `Invalid --config <path>: <cause>`; the
  job does not run), or a `serve-api` host it refuses (see below).
- **Parameters**: `--param key=value` is repeatable and empty values are allowed. Parameter names
  are case-sensitive: the Spark and plain-JVM jobs reject a key that differs from one of their
  parameters only by case (for example `expectedrows`); other unknown keys are ignored.
- **`--config path.properties`** merges a `java.util.Properties` file, read as UTF-8 with one
  leading BOM ignored, into the job parameters. Keys are trimmed; values are passed verbatim, like
  `--param` and HTTP values. Backslash is an escape character, so write Windows paths as
  `D:/out/x.parquet` or `D:\\out\\x.parquet`. `--param` values override file entries.
- **`--json`** prints the result and metrics as exactly one UTF-8 JSON line ending in LF on every
  platform; `--result-file path` writes the same bytes to a caller-owned file for orchestration.
- **Spark master**: `--param spark.master` > `--master` > `spark.master` in `--config` >
  `spark-submit --master` > `local[*]`. Shuffle partitions: the `spark.shufflePartitions` job
  parameter (`--param` or `--config`) > `spark-submit --conf spark.sql.shuffle.partitions` > 8.
- **`--lifecycle dev|prod`** (default `dev`) is a label passed to jobs; no job reads it yet.
- **Control commands** reject job options: `--config`, `--master`, `--lifecycle`, `--param`,
  `--json` or `--result-file` on `list-jobs` or `serve-api` exits 2. `serve-api` uses only `--host`
  and `--port`. Job names cannot contain `/` or equal a control command.
- **`serve-api`** binds `127.0.0.1:8080` by default. It starts on a non-loopback `--host` (such as
  `0.0.0.0`) only when `DATACRAFT_DATA_ROOT` is set and not blank; otherwise it exits 2, because
  the API is unauthenticated and its file jobs could read any path. The `datacraft/jvm` image sets
  `DATACRAFT_DATA_ROOT=/opt/datacraft/data`.

With `DATACRAFT_DATA_ROOT` set, the `input` of `csv-profile` and `file-checksum` and the paths of
`csv-to-parquet` must lie strictly inside that directory; unset means unconfined, which suits local
CLI use. Job parameters, messages and metrics:
[`docs/data-processing.md`](docs/data-processing.md). Latest review evidence:
[`2026-09-25 review`](docs/engineering-report/2026-09-25-review.md).

### Spark jobs run via `spark-submit`

Because Spark is a **provided** dependency, Spark jobs are launched with `spark-submit` (which puts
Spark on the classpath), not plain `java -jar`. Under plain `java -jar` a Spark job that passes its
parameter checks returns FAILED (exit 1) with "Spark runtime is not on the classpath (missing
<class>); launch Spark jobs with spark-submit". The job inherits spark-submit's `--master` unless
`--master` or `spark.master` is given to the CLI:

```bash
spark-submit --master 'local[*]' --class com.example.datacraft.cli.Runner \
  datacraft-cli/target/datacraft-cli.jar \
  --command csv-to-parquet \
  --param input=data/in.csv --param output=data/out.parquet
```

`csv-to-parquet` input must be a literal file or directory path; glob patterns are rejected.

(For laptop experiments without `spark-submit`, the `bundled-spark` Maven profile produces a
self-contained — but large — jar: `./mvnw -Pbundled-spark -pl datacraft-cli -am package`.)

## HTTP API

```
GET|HEAD /health
GET|HEAD /jobs
POST     /jobs/{jobName}/runs?lifecycle=dev&key=value
```

A run answers with the result JSON (`jobName`, `status`, `message`, `metrics`): 200 when the job
SUCCEEDED and 500 when it FAILED, including parameter rejections. Errors use
`{"error":"<code>"}`: 400 `invalid_request` for a malformed job path, an unknown `lifecycle` or a
query that is not percent-encoded UTF-8; 403 `cross_origin_forbidden` for a run request that
carries an `Origin` header (browsers send one; curl, `java.net.http` and Airflow do not); 404
`unknown_job` or `not_found`; 405 `method_not_allowed` with an `Allow` header; 500 `internal_error`
when a handler fails unexpectedly. Request targets the JDK server cannot parse get its plain HTML
400 before routing. On shutdown the server stops accepting connections and waits up to 8 s for
in-flight requests; longer runs are cut off. The API has no authentication, so keep it on loopback
or behind an authenticated gateway. Full contract:
[HTTP status contract](docs/data-processing.md#http-status-contract).

## Orchestration & deployment

- Airflow DAGs and helpers: [`orchestration/airflow`](orchestration/airflow/README.md).
- Single host (Compose) and the Swarm template, with scripts and a full tutorial:
  [`docs/deployment/cloud-and-deployment.md`](docs/deployment/cloud-and-deployment.md). The Swarm
  stack pins every service to `DATACRAFT_DATA_NODE`; spreading services across nodes needs shared
  storage first.

```bash
python3 deploy/scripts/deployment_env.py init  # create private secrets without overwriting a file
bash deploy/scripts/build-images.sh   # build images (compiles the jar in a container)
bash deploy/scripts/compose-up.sh     # start the single-host stack
```

Compose publishes the engine API on `127.0.0.1` only and the Airflow UI on `AIRFLOW_WEB_BIND`
(default `127.0.0.1`); reach them through SSH forwarding or an authenticated tunnel.

Tokyo/Singapore pricing, on-demand versus always-on billing, and an interactive calculator:
[`cloud cost report`](CLOUD-DEPLOYMENT-ANALYSIS.html). Prices and assumptions were checked on
2026-09-19; the report distinguishes complete scenario totals from compute-only prices.

## Configuration policy

Build/dependency/formatter/checker/compiler/test plugin versions are centralized in the parent
`pom.xml`. Tool configuration lives in root files / `config/`: `.scalafmt.conf`, `.editorconfig`,
`.gitattributes`, `config/checkstyle/checkstyle.xml`.
