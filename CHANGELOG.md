# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Review and hardening round (2026-09-25)

A reviewed round across the engine, API, CLI, IO, Spark, Airflow, images, build and CI. The JVM
suites now have 262 test cases (73 before this round). Dated record:
[`docs/engineering-report/2026-09-25-review.md`](docs/engineering-report/2026-09-25-review.md).
Items that need action from callers or operators are listed under **Breaking**.

#### Added
- **`datacraft-jobs`** module (`com.example.datacraft.jobs`; depends only on `datacraft-common`,
  `datacraft-engine` and `datacraft-io`). `JvmJobs.register` adds two plain-JVM data jobs to the
  CLI catalog, so `list-jobs`, the API and Airflow's `cli_task` see seven jobs. Both run under
  plain `java -jar` and in the `datacraft/jvm` image, reject case variants of their parameter names,
  and return FAILED with metrics when a quality gate does not match.
  - `csv-profile` profiles a small UTF-8 CSV file in memory with `CsvFiles`. Parameters: `input`,
    `header` (default `true`), `delimiter` (exactly one character, default `,`), `expectedRows` and
    `maxBytes` (1 to 2147483639, default 64 MiB). Metrics: `rows`, `columns`, `bytes`, `sha256`. A
    ragged record fails naming its 1-based record number, and the job refuses a file whose heap
    estimate exceeds a quarter of the maximum heap, before reading and again before parsing.
  - `file-checksum` streams any regular file through SHA-256, with an optional `expectedSha256`
    gate. Metrics: `bytes`, `sha256`.
- **`DATACRAFT_DATA_ROOT`** (trimmed; unset or blank means unconfined). When set, the `input` of
  `csv-profile` and `file-checksum` and the `input`/`output` of `csv-to-parquet` must lie strictly
  inside it. The JVM jobs read through `LocalStorageService`, refuse links below the root and never
  create a missing root. The `datacraft/jvm` image sets `DATACRAFT_DATA_ROOT=/opt/datacraft/data`,
  and in both stacks `datacraft-api` mounts the `datacraft-data` volume read-only there.
- HTTP API: `HEAD /health` and `HEAD /jobs` (`Allow: GET, HEAD`); a JSON 404
  `{"error":"not_found"}` for every unknown route; a 500 `{"error":"internal_error"}` backstop so
  every request is answered and closed; on close or SIGTERM a drain of up to 8 s for in-flight
  requests (longer runs are cut off).
- Engine: a failure the engine catches (anything a job throws, and an unknown job name) is logged
  once at `ERROR` ("Job <name> failed" with the stack trace) through `System.Logger`; a FAILED
  result that a job returns itself, such as a quality-gate mismatch, is not logged. stdout JSON and
  `--result-file` are unchanged.
- Swarm: a one-shot `airflow-init` service (bootstrap entrypoint, restart on failure after 10 s,
  pinned to the data node); bounded `json-file` logs (10 MB x 3) and the Compose healthchecks for
  the API server, scheduler, DAG processor and triggerer; warm Celery worker shutdown
  (`DUMB_INIT_SETSID=0`, `stop_grace_period` of `DATACRAFT_TASK_TIMEOUT_MINUTES`, default 60, plus
  30 s).
- `deployment_env.py check` also rejects publishing `datacraft-api` on anything other than
  `127.0.0.1` or `::1`, and any `datacraft-api` volume or bind mount that is not read-only (tmpfs is
  allowed).
- Build: an Enforcer rule `enforce-module-boundaries` fails `validate` if `datacraft-engine` depends
  on a project module other than `datacraft-common` or on Spark; a WARN-level Enforcer rule for JDK
  releases older than 25.0.3; surefire fails a module whose JUnit scan runs no tests
  (`surefire.failIfNoTests`, default `true`, `false` in `datacraft-cli` and `datacraft-spark`).
- Tests: SFTP integration tests against an embedded Apache MINA SSHD server (test-scoped
  `org.apache.sshd:sshd-sftp` 2.19.0); Windows-only junction tests; a Checkstyle modern-syntax
  fixture; deploy tests for the build entry points, `.dockerignore`, `.editorconfig`, the image
  build contract, the Swarm template, `swarm-deploy.sh`/`airflow-init.sh`, the test-count script
  and documentation consistency (relative links, stale claims, Java release and Airflow pins).
