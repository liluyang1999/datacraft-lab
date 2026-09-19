# datacraft-lab Architecture

## Goal

`datacraft-lab` is a personal data-processing factory: a strict Maven multi-module layout with
Java/Scala interop, a single reusable execution engine, local-or-cluster Spark, reusable
configuration and IO ports, a thin online HTTP API, and Airflow 3.x orchestration with
container deployments.

## The unified job model (the spine)

Everything flows through one contract — `engine.DataJob` (`name`, `description`,
`run(JobExecutionRequest) -> JobExecutionResult`):

```text
HTTP / CLI / Airflow
  -> datacraft-cli (generic dispatcher) or datacraft-api (HTTP)
  -> datacraft-engine: JobRegistry (catalog) + JobExecutionEngine (runs a DataJob, captures metrics)
  -> a DataJob implementation:
       • built-in (echo, noop)                              [engine]
       • Spark-backed via AbstractSparkDataJob              [spark]
         (spark-version, csv-to-parquet, row-count)
```

- **`AbstractSparkDataJob`** (Scala) adapts a Spark computation to `DataJob`: it derives a
  `SparkRuntimeConfig` from the request parameters, owns the session lifecycle
  (`SparkSessions.withSession`, deterministic close), runs the subclass's `runSpark`, and assembles
  the result + metrics. This is why a Spark job is invoked identically from the CLI, the API, and
  Airflow — no per-caller branching.
- **`ParameterKeys`** (engine) is the single source of truth for well-known parameter names shared
  across layers (`spark.master`, `input`, `output`, …), so there are no duplicated magic strings.

## Module boundaries

- **`datacraft-common`** — cross-module primitives (constants, `Lifecycle`, `DataCraftException`).
  Depends on nothing internal. No Spark/SFTP/Airflow/CLI knowledge.
- **`datacraft-config`** — typed UTF-8 properties loading + prefix slicing. Depends on `common`.
- **`datacraft-io`** — data-movement ports and helpers, depends on `common` + transfer libs only:
  - `StorageService` (root-bound local FS port) + `LocalStorageService` + `LocalFiles` (NIO helper:
    read/write/copy/move/delete/size/exists/checksum/list).
  - `RemoteFileTransfer` (port) + `SftpClient` (SFTP impl: get/put/streams/list/exists/size/mkdirs/
    delete/rename); `SftpConfig.fromProperties` for config-driven wiring.
  - `CsvFiles` — dependency-free RFC 4180 reader/writer for small, non-Spark work.
- **`datacraft-engine`** — the central, reusable execution layer: `DataJob`, `JobCatalog`/
  `JobRegistry`, `JobExecutionEngine`, request/result + metrics, `ParameterKeys`, built-in jobs.
  Depends on `common` only (must **not** depend on spark/io/api/cli).
- **`datacraft-spark`** — Spark abstractions and Spark-backed jobs. Depends on `common` + `engine`
  (for the `DataJob` bridge). Spark itself is **`provided`** (scope `${spark.scope}`).
- **`datacraft-api`** — online HTTP access to the engine (JDK HTTP server + Jackson). Depends on
  `engine`; owns no business logic.
- **`datacraft-cli`** — the process entrypoint and generic dispatcher; the single place shells and
  Airflow call. Wires `BuiltInJobs` + `SparkJobs` into one catalog.

`orchestration/airflow` orchestrates the built artifacts (the CLI jar); it never re-implements JVM
logic. `deploy/` holds the Docker images, Compose/Swarm stacks, and scripts.

## Packaging model

Spark is `provided`, so the shaded CLI jar is small (~9 MB) and Spark jobs run via `spark-submit`,
which supplies Spark at runtime — the standard pattern for cluster-deployable Spark apps. The
`bundled-spark` Maven profile flips Spark to `compile` for a self-contained (large) local jar. The
JVM HTTP API binds `127.0.0.1` by default and `0.0.0.0` inside containers (`--host`).

## Online engine model

The API is intentionally thin (JDK HTTP server, virtual-thread executor, Jackson):

- `GET /health`
- `GET /jobs`
- `POST /jobs/{jobName}/runs?lifecycle=dev&key=value`

## Deployment topology

Single host (Compose, LocalExecutor) first; multi-host (Swarm, CeleryExecutor + Redis) when one host
is no longer enough. The supplied Swarm stack pins stateful services and workers to one explicit
data node because its volumes are local. Configure shared storage before spreading workers.
Full reasoning, cost, and steps:
[`deployment/cloud-and-deployment.md`](deployment/cloud-and-deployment.md).

## Package convention

All JVM code uses base package `com.example.datacraft` plus the module name (`.common`, `.config`,
`.io`, `.engine`, `.spark`, `.api`, `.cli`).

## Quality gates

The parent POM centralizes compiler, formatter (Spotless: google-java-format + scalafmt),
Checkstyle, Enforcer, and test plugin configuration. Module POMs declare only their dependencies and
packaging needs. CI runs `mvn verify` on JDK 25 (including a Spark suite that boots a real
SparkSession), a runnable-jar smoke test, real Airflow parsing and operator contract tests,
full DAG execution including SFTP and repeat ETL runs, deployment-script linting and initializer
failure tests, native Compose/Swarm validation, and actual runtime image builds and smoke tests.
See [data contracts](data-processing.md) and the [review record](engineering-report/2026-09-19-review.md).
