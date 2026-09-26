# Architecture

## Goal

`datacraft-lab` is a personal data-processing factory: a strict Maven multi-module layout with
Java/Scala interop, a single reusable execution engine, local-or-cluster Spark, plain-JVM jobs for
small files, reusable configuration and IO ports, a thin online HTTP API, and Airflow 3.x
orchestration with container deployments.

## Repository layout

The repository is organised by responsibility. Production code lives in `modules/`, every test in
`tests/`, user documentation in `docs/` and design documentation in `design/`; the root keeps only
build entry points and tool configuration.

```text
datacraft-lab/
├── pom.xml  mvnw  mvnw.cmd  .mvn/     build entry points (.mvn: wrapper settings, jvm.config)
├── Makefile                           convenience targets over the wrapper and deploy/ scripts
├── .editorconfig  .gitattributes  .gitignore  .dockerignore  .scalafmt.conf
├── pyrightconfig.json                 Python type checking
├── config/checkstyle/checkstyle.xml   Checkstyle rules
├── .github/workflows/ci.yml           CI
├── modules/                           JVM source modules, grouped by responsibility
│   ├── core/         datacraft-common, datacraft-config, datacraft-engine
│   ├── io/           datacraft-io
│   ├── processing/   datacraft-jobs (plain JVM), datacraft-spark (Spark)
│   └── interfaces/   datacraft-api (HTTP), datacraft-cli (process entry point)
├── orchestration/airflow/             dags/ (with .airflowignore), requirements.txt
├── deploy/                            docker/, compose/, swarm/, scripts/
├── design/                            architecture and design documents (English)
├── docs/                              README, CHANGELOG, guides/, reports/
└── tests/                             every test
    ├── jvm/<artifactId>/{java,scala}  JVM tests of each module, run by that module's build
    ├── jvm/resources/                 shared logging.properties and log4j2-test.properties
    ├── orchestration/                 Airflow DAG contract tests (need Airflow)
    ├── smoke/                         Airflow pipeline, container and Compose smoke tests
    ├── deploy/                        deployment script and template tests (standard library only)
    ├── ci/                            check_test_counts.py and its tests, JschAlgorithmsProbe.java
    └── docs/                          documentation consistency and cost calculator tests
```

- The grouping directories are not part of any coordinate: artifactIds and Java/Scala packages
  are the same as before the grouping, so select a module by artifactId, for example
  `./mvnw -pl :datacraft-cli -am package`.
