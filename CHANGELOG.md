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
- **Airflow 3.x orchestration**: reusable `datacraft_common` helpers and three DAGs
  (`datacraft_engine_jobs`, `datacraft_spark_etl`, `datacraft_sftp_ingest`).
- **Deployment**: Dockerfiles (build/jvm/spark/airflow), single-host Compose (LocalExecutor),
  multi-host Swarm stack (CeleryExecutor + Redis), guarded build/up/down/swarm scripts, `Makefile`,
  `.dockerignore`.
- **Docs**: cloud (Cloudflare vs AWS) decision + cost analysis and a tutorial-grade deployment
  guide; refreshed README and architecture; a consolidated standalone review report at
  `PROJECT-REVIEW.html`.
- **CI**: GitHub Actions running `mvn verify` on JDK 21 plus a DAG syntax check.

### Changed
- Spark dependencies are now **`provided`** (`${spark.scope}`); the CLI jar shrinks to ~9 MB and
  Spark jobs run via `spark-submit`. A `bundled-spark` profile restores a self-contained local jar.
- The CLI is now a **generic engine dispatcher** (control commands `list-jobs`/`serve-api`, all
  other names dispatched as jobs) with `--config`, `--host`, and meaningful exit codes.
- The HTTP API serializes responses with **Jackson** (correct escaping) and can bind all interfaces.

### Removed
- The unused `SparkJob` trait (superseded by `AbstractSparkDataJob`).
