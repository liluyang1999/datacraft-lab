# Architecture decisions

This log records the decisions that shape datacraft-lab. Each entry gives its status, the context
that forced a choice, the decision and its consequences. Entries are numbered by topic, not by
date. When a decision changes, add a new entry and mark the old one as superseded instead of
rewriting it.

## 1. One DataJob contract behind the CLI, HTTP and Airflow

**Status:** Accepted.

**Context.** Three kinds of caller (a shell, an HTTP client and Airflow) run three kinds of job
(built-in, plain-JVM and Spark). An entry point per caller and job kind would duplicate parameter
handling, failure handling and result formats, and the copies would drift apart.

**Decision.** Every runnable unit implements `engine.DataJob`. `Runner.registry()` builds one
catalog of the built-in, Spark and plain-JVM jobs. The CLI handles only `list-jobs` and
`serve-api` itself and dispatches every other command name as a job; `serve-api` hands the same
catalog to the HTTP server; Airflow tasks run the same jar (`cli_task` with `java -jar`,
`spark_task` with `spark-submit`) and read the result from `--result-file`. `JobExecutionEngine` is
the single failure boundary.

**Consequences.**

- One result shape (`jobName`, `status`, `message`, `metrics`) serves the CLI's JSON, the HTTP body
  and Airflow's XCom. A failure is a FAILED result with exit code 1 or HTTP 500, not a stack trace.
- Parameter validation belongs to the jobs, so every caller gets the same messages.
- Job names are URL path segments and CLI commands: no `/`, and never `list-jobs` or `serve-api`.
- HTTP runs are synchronous: a long Spark job holds its request, and a run still going 8 s after
  shutdown starts is cut off.
- `Lifecycle` travels with every request, but no job reads it yet.

See [The unified job model](architecture.md#the-unified-job-model) and
[data-contracts.md](data-contracts.md).

## 2. Spark is a provided dependency, run through spark-submit

**Status:** Accepted.

**Context.** Spark and its Hadoop client are large, and `spark-submit` or a cluster supplies them
at runtime anyway. Most of the platform (the API, the built-in and the plain-JVM jobs) needs no
Spark.

**Decision.** `spark-sql` and `spark-hive` use `${spark.scope}`, which is `provided`. The
`bundled-spark` profile switches it to `compile` for a self-contained jar meant only for laptop
experiments. Spark jobs run through `spark-submit`: Airflow's `spark_task` gives `spark-submit`
and the CLI the same `--master`, and the Airflow image ships pyspark 4.2.0 for it. Under plain
`java -jar` a Spark job returns FAILED with "Spark runtime is not on the classpath (missing
<class>); launch Spark jobs with spark-submit".

**Consequences.**

- The shaded jar stays at about 9 MB. The `datacraft/jvm` image that serves the API has no Spark,
  so a Spark job sent to it answers HTTP 500 with that hint (`tests/smoke/container_smoke.sh`
  checks this).
- Under `spark-submit`, classes that Spark provides load from Spark's own jars first by default
  (`spark.driver.userClassPathFirst=false`), so a Spark run uses Spark's Jackson (2.21.2 in Spark
  4.2.0) rather than the one bundled in the jar.
- Test JVMs that embed Spark need the module options `spark-submit` normally adds
  (`spark.test.jvm.args`). CI fails when `SparkPipelineSpec`, which boots a real SparkSession, is
  canceled, and its `dags` job runs the packaged jar through pyspark 4.2.0's `spark-submit`.
- Only local mode is tested; a `spark://` cluster also needs shared data paths and reachable
  driver ports (see the [Airflow guide](../docs/guides/airflow.md)).

## 3. Plain-JVM small-file jobs in datacraft-jobs, confined by DATACRAFT_DATA_ROOT

**Status:** Accepted (added in the 2026-09-25 round).

**Context.** Profiling or checksumming a small file does not justify a Spark session, and the API
image has no Spark at all. The API is unauthenticated, so a file job reachable over HTTP could read
any path the process can read.