- A module directory holds only `pom.xml` and `src/main/{java,scala}`. Its tests live in
  `tests/jvm/<artifactId>/` and still run in that module's build; see
  [Source and test layout](build-and-quality.md#source-and-test-layout).
- The shaded CLI jar is `modules/interfaces/datacraft-cli/target/datacraft-cli.jar`.
- `orchestration/airflow` orchestrates the built CLI jar and never re-implements JVM logic.
  `deploy/` holds the Dockerfiles, the Compose and Swarm stacks and the scripts around them.

## Module responsibilities

Each module owns one responsibility and may depend only on the project modules listed for it. The
rule is enforced, not only documented: every module POM except `datacraft-cli` has an Enforcer
execution `enforce-module-boundaries` whose `bannedDependencies` rule excludes the project's own
group `com.example.datacraft` and, outside `datacraft-spark`, the group `org.apache.spark`, then
re-includes the allowed modules. It checks direct and transitive dependencies and fails `validate`
with a message that names the allow-list and points to this section.

| Module | Directory | Owns (key types) | May depend on | Enforced by |
| --- | --- | --- | --- | --- |
| `datacraft-common` | `modules/core/datacraft-common` | `Lifecycle`, `DataCraftException`, `AppInfo` | no project module | Enforcer rule; Spark banned |
| `datacraft-config` | `modules/core/datacraft-config` | `DataCraftConfig` | `common` | Enforcer rule; Spark banned |
| `datacraft-io` | `modules/io/datacraft-io` | `StorageService`, `LocalStorageService`, `LocalFiles`, `CsvFiles`, `RemoteFileTransfer`, `SftpClient`, `SftpConfig` | `common` | Enforcer rule; Spark banned |
| `datacraft-engine` | `modules/core/datacraft-engine` | `DataJob`, `JobCatalog`, `JobRegistry`, `JobExecutionEngine`, `JobExecutionRequest`, `JobExecutionResult`, `JobStatus`, `ParameterKeys`, `BuiltInJobs` | `common` | Enforcer rule; Spark banned |
| `datacraft-jobs` | `modules/processing/datacraft-jobs` | `JvmJobs`, `CsvProfileJob`, `FileChecksumJob`, `InputFiles` | `common`, `engine`, `io` | Enforcer rule; Spark banned |
| `datacraft-spark` | `modules/processing/datacraft-spark` | `AbstractSparkDataJob`, `SparkJobs`, `SparkRuntimeConfig`, `SparkSessions`, `DataFrames`, path guards | `common`, `engine`; Spark (`provided`) | Enforcer rule |
| `datacraft-api` | `modules/interfaces/datacraft-api` | `EngineHttpServer`, `EngineHttpServerConfig`, `EngineJson`, `HttpJsonResponse` | `common`, `engine` | Enforcer rule; Spark banned |
| `datacraft-cli` | `modules/interfaces/datacraft-cli` | `Runner`, `CliParser`, `CommandLineArgs`; the shaded jar | every module | none: the composition root |

What each module is for, and its third-party dependencies:

- **`datacraft-common`**: cross-module primitives. No Spark, SFTP, Airflow or CLI knowledge.
- **`datacraft-config`**: typed UTF-8 `.properties` loading (one leading BOM ignored) and prefix
  slicing (`withPrefix`).
- **`datacraft-io`**: data-movement ports and helpers; third-party: JSch (`com.github.mwiede`).
  - `StorageService` (root-bound local file-system port) with `LocalStorageService`, and
    `LocalFiles` (unrestricted NIO helper: read, write, copy, move, delete, size, exists,
    checksum, list).
  - `RemoteFileTransfer` (port, with an atomic default `download(String, Path)`) with `SftpClient`
    (SFTP with literal remote paths) and `SftpConfig.fromProperties` for config-driven wiring. No
    shipped job uses `SftpClient` yet; the Airflow ingest DAG downloads through Airflow's SFTP
    provider.
  - `CsvFiles`: dependency-free CSV reader/writer for small, non-Spark work (RFC 4180 quoting).
- **`datacraft-engine`**: the central, reusable execution layer: the job contract, the catalog,
  the engine, request/result and metrics, `ParameterKeys` and the built-in `echo` and `noop`.
- **`datacraft-jobs`**: plain-JVM data jobs (`csv-profile`, `file-checksum`) registered through
  `JvmJobs`; `InputFiles` confines their input under `DATACRAFT_DATA_ROOT`.
- **`datacraft-spark`**: Spark abstractions and Spark-backed jobs (`spark-version`,
  `csv-to-parquet`, `row-count`); third-party: `scala-library`, and `spark-sql` and `spark-hive` at
  `${spark.scope}` (`provided` unless the `bundled-spark` profile is active).
- **`datacraft-api`**: online HTTP access to the engine (JDK HTTP server); third-party:
  `jackson-databind`. It owns no business logic.
- **`datacraft-cli`**: the process entry point and generic dispatcher, the single place shells and
  Airflow call; third-party: `scala-library`, `scopt`. It wires `BuiltInJobs`, `SparkJobs` and
  `JvmJobs` into one catalog and is packaged as the shaded jar. It has no boundary rule because
  composing every module is its responsibility.

Outside the reactor, `orchestration/airflow` depends only on the built jar, which its tasks run
with `java -jar` or `spark-submit`, and `deploy/` packages that jar and the DAGs into images.

## The unified job model

Everything flows through one contract, `engine.DataJob` (`name`, `description`,
`run(JobExecutionRequest) -> JobExecutionResult`):

```text
CLI / HTTP / Airflow
  -> datacraft-cli (generic dispatcher; serve-api starts datacraft-api's HTTP server)
  -> datacraft-engine: JobCatalog (JobRegistry) + JobExecutionEngine (runs a DataJob and turns
     every outcome into a JobExecutionResult)
  -> a DataJob implementation:
       - built-in (echo, noop)                              [engine]
       - plain-JVM small-file jobs via JvmJobs              [jobs]
         (csv-profile, file-checksum)
       - Spark-backed via AbstractSparkDataJob              [spark]
         (spark-version, csv-to-parquet, row-count)
```

The CLI builds one catalog, `JvmJobs.register(SparkJobs.register(BuiltInJobs.registry()))`, and
serves it through `serve-api`; Airflow tasks run the same CLI jar, so all three callers see the
same seven jobs.

- **`JobExecutionEngine`** is the failure boundary: an unknown job name, a `null` result and every
  `Throwable` a job throws become a `FAILED` result, and each such `Throwable` is logged once at
  `ERROR` ("Job <name> failed") through `System.Logger`. Only `VirtualMachineError`s
  (`OutOfMemoryError`, `StackOverflowError`, `InternalError`) propagate. `JobRegistry` rejects job
  names containing `/`, because a name is one path segment of `POST /jobs/{name}/runs`, and the
  CLI refuses to start when a job is named after a control command (`list-jobs`, `serve-api`).
- **`AbstractSparkDataJob`** (Scala) adapts a Spark computation to `DataJob`: it rejects parameter
  keys that differ from a known name only by case, derives a `SparkRuntimeConfig` and validates the
  job's parameters before a session starts, owns the session lifecycle (`SparkSessions.withSession`,
  deterministic close), runs the subclass's `runSpark`, and assembles the result and metrics. A
  Spark job is therefore invoked identically from the CLI, the API and Airflow, with no per-caller
  branching, provided the process has the Spark runtime (`spark-submit`, or a jar built with
  `-Pbundled-spark`). Under plain `java -jar`, as in the `datacraft/jvm` image, a Spark job returns
  `FAILED` with "Spark runtime is not on the classpath (missing <class>); launch Spark jobs with
  spark-submit".