- CI gates: the Maven Wrapper must refuse a distribution with a wrong SHA-256 and no
  `maven-wrapper.jar` may be committed; per-module test-count floors after `./mvnw verify`
  (`.github/scripts/check_test_counts.py`; skipped, aborted and canceled tests do not count); the
  shaded jar must be Multi-Release, contain no `module-info.class`, pass a JSch Ed25519/X25519 probe
  and bundle Jackson at `jackson.version`; CLI exit codes on the real jar (`spark-version` exits 1
  and writes a FAILED result file; an unknown command, a malformed `--param` and a missing
  `--config` exit 2); a `csv-profile`/`file-checksum` smoke test including a failing `expectedRows`
  gate; an Airflow security floor (apache-airflow >= 3.3.2, FAB provider >= 3.9.0); missing-secret
  interpolation checks for both stack files.
- `container_smoke.sh` checks that the build context excludes `.env` and `backups/`, both images'
  JRE equals the freshly pulled Temurin and is at least 25.0.3, the API runs non-root and cannot
  modify its jar, `spark-version` on the JVM image answers HTTP 500 FAILED, `pip check` and the
  Airflow/FAB floors pass, and a fresh data volume is writable by an Airflow task with UID 1000.
  `compose_smoke.sh` checks `pull_policy: never` and the absence of a DAG bind mount, that the
  admin password from `.env` authenticates, and that `datacraft-api` runs non-root with a
  read-only data mount at `DATACRAFT_DATA_ROOT`.

#### Changed
- CLI: `--json` writes exactly one UTF-8 JSON line ending in LF on every platform, byte-identical
  to `--result-file`; stdout is written first, and a result file that cannot be written gives exit
  1 with `Failed to write --result-file <path>: <cause>`. The CLI refuses to start when a job name
  equals a control command. `Runner.run` returns the exit code, and only `main` exits. Help texts
  describe the new `--config`, `--master` and `--lifecycle` semantics.
- `DataCraftConfig`: skips one leading BOM; `getInt`/`getBoolean` tolerate surrounding whitespace;
  a malformed `\u` escape or a blank key fails with `Invalid configuration file: <file>: <reason>`,
  never echoing property values.
- Engine failure messages: an exception with a null or blank message reports its fully qualified
  class name (was the simple name); an `Error` reports `SimpleClassName: message`, or only the
  simple name when the message is blank.
- Spark jobs validate their parameters before a SparkSession starts. `expectedRows`,
  `spark.shufflePartitions` and a launcher `spark.sql.shuffle.partitions` get named range messages
  that never echo the value. Shuffle partitions fall back to spark-submit's
  `spark.sql.shuffle.partitions`, then 8. `spark.master`, `spark.appName` and `spark.warehouseDir`
  are trimmed, and a blank `spark.master` falls back to the launcher or `local[*]`. `mode=ignore` on
  an existing output reports `Skipped: <output> already exists (mode=ignore)`. The overlap guard
  canonicalises HDFS authorities and resolves scheme-less paths against `fs.defaultFS`. On a JVM
  without Spark, Spark jobs return FAILED with "Spark runtime is not on the classpath (missing
  <class>); launch Spark jobs with spark-submit".
- IO: `RemoteFileTransfer.download(String, Path)` is an atomic default method (a dot-prefixed
  `.part` sibling moved onto the target); `SftpClient.upload(Path, ...)` streams the file;
  `SftpClient.list` returns the sorted names of regular files, following links to files;
  `SftpConfig.fromProperties` trims `host` and `username` and keeps the password verbatim.
- Airflow: non-integer `DATACRAFT_TASK_RETRIES` or `DATACRAFT_TASK_TIMEOUT_MINUTES` import errors
  name the variable; `retry_exponential_backoff` is the float `2.0` (Airflow registers a new DAG
  version once); `cli_task`/`spark_task` values ending in `.sh` or `.bash` stay literal arguments;
  the Airflow 2.x fallback imports and the stale `orchestration/airflow/.airflowignore` are removed;
  the runtime smoke test pins the row counts and `note` values of both Parquet outputs.