**Decision.** The module `datacraft-jobs` (it may depend on `common`, `engine` and `io`; Spark is
banned) holds `csv-profile` and `file-checksum`, registered through `JvmJobs`. When
`DATACRAFT_DATA_ROOT` is set (trimmed; unset or blank means unconfined), the input must lie
strictly inside it and is read only through a `LocalStorageService` rooted at the root's real
path, which refuses a symbolic link below the root and a junction below it whose target lies
outside; the root is never created. `csv-profile` loads a file only up to `maxBytes` (default
64 MiB) and within a quarter of the maximum heap, and otherwise points to the Spark `row-count`
job.

**Consequences.**

- Both jobs run under plain `java -jar`, in the `datacraft/jvm` image and through the API.
- The image sets `DATACRAFT_DATA_ROOT=/opt/datacraft/data`, both stacks mount the data volume there
  read-only for the API, and `serve-api` refuses a non-loopback host without a root (decision 5).
- Confinement is an application guard, not an OS sandbox: hard links, Windows device names and
  concurrent hostile changes are not detected, so the root still needs file-system permissions.
- `row-count`, `spark-version`, `echo` and `noop` are not confined.

## 4. JDK 25 baseline with Spark 4.2.0 and Scala 2.13.18 pinned, Jackson kept on 2.x

**Status:** Accepted.

**Context.** Java 25 is the current LTS release. Spark 4.1 supports only Java 17 and 21; Spark
4.2.0 supports 17, 21 and 25, and its build uses Scala 2.13.18 and Jackson 2.21.2 (the
`scala.version` and `fasterxml.jackson.version` properties of the Spark 4.2.0 parent POM).
Jackson 3 exists as a new major line with new packages, while Spark stays on Jackson 2.

**Decision.** Build and run on JDK 25: `java.version` 25 drives javac's `release` and scalac's
`-release`, the Enforcer requires `[25.0.4.1,)` (the July/August 2026 security fixes; Spark 4.2.0
deprecates Java 25 releases older than 25.0.3), and every image runs a Java 25 runtime. Pin
`spark.version` 4.2.0 and `scala.version` 2.13.18 together. Stay on Jackson 2 (`jackson.version`
2.22.3 through the BOM), and fail the build if `jackson-core` or `jackson-databind` older than
2.22.3, which four published advisories affect, enters it.

**Consequences.**

- Builders and runtimes need JDK 25: the builder image is `maven:3.9.16-eclipse-temurin-25`, the
  optional Spark image is the `java25` variant, and `container_smoke.sh` requires the images' JRE
  to be at least 25.0.4.1.
- A JDK older than 25.0.4.1 fails the build instead of warning. The floor was a warning while the
  workstation JDK lagged; it became a requirement once the workstation, CI's `setup-java` and the
  builder image all resolved to 25.0.4.1, and a new security update raises it again.
- Scala and Spark move together: a Spark upgrade that changes its Scala version changes
  `scala.version` with it.
- Moving the API's JSON writer to Jackson 3 would not remove Jackson 2, which Spark needs and
  which `spark-submit` loads first anyway (decision 2); it would only add a second JSON library.
  Jackson 3 waits until Spark moves.
- The Jackson 2 line still receives fixes: 2.22.3 is the fixed release the Enforcer requires.

## 5. The unauthenticated API stays safe through binding, Origin check and data confinement

**Status:** Accepted; revisit before any exposure beyond the host.

**Context.** The HTTP API has no authentication, and its file jobs read paths given as
parameters. Binding to loopback keeps other hosts out, but not a browser on the same machine: a
page can POST to `127.0.0.1`, including through DNS rebinding.

**Decision.**

- `serve-api` binds `127.0.0.1` by default and refuses (exit 2) a non-loopback `--host` unless
  `DATACRAFT_DATA_ROOT` is set.
- A run request that carries any `Origin` header gets 403 `cross_origin_forbidden`. Browsers send
  `Origin` with a POST; curl, `java.net.http`, Airflow and the healthchecks do not.