- **`JvmJobs`** (Java, `datacraft-jobs`) are ordinary `DataJob`s that need no Spark. They read
  local files through `datacraft-io` (`LocalStorageService` when confined, `LocalFiles` otherwise,
  `CsvFiles` for parsing), so they run in the plain JVM image and through the HTTP API, confined
  under `DATACRAFT_DATA_ROOT` when it is set.
- **`ParameterKeys`** (engine) is the single source of truth for the parameter names shared across
  layers (`spark.master`, `input`, `output`, ...), so there are no duplicated magic strings.
  Job-specific names are constants on their job class (`CsvProfileJob.MAX_BYTES`,
  `FileChecksumJob.EXPECTED_SHA256`).
- **`Lifecycle`** (`dev`/`prod`) is a label: the CLI (`--lifecycle`) and the API (`lifecycle=`)
  validate it and put it on the request, but no job reads it and the result JSON does not record
  it.

Parameters, messages, metrics and status codes of every job are specified in
[data-contracts.md](data-contracts.md).

## Packaging model

Spark is `provided` (`spark.scope`), so the shaded CLI jar is small (about 9 MB) and Spark jobs run
via `spark-submit`, which supplies Spark at runtime: the standard pattern for cluster-deployable
Spark applications. The `bundled-spark` Maven profile flips Spark to `compile` for a
self-contained, large jar meant for laptop experiments. The shaded jar has `Runner` as its main
class and declares `Multi-Release: true`, so the Java-version-specific classes of JSch (Ed25519,
X25519) and Jackson stay active, and it carries no dependency `module-info.class`. How the shade
configuration merges manifests, notices, licenses and service files is described in
[Packaging](build-and-quality.md#packaging).

The jar is built once in a builder image (`deploy/docker/Dockerfile.build`) and copied into the
runtime images:

- `datacraft/jvm`: a Java 25 JRE and the jar; serves the API as uid 10001, with the jar
  root-owned.
- `datacraft/airflow`: Airflow 3.3.2 with a Java 25 JRE, pyspark 4.2.0 (which provides
  `spark-submit`), the jar and the DAGs.
- `datacraft/spark` (optional, built only with `BUILD_SPARK_IMAGE=true`): the official Spark 4.2.0
  Java 25 image plus the jar. No stack, DAG or CI job uses it.

## Online engine model

The API is intentionally thin (JDK HTTP server, virtual-thread executor, Jackson):

- `GET|HEAD /health`
- `GET|HEAD /jobs`
- `POST /jobs/{jobName}/runs?lifecycle=dev&key=value`

A run answers with the result JSON: 200 when the job succeeded, 500 when it failed, including
parameter rejections. A run request carrying an `Origin` header gets 403 `cross_origin_forbidden`,
because loopback binding does not stop a browser on the same machine. Unknown routes get a JSON 404
and other methods a JSON 405 with `Allow`; the full status contract, including the JDK server's own
HTML 400 for request targets it cannot parse, is in
[data-contracts.md](data-contracts.md#http-status-contract).

`serve-api` binds `127.0.0.1` by default. It refuses to start (exit 2) on a non-loopback `--host`
while `DATACRAFT_DATA_ROOT` is unset or blank, because the API is unauthenticated and its file jobs
could otherwise read any path. The `datacraft/jvm` image serves on `0.0.0.0:8080` and sets
`DATACRAFT_DATA_ROOT=/opt/datacraft/data`.

On `close()` or SIGTERM the server stops accepting connections at once, waits up to 8 s for
in-flight requests and up to 1 s more for handler threads, which fits Docker's 10 s default stop
timeout. Runs still going after that, such as long Spark jobs, are cut off.

## Deployment topology

Single host (Compose, LocalExecutor) first; multi-host (Swarm, CeleryExecutor + Redis) when one host
is no longer enough. The Compose stack runs PostgreSQL 18, the Airflow 3.3.2 components (API
server, scheduler, DAG processor, triggerer and a one-shot initializer) and `datacraft-api`; Spark
jobs run in local mode through `spark-submit` inside the Airflow containers. The supplied Swarm
stack pins every service to one explicit data node (`DATACRAFT_DATA_NODE`) because its volumes are
node-local; configure shared storage before spreading workers. In both stacks `datacraft-api`
mounts the shared `datacraft-data` volume read-only at `/opt/datacraft/data`, so its small-file
jobs read what Airflow tasks wrote; Compose publishes the API on loopback only, and Swarm keeps it
on the overlay network.

Steps, sizing, upgrades, backup and restore are in the deployment guide
([docs/guides/deployment.md](../docs/guides/deployment.md), Chinese); the reasons behind the
topology and the cloud platform choice are decisions 5 to 7 in [decisions.md](decisions.md).

## Package convention

All JVM code uses base package `com.example.datacraft` plus the module name (`.common`, `.config`,
`.io`, `.engine`, `.jobs`, `.spark`, `.api`, `.cli`).

## Quality gates

The parent POM owns every version and the compiler, formatter, Checkstyle, Enforcer and test
configuration; module POMs declare only their dependencies, their boundary rule and module-specific
plugins. `./mvnw verify` runs the Enforcer rules (JDK 25+, Maven 3.9+, pinned plugins, no POM
repositories, no vulnerable Jackson, module boundaries), javac with `-Xlint:all` and scalac with
`-Werror` (any warning fails), Spotless, Checkstyle, the JUnit and ScalaTest suites and the shaded
package. GitHub Actions on `ubuntu-24.04` is the authoritative gate: on top of `verify` it checks
per-module test floors, the shaded jar and the CLI exit codes, runs real Airflow pipelines, lints
and tests the deployment scripts, type-checks the Python code, validates both stacks and
smoke-tests the images. The full description is in [build-and-quality.md](build-and-quality.md).

## Extending the platform

Coding rules:

- Put a model in `datacraft-common` only when several modules need it; `common` never depends on
  another project module.
- Express a new IO need through a port first (`StorageService` for local files,
  `RemoteFileTransfer` for remote transfer), then decide whether `LocalFiles` needs a lower-level
  helper.
- Every runnable task implements `DataJob` and is registered in the catalog the CLI builds
  (`Runner.registry()`), which the CLI, `serve-api` and Airflow share.
- Job names must not contain `/` and must not equal the control commands `list-jobs` or
  `serve-api`.
- Plain-JVM jobs that do not need Spark go into `datacraft-jobs`, register through `JvmJobs` and
  open their input through `InputFiles`, so the `DATACRAFT_DATA_ROOT` confinement applies.
- Spark code stays in `datacraft-spark` (or a future Spark module). A Spark job extends
  `AbstractSparkDataJob`, parses its parameters in one method shared by `validate` and `runSpark`
  (as `CsvToParquetJob` and `RowCountJob` do) and registers through `SparkJobs`.
- New parameter names shared across layers go into `ParameterKeys`; a Spark job's names also go
  into the case-variant list in `AbstractSparkDataJob`.
- The API only adapts the protocol, converts requests and encodes responses; it holds no business
  logic.
- The CLI only parses arguments and composes the catalog; it does not copy API or engine logic.

Extension points:

- When the HTTP API surface is stable, consider a mature web framework; the JDK HTTP server suits
  the current foundation stage.
- When the number of jobs grows, add a plugin-scanning or configuration-driven `JobCatalog`;
  `JobExecutionEngine` and `EngineHttpServer` depend only on the `JobCatalog` interface.
- For object storage, HDFS or cloud storage, add an implementation of `StorageService` or
  `RemoteFileTransfer` instead of changing the jobs. `StorageService` takes relative
  `java.nio.file.Path` values, so such an adapter maps them to its own keys.
- New Airflow DAGs reuse `datacraft_common.py` (`cli_task`, `spark_task`, `data_path_param`,
  `DEFAULT_ARGS`) for parameters, timeouts, retries and results, and add tests under
  `tests/orchestration` and, for DAGs that process data, a run in
  `tests/smoke/airflow_runtime_smoke.py`.

Adding a JVM module:

1. Create it under the matching group in `modules/` and list it in the root `<modules>`.
2. Give its POM an `enforce-module-boundaries` rule with its allow-list, and add a row to the table
   above.
3. Put its tests in `tests/jvm/<artifactId>/{java,scala}`; the build finds them without further
   configuration. A Scala module also declares `scala-maven-plugin` and `scalatest-maven-plugin`
   and sets `surefire.skip=true` when it has no JUnit tests, as `datacraft-spark` and
   `datacraft-cli` do.
4. Add it to `MODULE_DIRS` and `FLOORS` in `tests/ci/check_test_counts.py`; a test there checks
   that `MODULE_DIRS` matches `<modules>`.
5. Add its POM to the `COPY` list of `deploy/docker/Dockerfile.build`, so the dependency layer that
   the builder resolves from the POMs, before it copies the sources, still covers every module.
6. If it contributes jobs, make `datacraft-cli` depend on it and register the jobs in
   `Runner.registry()`.

## Related documents

- [data-contracts.md](data-contracts.md): parameters, messages, metrics, confinement and HTTP
  status codes.
- [build-and-quality.md](build-and-quality.md): the build, the quality gates and CI.
- [decisions.md](decisions.md): why the architecture looks like this.
- [Evaluation report](../docs/reports/evaluation-report.html) (Chinese): the current assessment,
  evidence, residual risks and the cloud cost calculator.