- Images: `datacraft/jvm` runs as uid/gid 10001 with a root-owned, read-only jar. The Airflow
  image's `/opt/datacraft/data` is group-writable for GID 0. `build-images.sh` builds the builder
  with `--pull`, pulls each public base image before building on it, and accepts `MAVEN_IMAGE`,
  `JRE_IMAGE`, `AIRFLOW_IMAGE` and `SPARK_IMAGE` overrides. `.dockerignore` also excludes
  `backups/`, `warehouse/`, `spark-warehouse/`, `.airflow/`, `**/.env` and `.claude/`.
- Compose: datacraft images use `pull_policy: never`, and `compose-up.sh` stops early with "run
  bash deploy/scripts/build-images.sh first" when they are missing. Both stacks set
  `DATACRAFT_DATA_ROOT=/opt/datacraft/data` under `environment:` for every Airflow container and
  `datacraft-api`, overriding any `.env` value. Swarm pins `datacraft-api` to `DATACRAFT_DATA_NODE`
  and keeps it off published ports.
- `airflow-bootstrap.sh` passes the admin password to `airflow users create` on stdin under
  `setsid -w` (it no longer appears in argv or `ps`), and looks users up with
  `airflow users export`, whose file output is not corrupted by warnings Airflow 3.3.2 prints on
  stdout.
- `.env.example`: `DATACRAFT_TAG=` is empty, `AIRFLOW_WORKER_REPLICAS=1`, `AIRFLOW_UID=50000` is
  now recommended on Linux too (no host directory is bind-mounted), and commented optional
  `DATACRAFT_TASK_RETRIES`, `DATACRAFT_TASK_TIMEOUT_MINUTES` and `DATACRAFT_JAVA_BIN` entries.
- Build and toolchain: Apache Maven Wrapper 3.3.4 (script-only; `.mvn/wrapper/maven-wrapper.jar`
  removed) replaces the Takari wrapper and verifies the Maven 3.9.16 download with
  `distributionSha256Sum`; on Linux/macOS it needs `unzip`. Checkstyle engine 9.3 -> 14.1.0
  (`checkstyle.version`), maven-surefire-plugin and maven-failsafe-plugin 3.5.3 -> 3.5.6. Tests
  run with `-Djdk.net.unixdomain.tmpdir=${project.build.directory}` (`test.nio.jvm.args`). `make`
  defaults to `./mvnw` (`make MVN=mvn` overrides). `Dockerfile.build` defaults to
  `maven:3.9.16-eclipse-temurin-25`. `build.ps1` falls back to `mvnw.cmd` when `mvn` is not on
  `PATH`. `.editorconfig` sets 2-space Java/Scala indents, tab-indented Makefile recipes and CRLF
  for `*.cmd`/`*.bat`.
- CI: the actions moved from their Node 20 majors (checkout, setup-java and upload-artifact v4,
  setup-python v5, download-artifact v4) to node24 majors pinned to full commit SHAs (checkout
  v7.0.1, setup-java v6.0.1, setup-python v7.0.0, upload-artifact v7.0.1, download-artifact
  v8.0.1), and the new setup-node step uses v7.0.0; the scripts job fails on any unpinned `uses:`.
  Every job runs on `ubuntu-24.04` (was `ubuntu-latest`). The dags job is renamed "Airflow
  contracts and real pipelines (3.3.2)" and installs Airflow 3.3.2 with constraints-3.3.2. The
  scripts job sets up Python 3.12 and Node 22.

#### Fixed
- Engine: an `Error` other than a `VirtualMachineError` (for example `NoClassDefFoundError` for a
  Spark job under plain `java -jar`) escaped the engine, so the CLI printed an uncaught stack trace
  with no structured output and an HTTP request hung. It now becomes a FAILED result (CLI exit 1
  with `--json`/`--result-file`; HTTP 500 with the result body).
- HTTP: raw non-ASCII query bytes were silently double-decoded; the handler now answers 400
  `invalid_request`. Unknown routes such as `/` returned the JDK's HTML 404.