- The `datacraft/jvm` image runs as uid 10001 with a root-owned jar and sets
  `DATACRAFT_DATA_ROOT`; both stacks mount the data volume read-only for the API.
- Compose publishes the API on `127.0.0.1` only. `deployment_env.py check`, which `compose-up.sh`
  and `swarm-deploy.sh` run on the effective Compose configuration, rejects any other host address
  and any API volume that is not read-only. The Swarm stack publishes no API port.

**Consequences.**

- Browsers cannot trigger runs; command-line clients and Airflow are unaffected.
- Anything that reaches the port, such as a local process or an SSH tunnel, can still run jobs and
  read, but not change, files under the data root. Put an authenticated gateway in front before
  exposing the API beyond the host.
- In Swarm the API sits on the overlay network `datacraft-net`, which is attachable and
  unencrypted, so only trusted nodes may join the swarm.

## 6. Single-host Docker Compose first; the Swarm template stays pinned to one data node

**Status:** Accepted.

**Context.** The workload is personal and small, and cost matters (decision 7). Airflow tasks hand
CSV and Parquet data to each other through a local volume, so tasks on different hosts would not
see each other's files without shared storage. Swarm stacks can neither build images nor read
`.env`.

**Decision.** The supported deployment is one host with Docker Compose: PostgreSQL 18, Airflow
3.3.2 with LocalExecutor (API server, scheduler, DAG processor, triggerer and a one-shot
initializer) and `datacraft-api`, with conservative defaults (Airflow parallelism 1, one DAG
parsing process, one API server worker, Spark `local[2]`). The Swarm stack (CeleryExecutor and
Redis) is a template for later growth: every service is pinned to `DATACRAFT_DATA_NODE` because
the volumes are node-local, images come from a registry under an exact tag (`swarm-deploy.sh`
rejects an empty tag and `latest`), and `airflow-init` runs as a one-shot service.

**Consequences.**

- There is no high availability. Spreading services across nodes first needs shared storage for
  data and logs, a highly available database, secret management and authenticated networking;
  adding worker replicas alone does not scale out.
- CI runs the Compose path for real (`tests/smoke/compose_smoke.sh`: the full stack, a DAG run and
  a restored metadata backup). The Swarm template is only validated (`docker stack config` and
  static tests) and has not run on a real multi-node swarm.
- Every secret must be set: both stack files fail interpolation without one, and
  `deployment_env.py init` generates them.

Steps: [deployment guide](../docs/guides/deployment.md) (Chinese).

## 7. Cloud platform: Tencent Cloud Lighthouse always-on, Oracle OCI A1 for occasional runs

**Status:** Accepted as the plan for the first deployment. Nothing has been purchased, and no cloud
or ARM host has run the stack yet.

**Context.** The target regions are Tokyo and Singapore. The stack (decision 6) needs one Linux
host; the planning baseline is 8 GB of memory and at least 64 GB of SSD, a starting point rather
than a measured requirement. Two usage patterns matter: always on (730 hours a month on average)
and about 60 hours a month. Prices are public list prices in USD before tax, verified against the
providers' official sources on 2026-09-19, without new-customer credits, promotions or commitment
discounts. Scenario totals keep the host and its disk for the whole month, assume at most 100 GB
of egress a month including backup uploads, and add an example backup of 20 GB-months on
Cloudflare R2 Standard: $0.15 after R2's free 10 GB-months, assuming no other project uses them.

**Decision.**

| Use | Platform | Monthly cost |
| --- | --- | --- |
| Always on | Tencent Cloud Lighthouse (international site), 2 vCPU / 8 GB / 80 GB SSD; same list price in Tokyo and Singapore | $10 plan + $0.15 backup = $10.15 |
| About 60 hours a month | Oracle OCI A1 (ARM), 2 OCPU / 8 GB, no free tier assumed | $1.92 compute + $2.72 for a 64 GB Balanced volume kept all month + $0.15 backup = $4.79 |
| Alternative for an existing AWS habit | AWS Lightsail 8 GB (IPv4, 160 GB SSD, 5 TB transfer) | $44 plan + $0.15 backup = $44.15 |

