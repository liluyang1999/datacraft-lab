# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  `PROJECT-REVIEW.html`.
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
- Spark dependencies are now **`provided`** (`${spark.scope}`); the CLI jar shrinks to ~9 MB and
  Spark jobs run via `spark-submit`. A `bundled-spark` profile restores a self-contained local jar.
- The CLI is now a **generic engine dispatcher** (control commands `list-jobs`/`serve-api`, all
  other names dispatched as jobs) with `--config`, `--host`, and meaningful exit codes.
- The HTTP API serializes responses with **Jackson** (correct escaping) and can bind all interfaces.

- `mvnw` and the deployment scripts are marked executable in git, so `./mvnw` works on Linux CI
  runners (this was the cause of the first failing CI run).
- `deploy/compose/.env.example` now documents the Swarm-only variables `DATACRAFT_REGISTRY`,
  `DATACRAFT_TAG`, and `AIRFLOW_WORKER_REPLICAS`.

### Removed
- The unused `SparkJob` trait (superseded by `AbstractSparkDataJob`).