- CLI: `--json` stdout used the platform line separator and console charset, so it could differ
  from `--result-file`. A `--config` file with a BOM lost its first key.
- IO: `LocalStorageService` refuses paths through symbolic links and any existing component whose
  real path leaves the root, including Windows junctions; the root itself is valid only as a listing
  directory; listings omit symbolic links, and recursive listings skip reparse points.
  `LocalFiles.deleteRecursively` removes links and junctions (dangling ones included) without
  deleting their targets.
- `CsvFiles` writer: records end with LF on every OS; a record of one empty field is written as
  `""` instead of a blank line; a field starting with U+FEFF is quoted; a row with no fields is
  rejected.
- SFTP: a failed download no longer truncates the existing local file or leaves a partial file.
- Spark `row-count` reads JSON in FAILFAST mode and counts CSV and JSON over complete records, so
  malformed JSON lines fail the job instead of being counted.
- The shaded `datacraft-cli.jar` (and the `bundled-spark` jar) declares `Multi-Release: true`, so
  JSch's X25519, mlkem768x25519 and ssh-ed25519 support and Jackson's versioned classes are active;
  dependency `module-info.class` files are no longer copied into it.
- Checkstyle 9.3 could not parse record patterns, `case ... when` guards or JEP 513 constructor
  bodies.
- Windows hosts: symbolic-link tests abort (JUnit) or cancel (ScalaTest) and Parquet-writing Spark
  tests cancel when their preconditions are missing, instead of failing; the HTTP and Spark tests
  no longer depend on the profile TEMP directory for NIO selectors.

#### Security
- Airflow 3.3.1 -> 3.3.2 in the image and CI, with constraints-3.3.2: FAB provider 3.8.0 -> 3.9.0,
  sqlparse 0.5.5 -> 0.6.0. Fixes CVE-2026-86473 and CVE-2026-82355 (Airflow core) and
  CVE-2026-82311, CVE-2026-86462, CVE-2026-82310 and CVE-2026-86466 (FAB provider 3.9.0).
- Jackson 2.22.2 -> 2.22.3 (jackson-annotations stays 2.22): GHSA-wv8q-qhhj-9h54 /
  CVE-2026-91776, GHSA-cxp5-3px4-pw24 / CVE-2026-91777, GHSA-p6pp-m3f8-5c89 / CVE-2026-89407 and
  GHSA-7hhh-6rmp-j9qf / CVE-2026-89425. None is reachable from the serialize-only API. The Enforcer
  fails `validate` if jackson-core or jackson-databind older than 2.22.3 enters the build.
- The runtime Dockerfiles no longer default `JAR_IMAGE`, so a build cannot fall back to the
  third-party Docker Hub name `datacraft/jar-builder`; `pull_policy: never` keeps Compose from
  pulling `datacraft/*` names; database dumps and env files stay out of the build context.
- Also security-relevant, listed under Breaking: the `Origin` 403, the `serve-api` loopback rule,
  required secrets, the SFTP host-key guard and the Airflow data-path confinement.

#### Breaking
- Engine/API: `ParameterKeys.OUTPUT_FORMAT` is removed (source-incompatible for external code).
  `JobRegistry.register` rejects job names containing `/`. A run request carrying any `Origin`
  header gets 403 `{"error":"cross_origin_forbidden"}`, so browsers can no longer trigger runs.
- CLI: `list-jobs` and `serve-api` reject `--config`, `--master`, `--lifecycle`, `--param`, `--json`
  and `--result-file` with exit 2. An unreadable or malformed `--config` exits 2 (was an uncaught
  exception with exit 1) with one stderr line and no stack trace. `--config` values are passed
  verbatim: trailing whitespace is kept and escapes such as `\t` survive (values were trimmed
  before). The CLI no longer injects `spark.master=local[*]`, so a Spark job without
  `--param spark.master`, `--master` or a config `spark.master` runs on spark-submit's `--master`;
  runs that silently ran locally may now run on that cluster. `serve-api` on a non-loopback
  `--host` exits 2 unless `DATACRAFT_DATA_ROOT` is set and not blank.
