# datacraft-lab Architecture

## Goal

`datacraft-lab` is a personal data-processing factory: a strict Maven multi-module layout with
Java/Scala interop, a single reusable execution engine, local-or-cluster Spark, plain-JVM jobs for
small files, reusable configuration and IO ports, a thin online HTTP API, and Airflow 3.x
orchestration with container deployments.

## The unified job model (the spine)

Everything flows through one contract — `engine.DataJob` (`name`, `description`,
`run(JobExecutionRequest) -> JobExecutionResult`):

```text
HTTP / CLI / Airflow
  -> datacraft-cli (generic dispatcher) or datacraft-api (HTTP)
  -> datacraft-engine: JobRegistry (catalog) + JobExecutionEngine (runs a DataJob, captures metrics)
  -> a DataJob implementation:
       • built-in (echo, noop)                              [engine]
       • plain-JVM small-file jobs via JvmJobs              [jobs]
         (csv-profile, file-checksum)
       • Spark-backed via AbstractSparkDataJob              [spark]
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
  deterministic close), runs the subclass's `runSpark`, and assembles the result + metrics. A Spark
  job is therefore invoked identically from the CLI, the API, and Airflow — no per-caller branching
  — provided the process has the Spark runtime (`spark-submit`, or a jar built with
  `-Pbundled-spark`). Under plain `java -jar`, as in the `datacraft/jvm` image, a Spark job returns
  `FAILED` with "Spark runtime is not on the classpath (missing <class>); launch Spark jobs with
  spark-submit".
- **`JvmJobs`** (Java, `datacraft-jobs`) are ordinary `DataJob`s that need no Spark. They read local
  files through the `datacraft-io` ports, so they run in the plain JVM image and through the HTTP
  API, confined under `DATACRAFT_DATA_ROOT` when it is set.
- **`ParameterKeys`** (engine) is the single source of truth for well-known parameter names shared
  across layers (`spark.master`, `input`, `output`, …), so there are no duplicated magic strings.
- **`Lifecycle`** (`dev`/`prod`) is a label: the CLI (`--lifecycle`) and the API (`lifecycle=`)
  validate it and put it on the request, but no job reads it and the result JSON does not record it.

## Module boundaries

- **`datacraft-common`** — cross-module primitives (constants, `Lifecycle`, `DataCraftException`).
  Depends on nothing internal. No Spark/SFTP/Airflow/CLI knowledge.
- **`datacraft-config`** — typed UTF-8 properties loading + prefix slicing. Depends on `common`.
- **`datacraft-io`** — data-movement ports and helpers, depends on `common` + transfer libs only:
  - `StorageService` (root-bound local FS port) + `LocalStorageService` + `LocalFiles` (NIO helper:
    read/write/copy/move/delete/size/exists/checksum/list).
  - `RemoteFileTransfer` (port, with an atomic default `download(String, Path)`) + `SftpClient`
    (SFTP impl with literal remote paths: get/put/streams/list/exists/size/mkdirs/delete/rename);
    `SftpConfig.fromProperties` for config-driven wiring.
  - `CsvFiles` — dependency-free CSV reader/writer for small, non-Spark work, using RFC 4180
    quoting. The writer ends every record with LF; the reader accepts CRLF, LF or CR record
    terminators and a leading BOM.
- **`datacraft-engine`** — the central, reusable execution layer: `DataJob`, `JobCatalog`/
  `JobRegistry`, `JobExecutionEngine`, request/result + metrics, `ParameterKeys`, built-in jobs.
  Depends on `common` only (must **not** depend on config/io/jobs/spark/api/cli or on Spark).
- **`datacraft-jobs`** — plain-JVM data jobs (`csv-profile`, `file-checksum`) registered through
  `JvmJobs`. Depends on `common` + `engine` + `io`; no Spark.
- **`datacraft-spark`** — Spark abstractions and Spark-backed jobs. Depends on `common` + `engine`
  (for the `DataJob` bridge). Spark itself is **`provided`** (scope `${spark.scope}`).
- **`datacraft-api`** — online HTTP access to the engine (JDK HTTP server + Jackson). Depends on
  `common` + `engine`; owns no business logic.
- **`datacraft-cli`** — the process entrypoint and generic dispatcher; the single place shells and
  Airflow call. Wires `BuiltInJobs` + `SparkJobs` + `JvmJobs` into one catalog.

`orchestration/airflow` orchestrates the built artifacts (the CLI jar); it never re-implements JVM
logic. `deploy/` holds the Docker images, Compose/Swarm stacks, and scripts.

## Packaging model

Spark is `provided`, so the shaded CLI jar is small (~9 MB) and Spark jobs run via `spark-submit`,
which supplies Spark at runtime — the standard pattern for cluster-deployable Spark apps. The
`bundled-spark` Maven profile flips Spark to `compile` for a self-contained (large) local jar. The
shaded jar declares `Multi-Release: true`, so the Java-version-specific classes of JSch (X25519,
Ed25519) and Jackson stay active, and it carries no dependency `module-info.class`.

The JVM HTTP API binds `127.0.0.1` by default. `serve-api` refuses to start (exit 2) on a
non-loopback `--host` while `DATACRAFT_DATA_ROOT` is unset or blank, because the API is
unauthenticated and its file jobs could otherwise read any path. The `datacraft/jvm` image serves
on `0.0.0.0:8080` as uid 10001 and sets `DATACRAFT_DATA_ROOT=/opt/datacraft/data`.

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
[data-processing.md](data-processing.md#http-status-contract).

On `close()` or SIGTERM the server stops accepting connections at once, waits up to 8 s for
in-flight requests and up to 1 s more for handler threads, which fits Docker's 10 s default stop
timeout. Runs still going after that, such as long Spark jobs, are cut off.

## Deployment topology

Single host (Compose, LocalExecutor) first; multi-host (Swarm, CeleryExecutor + Redis) when one host
is no longer enough. The supplied Swarm stack pins stateful services, workers and `datacraft-api` to
one explicit data node because its volumes are local. Configure shared storage before spreading
workers. In both stacks `datacraft-api` mounts the shared `datacraft-data` volume read-only at
`/opt/datacraft/data`, so its small-file jobs read what Airflow tasks wrote; Compose publishes the
API on loopback only, and Swarm keeps it on the overlay network.
Full reasoning, cost, and steps:
[`deployment/cloud-and-deployment.md`](deployment/cloud-and-deployment.md).

## Package convention

All JVM code uses base package `com.example.datacraft` plus the module name (`.common`, `.config`,
`.io`, `.engine`, `.jobs`, `.spark`, `.api`, `.cli`).

## Quality gates

The parent POM centralizes compiler, formatter (Spotless: google-java-format + scalafmt),
Checkstyle (engine pinned to 14.1.0 through `checkstyle.version`), Enforcer, and test plugin
configuration. Module POMs declare only their dependencies and packaging needs. The build enforces
the boundaries above instead of only documenting them:

- The root Enforcer rules require JDK 25+ (with a warning below 25.0.3) and Maven 3.9+, and fail
  `validate` when jackson-core or jackson-databind older than 2.22.3 enters the dependency graph.
- The engine boundary is enforced by the Enforcer: `datacraft-engine`'s `enforce-module-boundaries`
  rule fails `validate` when any project module other than `datacraft-common`, or any
  `org.apache.spark` artifact, is among its dependencies. The `datacraft-common` boundary is
  enforced by reactor cycle detection: every other module depends on it, so an internal dependency
  from `common` would form a cycle, which Maven rejects.
- Surefire fails a module whose JUnit scan runs no tests (`surefire.failIfNoTests`, default `true`;
  `false` in `datacraft-cli` and `datacraft-spark`, which test through ScalaTest).

GitHub Actions CI is the authoritative gate. Every job runs on `ubuntu-24.04`, and every action is
pinned to a full commit SHA (the scripts job fails on any other non-local `uses:`). The build job:

- checks that the Maven Wrapper refuses a Maven distribution with the wrong SHA-256, then runs
  `./mvnw -B -ntp verify` on JDK 25, including a Spark suite that boots a real SparkSession;
- fails any module that executed fewer tests than its floor in
  `.github/scripts/check_test_counts.py` (Linux counts; skipped, aborted and canceled tests do not
  count);
- runs `list-jobs`, `echo`, `noop` and the plain-JVM jobs with their quality gates on the shaded
  jar, and checks its `Multi-Release` manifest, the absence of `module-info.class`, JSch's Ed25519
  and X25519 support (`JschAlgorithmsProbe.java`), and that the bundled jackson-core and
  jackson-databind equal `jackson.version`;
- asserts the CLI exit codes: `spark-version` under plain `java -jar` exits 1 and still writes a
  `FAILED` `--result-file`, and an unknown command, a malformed `--param` and a missing `--config`
  exit 2 without an uncaught exception.

The other jobs run real Airflow 3.3.2 parsing and operator contract tests behind a security floor
(Airflow 3.3.2, FAB provider 3.9.0), full DAG execution including SFTP and repeat ETL runs,
deployment-script linting and tests, native Compose/Swarm validation including missing-secret
rejection, and actual runtime image builds and smoke tests.

See [data contracts](data-processing.md), the
[2026-09-25 review record](engineering-report/2026-09-25-review.md) of the latest round (the
previous one is [2026-09-19](engineering-report/2026-09-19-review.md)), and the consolidated
evaluation report [`PROJECT-REVIEW.html`](../PROJECT-REVIEW.html) (Chinese).
