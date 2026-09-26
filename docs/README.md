# datacraft-lab

[![CI](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/liluyang1999/datacraft-lab/actions/workflows/ci.yml)

`datacraft-lab` is a personal data-processing factory: a Maven multi-module JVM project written in
Java and Scala. Every job implements one `DataJob` contract and runs through one execution engine,
whether it is started from the command line, through the JSON HTTP API, or by an Apache Airflow
3.3.2 DAG, because the DAGs launch the same CLI jar.

- **Processing**: Apache Spark 4.2.0 jobs (`spark-version`, `csv-to-parquet`, `row-count`) for heavy
  work, launched with `spark-submit`, and plain-JVM jobs (`csv-profile`, `file-checksum`) for small
  files, which need no Spark.
- **One artifact**: the shaded `datacraft-cli.jar` runs jobs and also serves the HTTP API
  (`serve-api`).
- **Deployment**: Docker Compose for a single host (Airflow LocalExecutor) and a Docker Swarm stack
  template (CeleryExecutor) that pins every service to one node, `DATACRAFT_DATA_NODE`, because its
  volumes are node-local.
- **Build**: Java 25 bytecode, a pinned Maven Wrapper, and one `verify` gate covering formatting,
  Checkstyle, dependency rules, tests and packaging; any javac or scalac warning fails the build.

GitHub shows this file as the repository page because the repository root has no README. Links
below are relative to `docs/`.

## Repository layout

```text
datacraft-lab/
├── pom.xml, mvnw, mvnw.cmd, .mvn/   Maven build, pinned wrapper, Maven JVM options
├── Makefile                         shortcuts for the wrapper, test suites and deploy scripts
├── .editorconfig, .gitattributes, .gitignore, .dockerignore, .scalafmt.conf   tool settings
├── pyrightconfig.json               Python type checking
├── config/checkstyle/checkstyle.xml Checkstyle rules
├── .github/workflows/ci.yml         CI
├── modules/                         JVM source modules, grouped by responsibility
│   ├── core/          datacraft-common, datacraft-config, datacraft-engine
│   ├── io/            datacraft-io
│   ├── processing/    datacraft-jobs (plain JVM), datacraft-spark (Spark)
│   └── interfaces/    datacraft-api (HTTP), datacraft-cli (process entry point)
├── orchestration/airflow/           dags/ (with .airflowignore), requirements.txt
├── deploy/                          docker/, compose/, swarm/, scripts/
├── design/                          architecture and design documents (English)
├── docs/                            this page, guides/, reports/, CHANGELOG.md
└── tests/                           all tests, kept outside the modules
    ├── jvm/<artifactId>/{java,scala}   JVM tests, compiled and run by that module's build
    ├── jvm/resources/                  shared JVM test resources (logging configuration)
    ├── orchestration/                  Airflow DAG contract tests (need Airflow)
    ├── smoke/                          Airflow runtime, container and Compose smoke tests
    ├── deploy/                         deployment script and template tests
    ├── ci/                             test-count floor check and its tests, JSch probe
    └── docs/                           documentation consistency and cost calculator tests
```

## Modules

Build a single module by its artifactId, for example `./mvnw -pl :datacraft-jobs -am verify`.

| Path | artifactId | Package | Responsibility | May depend on |
| --- | --- | --- | --- | --- |
| `modules/core/datacraft-common` | `datacraft-common` | `com.example.datacraft.common` | Shared constants, the `Lifecycle` label, the base `DataCraftException` | no project module |
| `modules/core/datacraft-config` | `datacraft-config` | `com.example.datacraft.config` | UTF-8 `java.util.Properties` loading (BOM optional), typed access, prefix slicing | common |
| `modules/io/datacraft-io` | `datacraft-io` | `com.example.datacraft.io` | Root-bound local storage, file helpers, a small CSV reader/writer, SFTP transfers | common |
| `modules/core/datacraft-engine` | `datacraft-engine` | `com.example.datacraft.engine` | The `DataJob` contract, job registry, execution engine, result model, shared parameter keys, built-in `echo` and `noop` | common |
| `modules/processing/datacraft-jobs` | `datacraft-jobs` | `com.example.datacraft.jobs` | Plain-JVM jobs for small files: `csv-profile`, `file-checksum` | common, engine, io |
| `modules/processing/datacraft-spark` | `datacraft-spark` | `com.example.datacraft.spark` | Spark jobs `spark-version`, `csv-to-parquet`, `row-count`, with session handling and path guards (Scala; Spark is `provided`) | common, engine |
| `modules/interfaces/datacraft-api` | `datacraft-api` | `com.example.datacraft.api` | JSON HTTP API on the JDK HTTP server | common, engine |
| `modules/interfaces/datacraft-cli` | `datacraft-cli` | `com.example.datacraft.cli` | Process entry point (Scala): control commands and job dispatch; builds the shaded jar | any module (composition root) |

Every module except `datacraft-cli` has an Enforcer rule, `enforce-module-boundaries`, that fails
the build when the module depends on a project module outside this list. Every module except
`datacraft-spark` and `datacraft-cli` is also barred from depending on Spark. The reasons are in
[Module responsibilities](../design/architecture.md#module-responsibilities).

## Quick start

You need JDK 25 (25.0.4.1 or newer recommended); the Maven Wrapper downloads Maven 3.9.16 on first
use. The [development guide](guides/development.md) lists every prerequisite.

Run the full gate, then run jobs from the shaded jar:

```bash
./mvnw -B -ntp verify        # on Windows: .\mvnw.cmd -B -ntp verify
JAR=modules/interfaces/datacraft-cli/target/datacraft-cli.jar
java -jar "$JAR" --command list-jobs
java -jar "$JAR" --command echo --param message=hello
printf 'id,note\n1,"a, b"\n2,c\n' > sample.csv
java -jar "$JAR" --command csv-profile --param input=sample.csv --param expectedRows=2 --json
```

To build only the jar, without tests, run `make package`, `bash deploy/scripts/build-jar.sh`, or
`deploy/scripts/build.ps1` on Windows. Spark jobs run through `spark-submit`; see
[the usage guide](guides/usage.md).

Deploy the single-host stack with Docker Compose:

```bash
python3 deploy/scripts/deployment_env.py init   # private secrets in deploy/compose/.env
bash deploy/scripts/build-images.sh             # builds the images; the jar compiles in a container
bash deploy/scripts/compose-up.sh               # Postgres, Airflow and the engine API
```

Compose publishes the engine API on `127.0.0.1` and the Airflow UI on `AIRFLOW_WEB_BIND` (default
`127.0.0.1`); reach them through SSH port forwarding. The deployment tutorial, which also covers
Swarm, is in Chinese: [guides/deployment.md](guides/deployment.md).

## Documentation map

| Document | Language | Contents |
| --- | --- | --- |
| [design/README.md](../design/README.md) | English | Index of the design documents and the design principles |
| [design/architecture.md](../design/architecture.md) | English | Repository layout, module responsibilities, the job model, packaging, the online engine, deployment topology, extension points |
| [design/data-contracts.md](../design/data-contracts.md) | English | Results and failures, job parameters, write modes, data root, IO boundaries, HTTP status contract |
| [design/build-and-quality.md](../design/build-and-quality.md) | English | Reactor, versions, compiler and formatting settings, Enforcer rules, tests, packaging, CI gates, zero-warning policy |
| [design/decisions.md](../design/decisions.md) | English | Architecture decision log, including the cloud platform choice |
| [guides/usage.md](guides/usage.md) | English | Running jobs: CLI options, exit codes, `spark-submit`, `serve-api`, HTTP API |
| [guides/development.md](guides/development.md) | English | Prerequisites, build and test commands, test layout, CI |
| [guides/airflow.md](guides/airflow.md) | English | DAGs, Airflow configuration, SFTP connection, Airflow tests |
| [guides/deployment.md](guides/deployment.md) | Chinese | Compose and Swarm deployment tutorial and operations |
| [reports/evaluation-report.html](reports/evaluation-report.html) | Chinese | Consolidated evaluation report, including the cloud platform decision and a cost calculator |
| [reports/pricing-evidence.md](reports/pricing-evidence.md) | Chinese | Sources and calculations behind the cloud prices |
| [CHANGELOG.md](CHANGELOG.md) | English | Change history |