- Spark: `csv-to-parquet` rejects glob inputs (`{ } [ ] * ?` or a backslash); a key that differs
  from a Spark job parameter only by case fails instead of being ignored; with
  `DATACRAFT_DATA_ROOT` set (as in both stacks), `csv-to-parquet` paths outside the root fail.
- SFTP: remote paths are literal (`*`, `?` and `\` are no longer interpreted by JSch). Uploading
  onto an existing remote directory, or downloading onto an existing local directory, now fails. A
  download replaces the target file, so it gets default permissions and hard links keep the old
  content.
- Airflow: `datacraft_sftp_ingest.download` fails without retry unless the `datacraft_sftp`
  connection verifies the host key (a `host_key` extra, or `"no_host_key_check": false` with a
  `known_hosts` file); add the key before upgrading. The trigger-conf data paths of
  `datacraft_spark_etl` and `datacraft_sftp_ingest` must be absolute paths inside
  `DATACRAFT_DATA_ROOT` (default `$DATACRAFT_HOME/data`) without `.`/`..` segments, `{}[]*?\`,
  NUL/CR/LF or trailing whitespace; an invalid root is an import error.
- Deployment: the DAG bind mount is removed, so DAGs and the CLI jar come from the image; run
  `build-images.sh` after `git pull`. `POSTGRES_PASSWORD`, `AIRFLOW_FERNET_KEY`,
  `AIRFLOW_API_SECRET_KEY` and `AIRFLOW_JWT_SECRET` are required at interpolation (`${VAR:?}`) in
  both stack files, with no weak fallback. `docker build` of the runtime Dockerfiles needs
  `--build-arg JAR_IMAGE`; use `build-images.sh`, which now needs registry access to pull the base
  images. An existing `datacraft-data` volume used with an `AIRFLOW_UID` other than 50000 needs the
  one-off `chmod g+rwx` repair from the deployment guide.
- Swarm: `swarm-deploy.sh` requires `DATACRAFT_TAG` set to the exact pushed version (empty and
  `latest` are rejected) and deploys with `--with-registry-auth` (run `docker login` first).
  `airflow-init.sh` only waits for the `airflow-init` service and must run on a manager
  (`DATACRAFT_INIT_TIMEOUT`, default 600 s; `DATACRAFT_INIT_POLL_SECONDS`, default 5 s).
  `AIRFLOW_WORKER_REPLICAS` defaults to 1 (was 2).

### Economical cloud deployment and cost estimates (2026-09-19)
- **Breaking:** `compose-up.sh` and `swarm-deploy.sh` stop when `deploy/compose/.env` is missing
  instead of copying `.env.example`. Run `python3 deploy/scripts/deployment_env.py init`: it fills
  the template with generated secrets (including a Fernet key, without extra Python packages),
  creates the file with mode 0600 and never overwrites an existing file.
- **Breaking:** both scripts first pipe the effective Compose configuration (`docker compose config
  --format json`, including shell overrides) into `deployment_env.py check`. It rejects a
  `POSTGRES_PASSWORD` that is not at least 32 URL-safe characters (letters, digits, `_`, `-`),
  Airflow API, JWT and Fernet secrets or an admin password shorter than 32 characters or starting
  with `change-me`/`please-change`, secrets that differ between Airflow components or repeat one
  another, a Fernet key that does not decode to 32 bytes, and a `POSTGRES_USER` or `POSTGRES_DB`
  that is not a simple SQL identifier.
- **Breaking:** the Compose Airflow UI binds `AIRFLOW_WEB_BIND` (default `127.0.0.1`) instead of
  every interface; reach it through SSH forwarding. `compose-up.sh` waits for healthy services
  (`docker compose up -d --wait`, `DATACRAFT_START_TIMEOUT`, default 300 s) instead of printing
  URLs.
- Conservative single-host defaults: `AIRFLOW_PARALLELISM=1`, `AIRFLOW_PARSING_PROCESSES=1`, one
  API server worker, and `DATACRAFT_SPARK_MASTER=local[2]` (was `local[*]`).
- Every Compose service logs through `json-file`, capped at 10 MB x 3 files; the scheduler, DAG
  processor and triggerer gained `airflow jobs check` healthchecks.
- `CLOUD-DEPLOYMENT-ANALYSIS.html` became a Tokyo/Singapore cost comparison with an interactive
  calculator (`docs/deployment/cloud-costs.js`), backed by `docs/deployment/pricing-evidence.md`;
  the deployment guide was rewritten (Chinese) around SSH forwarding, resource limits, backup and
  restore. `node --test deploy/tests/cloud-costs.test.cjs` checks the calculator in CI.
- CI runs `deploy/tests/compose_smoke.sh`: the full Compose stack with generated secrets, a
  scheduled `datacraft_engine_jobs` run through the Execution API and LocalExecutor (after the DAG
  is registered), and a `pg_dump -Fc` backup restored into a separate database that must contain
  the successful run.
- `/backups/` is gitignored; `airflow-bootstrap.sh` calls `python3`.

### Data processing and orchestration review (2026-09-19)
- CSV parsing now preserves quoted empty records and UTF-8 BOM input and rejects malformed quoting.
- Spark CSV conversion supports explicit DDL schemas, strict parsing, RFC quoting and multiline
  records. It validates/cache-materializes input before writing, rejects overlapping paths, and
  reports accurate ignore/append metrics. Row counting supports `expectedRows` as a failing quality
  gate. Managed sessions cannot stop another owner, and checked Scala exceptions become failed jobs.
- Airflow uses literal argument environments, task deadlines, bounded retries and one active DAG
  run; missing SFTP providers fail visibly. Structured CLI result files carry counts through XCom
  and are cleaned after tasks. Added real provider, shell, initializer and full DAG smoke coverage.
- HTTP invalid requests return JSON errors rather than disconnecting; job paths decode once. CLI
  validates arguments, honors explicit master precedence and exports JSON results.
- Local storage rejects symlink traversal, hashing uses bounded memory, and SFTP validates timeout
  limits, supports known-hosts files, closes failed connections and redacts config passwords.
- Deployment shares the Airflow execution API/JWT configuration, uses constrained providers, fails
  visibly on initialization errors and uses the PostgreSQL 18 volume layout. Local-volume Swarm
  services require a fixed data node; the unauthenticated engine API is no longer published remotely.
- Existing deployments must review the volume-layout and network-access notes before applying the
  updated definitions. No automatic database migration or production deployment is performed.

### Added
- **IO ports & helpers**: `RemoteFileTransfer` port with a full-featured `SftpClient`
  (streams, `list`/`exists`/`size`/`mkdirs`/`delete`/`rename`), `SftpConfig.fromProperties`,
  richer `LocalFiles`/`StorageService` (`move`/`delete`/`deleteRecursively`/`exists`/`size`/lines),
  and `CsvFiles`, a dependency-free CSV reader/writer using RFC 4180 quoting (it writes LF record
  terminators; the reader accepts CRLF, LF or CR and a leading BOM).
- **Engine↔Spark bridge**: `AbstractSparkDataJob` adapts Spark computations to the universal
  `DataJob`; reusable `DataFrames` read/write; Spark jobs `spark-version`, `csv-to-parquet`,
  `row-count`; metric-aware `JobExecutionResult.succeeded/failed`; shared `engine.ParameterKeys`.
- **Spark integration test** (`SparkPipelineSpec`): boots a real SparkSession and runs
  csv-to-parquet then row-count end to end, so CI proves Spark actually runs on the target JDK.
  It cancels on hosts whose JVM cannot open NIO selectors, and its Parquet-writing tests cancel on
  Windows without winutils; CI fails when any Spark test is canceled.
- **Airflow 3.x orchestration**: reusable `datacraft_common` helpers and three DAGs
  (`datacraft_engine_jobs`, `datacraft_spark_etl`, `datacraft_sftp_ingest`).
- **Deployment**: Dockerfiles (build/jvm/spark/airflow), single-host Compose (LocalExecutor),
  multi-host Swarm stack (CeleryExecutor + Redis), guarded build/up/down/swarm scripts, `Makefile`,
  `.dockerignore`.
- **Docs**: a tutorial-grade deployment guide; refreshed README and architecture; a consolidated
  standalone review report at `PROJECT-REVIEW.html`; and a cloud cost report at
  `CLOUD-DEPLOYMENT-ANALYSIS.html`, now a Tokyo/Singapore cost comparison with an interactive
  calculator (ARM hosts are priced but not run-verified). The deployment guide links to it instead
  of keeping a second copy of the numbers.
- **CI**: GitHub Actions running `./mvnw verify` on JDK 25, a runnable-jar smoke test, Airflow DAG
  checks, deployment-script linting (`bash -n` + ShellCheck), and Compose/Swarm topology validation.
  No deployment is automated.

### Changed
- **Upgraded to JDK 25.** The build and all runtimes now target Java 25 bytecode. This required a
  coordinated uplift, because the previous stack does not support Java 25:
  **Spark 4.1.2 -> 4.2.0** (Spark 4.1 supports only Java 17/21; 4.2.0 supports 17/21/25) and
  **Scala 2.13.16 -> 2.13.18** (JDK 25 support landed in 2.13.17, and 2.13.18 is exactly the
  version Spark 4.2.0 is compiled against). Enforcer now requires `[25,)`; the images move to a
  Maven + Temurin 25 builder (`maven:3.9.16-eclipse-temurin-25` since 2026-09-25),
  `eclipse-temurin:25-jre`, and `apache/spark:4.2.0-scala2.13-java25-python3-ubuntu`; the Airflow
  image pins `pyspark==4.2.0`. Use JDK **25.0.3+** — Spark 4.2.0 deprecates older Java 25 releases.
- CI now runs the full gate on JDK 25; the separate JDK 25 canary is gone because 25 is the target.
- **Upgraded the remaining frameworks for the JDK 25 baseline** (stable releases available at the
  time; release-candidate / milestone / beta offers were deliberately rejected): Jackson 2.18.2 ->
  2.22.2 (2.22.3 since 2026-09-25), JUnit 5.13.1 -> 6.1.3, jsch 0.2.25 -> 2.28.7,
  scala-maven-plugin 4.9.2 -> 4.9.10, Spotless 2.46.1 -> 3.10.0, google-java-format 1.28.0 ->
  1.36.1, scalafmt 3.8.4 -> 3.11.5, maven-jar 3.4.2 -> 3.5.1, maven-resources 3.3.1 -> 3.5.0,
  maven-shade 3.6.0 -> 3.6.2, Maven 3.9.9 -> 3.9.16 (via the wrapper), Airflow image 3.0.2 ->
  3.3.1 (3.3.2 since 2026-09-25), Postgres 16 -> 18, Redis 7 -> 8. Deliberately kept: Scala
  2.13.18 and Spark 4.2.0 (the newer offers were a Scala 3 RC and a Spark preview), scalatest
  3.2.19, scopt 4.1.0, and the Maven plugins whose only newer builds are 4.0.0 betas or milestones.
- Spark dependencies are now **`provided`** (`${spark.scope}`); the CLI jar shrinks to ~9 MB and
  Spark jobs run via `spark-submit`. A `bundled-spark` profile restores a self-contained local jar.
- The CLI is now a **generic engine dispatcher** (control commands `list-jobs`/`serve-api`, all
  other names dispatched as jobs) with `--config`, `--host`, and meaningful exit codes.
- The HTTP API serializes responses with **Jackson** (correct escaping) and can bind all interfaces
  (since 2026-09-25 only with `DATACRAFT_DATA_ROOT` set).

- `mvnw` and the deployment scripts are marked executable in git, so `./mvnw` works on Linux CI
  runners (this was the cause of the first failing CI run).
- `deploy/compose/.env.example` now documents the Swarm-only variables `DATACRAFT_REGISTRY`,
  `DATACRAFT_TAG`, and `AIRFLOW_WORKER_REPLICAS`.

- `airflow fab-db migrate` now runs during initialisation. The FAB auth manager keeps its
  user/role tables in a separate schema, so without it `airflow users create` fails and no admin
  account exists to log in with.
- The Airflow image resolves `SPARK_HOME` from the installed pyspark package instead of hardcoding
  a `python3.12` site-packages path, so it survives a Python bump in the Airflow base image.

### Removed
- The unused `SparkJob` trait (superseded by `AbstractSparkDataJob`).