OCI A1 compute costs $0.032 an hour (2 x $0.01 per OCPU-hour plus 8 x $0.0015 per GB-hour), so
always-on OCI costs $26.23 and the two recommendations break even at about 227.5 hours a month.
Lighthouse's 120 GB plan lists at $14.50 if 80 GB is too small. An OCI free tier could lower the
on-demand cost further, but capacity, the home region, the remaining allowance and an ARM test must
be confirmed first.

EC2, GCP and Azure were compared on compute alone, because their disks, public addresses, egress
and backups depend on choices not made yet. These are subtotals, not totals:

| Machine | Tokyo: 60 h / 730 h | Singapore: 60 h / 730 h | Still to add |
| --- | --- | --- | --- |
| EC2 t4g.large | $5.18 / $63.07 | $5.09 / $61.90 | EBS, public IPv4, egress, backups, possible Unlimited CPU credits |
| GCP e2-standard-2 | $5.16 / $62.75 | $4.96 / $60.35 | persistent disk, external IP, egress, backups |
| Azure B2as v2 (Linux) | $5.88 / $71.54 | $5.66 / $68.91 | 64 GiB E6 LRS disk ($4.80 a month), disk operations, public IP, egress, backups |

Not chosen:

- **Managed Airflow.** AWS MWAA environments and Google's managed Airflow bill a control plane,
  workers, a database and networking. MWAA Serverless bills per task, but its managed task model
  is not directly compatible with the current JVM and `spark-submit` commands. At this scale no
  saving justifies the migration and the maintenance.
- **Cloudflare Workers and Containers.** Workers and Pages cannot run the JVM, PostgreSQL and
  Airflow stack. Containers run Linux containers, but their lifecycle and storage model would need
  the stack redesigned, so they are no drop-in low-cost home for a stateful deployment.
- **R2 beyond backups.** R2 is only a backup target. The project has no S3A connector, so `s3://`
  and `s3a://` paths are not supported Spark data paths, and the Airflow path parameters reject
  them.
- **Commitments.** No Savings Plans, Reserved Instances or committed-use discounts before stable
  utilization is observed.

**Consequences.**

- The deployment scripts are ready, but the purchase must re-check region, SKU, stock, account
  eligibility and taxes: the figures are a price snapshot for comparison, not a quote.
- CI runs the images on Linux amd64 only. OCI A1 is ARM, so it needs its own acceptance run.
- Shutting a machine down does not stop the monthly charge of a retained Lighthouse or Lightsail
  instance; a stopped OCI instance stops compute billing, but its disk is still billed.
- Prices, sources and formulas are maintained in one place: the
  [evaluation report](../docs/reports/evaluation-report.html) (Chinese, with the cost calculator)
  and the [pricing evidence](../docs/reports/pricing-evidence.md) (Chinese).

## 8. All tests under tests/, platform differences as tags instead of skips

**Status:** Accepted (2026-09-26 restructure).

**Context.** Tests used to be scattered across each module's `src/test` tree, the deployment
folder, the Airflow folder and the CI scripts folder; the exact moves are recorded in the
[changelog](../docs/CHANGELOG.md). On Windows, tests whose preconditions were missing reported as
skipped, aborted or canceled (symbolic links need privilege, Hadoop writes need winutils), and the
junction tests ran only on Windows. That noise hid real skips, and the CI floor check counts only
executed tests.

**Decision.** Every test lives under `tests/`, grouped by kind: `tests/jvm/<artifactId>` for each
module's JVM tests (wired through `testSourceDirectory`, so they still run in that module's build),
then `tests/orchestration`, `tests/smoke`, `tests/deploy`, `tests/ci` and `tests/docs`.
Platform-specific tests carry a `posix-only` or `windows-only` tag, and the OS-activated profiles
`windows-host` and `posix-host` exclude the other platform's tag.

**Consequences.**

