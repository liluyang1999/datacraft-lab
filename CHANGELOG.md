# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  and a dependency-free RFC 4180 `CsvFiles`.
- **Engine↔Spark bridge**: `AbstractSparkDataJob` adapts Spark computations to the universal
  `DataJob`; reusable `DataFrames` read/write; Spark jobs `spark-version`, `csv-to-parquet`,
  `row-count`; metric-aware `JobExecutionResult.succeeded/failed`; shared `engine.ParameterKeys`.
- **Spark integration test** (`SparkPipelineSpec`): boots a real SparkSession and runs
  csv-to-parquet then row-count end to end, so CI proves Spark actually runs on the target JDK.
  It self-cancels only on hosts whose JVM cannot open NIO selectors at all.
- **Airflow 3.x orchestration**: reusable `datacraft_common` helpers and three DAGs
  (`datacraft_engine_jobs`, `datacraft_spark_etl`, `datacraft_sftp_ingest`).
- **Deployment**: Dockerfiles (build/jvm/spark/airflow), single-host Compose (LocalExecutor),
  multi-host Swarm stack (CeleryExecutor + Redis), guarded build/up/down/swarm scripts, `Makefile`,
  `.dockerignore`.
- **Docs**: cloud (Cloudflare vs AWS) decision + cost analysis and a tutorial-grade deployment
  guide; refreshed README and architecture; a consolidated standalone review report at
  `PROJECT-REVIEW.html`, and a re-done cloud vendor evaluation at
  `CLOUD-DEPLOYMENT-ANALYSIS.html` (Tokyo-region pricing, Oracle free-tier change, verified
  arm64 image support, decision tree). The deployment guide now points at it instead of
  keeping a second copy of the numbers.
- **CI**: GitHub Actions running `mvn verify` on JDK 25, a runnable-jar smoke test, a
  DAG syntax checks, deployment-script linting
  (`bash -n` + ShellCheck), and Compose/Swarm topology validation. No deployment is automated.

### Changed
- **Upgraded to JDK 25.** The build and all runtimes now target Java 25 bytecode. This required a
  coordinated uplift, because the previous stack does not support Java 25:
  **Spark 4.1.2 -> 4.2.0** (Spark 4.1 supports only Java 17/21; 4.2.0 supports 17/21/25) and
  **Scala 2.13.16 -> 2.13.18** (JDK 25 support landed in 2.13.17, and 2.13.18 is exactly the
  version Spark 4.2.0 is compiled against). Enforcer now requires `[25,)`; the images move to
  `maven:3.9-eclipse-temurin-25`, `eclipse-temurin:25-jre`, and
  `apache/spark:4.2.0-scala2.13-java25-python3-ubuntu`; the Airflow image pins `pyspark==4.2.0`.
  Use JDK **25.0.3+** — Spark 4.2.0 deprecates older Java 25 releases.
- CI now runs the full gate on JDK 25; the separate JDK 25 canary is gone because 25 is the target.
- **Aligned every remaining framework with the JDK 25 baseline** (stable releases only; all
  release-candidate / milestone / beta offers were deliberately rejected): Jackson 2.18.2 -> 2.22.2,
  JUnit 5.13.1 -> 6.1.3, jsch 0.2.25 -> 2.28.7, scala-maven-plugin 4.9.2 -> 4.9.10,
  Spotless 2.46.1 -> 3.10.0, google-java-format 1.28.0 -> 1.36.1, scalafmt 3.8.4 -> 3.11.5,
  maven-jar 3.4.2 -> 3.5.1, maven-resources 3.3.1 -> 3.5.0, maven-shade 3.6.0 -> 3.6.2,
  Maven Wrapper 3.9.9 -> 3.9.16, Airflow image 3.0.2 -> 3.3.1, Postgres 16 -> 18, Redis 7 -> 8.
  Deliberately kept: Scala 2.13.18 and Spark 4.2.0 (latest stable; the newer offers were a Scala 3
  RC and a Spark preview), scalatest 3.2.19, scopt 4.1.0, and the Maven plugins whose only newer
  builds are 4.0.0 betas or milestones.
- Spark dependencies are now **`provided`** (`${spark.scope}`); the CLI jar shrinks to ~9 MB and
  Spark jobs run via `spark-submit`. A `bundled-spark` profile restores a self-contained local jar.
- The CLI is now a **generic engine dispatcher** (control commands `list-jobs`/`serve-api`, all
  other names dispatched as jobs) with `--config`, `--host`, and meaningful exit codes.
- The HTTP API serializes responses with **Jackson** (correct escaping) and can bind all interfaces.

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