- Modules contain only production code. The Docker build context excludes `tests/`, so the
  builder image skips test compilation.
- No module reports skipped or canceled tests on either OS. Linux CI executes 256 of the 262 JVM
  tests and a Windows build 248.
- The floors are Linux counts, so a Windows build is below two of them; the floor check runs in CI
  on Linux.

Details: [Testing](build-and-quality.md#testing).

## 9. Repository layout by responsibility

**Status:** Accepted (2026-09-26 restructure).

**Context.** The eight modules sat side by side at the root next to the documentation, deployment
and orchestration folders. Documentation was spread over root files, `docs/`, a Chinese engineering
report in HTML and two root HTML reports, and tests were scattered (decision 8). Module boundaries
were documented, but only the engine's was enforced.

**Decision.** Group the modules by responsibility under `modules/` (`core`, `io`, `processing`,
`interfaces`) without changing artifactIds or packages. Move the user documentation to `docs/`
(`README.md`, `CHANGELOG.md`, `guides/`, `reports/`), the English design documents to `design/` and
every test to `tests/`, and keep only build entry points and tool configuration at the root. The
files were moved with `git mv`. Give every module except the CLI an Enforcer rule for its allowed
dependencies.

**Consequences.**

- Build commands select modules by artifactId (`-pl :datacraft-cli`), module POMs reach the parent
  through `../../../pom.xml`, and the CLI jar is
  `modules/interfaces/datacraft-cli/target/datacraft-cli.jar`; CI, the scripts,
  `Dockerfile.build` and the documents use these paths.
- The root has no README; GitHub shows `docs/README.md` on the repository page instead.
- `.dockerignore` excludes `docs/`, `design/` and `tests/`, keeping them out of the image build
  context.
- The per-module Enforcer rules back the grouping: a dependency that crosses responsibilities
  fails the build instead of only contradicting the documentation.

Layout and rules: [Repository layout](architecture.md#repository-layout) and
[Module responsibilities](architecture.md#module-responsibilities).

## 10. CI/CD logic lives in cicd/; the workflow only orchestrates

**Status:** Accepted (2026-09-26 restructure).

**Context.** The workflow carried most of the pipeline as inline shell: the Maven Wrapper checksum
check, the Spark-suite, jar and exit-code checks, the pinned-action check and the stack-file
validation. That logic ran only on a GitHub runner, ShellCheck never saw it, and it could not be
reproduced when the Actions quota was exhausted. GitHub reads workflows only from
`.github/workflows`, so the workflow file itself cannot move.

**Decision.** `.github/workflows/ci.yml` keeps the triggers, permissions, runners, toolchain setup
(SHA-pinned actions) and job order, and each step runs one command: a script in `cicd/` or a test
entry point in `tests/`. `cicd/` groups the scripts by the job that runs them: `build/` (wrapper
checksum, test-count floors, Spark suite, CLI jar checks and the JSch probe), `airflow/` (the
constrained Airflow install and the security floor), `lint/` (shell syntax, ShellCheck, pinned
actions) and `stacks/` (Compose and Swarm validation), with shared helpers in `cicd/lib.sh`. The
tests of these scripts stay in `tests/ci`.

**Consequences.**

- The checks run outside GitHub Actions (Linux, macOS, WSL or Git Bash for the build checks,
  Docker for the stack check) and report through `::error::` annotations only on a runner.
- ShellCheck covers every script at warning severity, the pipeline scripts included.
- `tests/ci/test_workflow.py` fails when a `run:` step turns into a multi-line block, names a
  script that does not exist, or when a file in `cicd/` is used by nothing.
- `cicd/airflow/install-airflow.sh` derives the constraints file from the running Python, as the
  Airflow image does, so the CI Python version and the constraints file cannot disagree.
- `cicd/stacks/check-stack-files.sh` refuses to run while `deploy/compose/.env` exists, because the
  Compose file reads that path and the check must never overwrite real secrets.

Details: [CI gates](build-and-quality.md#ci-gates) and the
[development guide](../docs/guides/development.md#ci).
