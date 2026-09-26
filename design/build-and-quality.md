# Build and quality

The JVM code is one Maven reactor driven by the root [`pom.xml`](../pom.xml). `./mvnw verify` is
the single local gate; GitHub Actions ([`ci.yml`](../.github/workflows/ci.yml)) is the
authoritative one. This document explains how the build is organised and why. Day-to-day commands
are in the [development guide](../docs/guides/development.md).

## Principles

- The root POM is the only place that manages versions, plugin configuration and quality gates.
  Module POMs declare their coordinates, their dependencies (third-party ones without versions),
  their `enforce-module-boundaries` rule and only the plugins specific to the module: Scala
  compilation and ScalaTest in `datacraft-spark` and `datacraft-cli`, shading in `datacraft-cli`.
  Module-level switches are properties, such as `surefire.skip`.
- Convention over configuration: Maven's default lifecycle stays in charge; the POM pins versions
  and declares only what a mixed Java/Scala/Spark project needs beyond the defaults.
- Every check that can fail does so in `verify`, and every warning is fixed at its cause, turned
  into a failure or, when a third-party tool emits it, removed by configuration (see
  [Zero-warning policy](#zero-warning-policy)).

## Reactor and modules

The root project `com.example.datacraft:datacraft-lab:0.1.0-SNAPSHOT` (packaging `pom`) aggregates
eight modules, nine reactor projects in all. They are declared in an order that already satisfies
the dependency graph; Maven sorts the reactor by dependencies anyway.

| Order | Module | Directory | Sources | Tests |
| --- | --- | --- | --- | --- |
| 1 | `datacraft-common` | `modules/core/datacraft-common` | Java | JUnit |
| 2 | `datacraft-config` | `modules/core/datacraft-config` | Java | JUnit |
| 3 | `datacraft-io` | `modules/io/datacraft-io` | Java | JUnit |
| 4 | `datacraft-engine` | `modules/core/datacraft-engine` | Java | JUnit |
| 5 | `datacraft-jobs` | `modules/processing/datacraft-jobs` | Java | JUnit |
| 6 | `datacraft-spark` | `modules/processing/datacraft-spark` | Scala | ScalaTest |
| 7 | `datacraft-api` | `modules/interfaces/datacraft-api` | Java | JUnit |
| 8 | `datacraft-cli` | `modules/interfaces/datacraft-cli` | Scala | ScalaTest |

Each module POM names the root as its parent with `<relativePath>../../../pom.xml</relativePath>`.
Directories are grouped by responsibility, so select modules by artifactId:
`./mvnw -pl :datacraft-cli -am package`. What each module owns and may depend on is in
[Module responsibilities](architecture.md#module-responsibilities).

## Source and test layout

Production sources stay in the Maven default places, `src/main/java` or `src/main/scala`. Tests
live outside the modules, in one tree per artifactId, and still run in their module's build:

| Content | Location | Wiring in the root POM |
| --- | --- | --- |
| Java tests | `tests/jvm/<artifactId>/java` | `<testSourceDirectory>${datacraft.tests.dir}/java</testSourceDirectory>` |
| Scala tests | `tests/jvm/<artifactId>/scala` | scala-maven-plugin derives `${project.build.testSourceDirectory}/../scala` |
| Module test resources | `tests/jvm/<artifactId>/resources` (optional; none today) | first `<testResource>` |
| Shared test resources | `tests/jvm/resources` | second `<testResource>` |

The properties behind the table are `datacraft.root`, set to
`${maven.multiModuleProjectDirectory}` (the directory that holds `.mvn`), and
`datacraft.tests.dir`, set to `${datacraft.root}/tests/jvm/${project.artifactId}`, so every module
resolves the same tree wherever the build starts. The shared resources are
`logging.properties` and `log4j2-test.properties` (see [Test JVM options](#test-jvm-options)).

Consequences elsewhere in the build:

- Checkstyle reads `src/main/java` and `${project.build.testSourceDirectory}`, so it covers the
  moved Java tests. Spotless checks module sources in each module and `tests/**` from the root
  project (see [Formatting](#formatting)).
- `.dockerignore` excludes `tests/`, `docs/` and `design/`, so the builder image packages with
  `-Dmaven.test.skip=true`, which skips compiling the tests as well as running them. Tests run
  locally and in CI.

## Version management

- `properties` in the root POM are the single version matrix. `dependencyManagement` pins every
  third-party dependency; modules refer to project modules at `${project.version}`.
- Jackson is imported as a BOM (`jackson-bom` at `${jackson.version}`), which aligns every Jackson
  artifact, including transitive ones.
- `pluginManagement` and the root `build/plugins` pin every plugin, and the Enforcer rejects a
  plugin without a version (see [Enforcer](#enforcer)).
- `project.build.outputTimestamp` is fixed (`2026-01-01T00:00:00Z`), so archive entries get
  reproducible timestamps; sources and reports are UTF-8.

| Component | Property or place | Version |
| --- | --- | --- |
| Java release | `java.version`, used as `maven.compiler.release` | 25 |
| Scala | `scala.version` (`scala.binary.version` 2.13) | 2.13.18 |
| Spark (`provided` by default) | `spark.version`, `spark.scope` | 4.2.0 |
| Jackson BOM | `jackson.version` | 2.22.3 |
| JSch (mwiede fork) | `jsch.version` | 2.28.7 |
| scopt | `scopt.version` | 4.1.0 |
| JUnit Jupiter (test) | `junit.jupiter.version` | 6.1.3 |
| ScalaTest (test) | `scalatest.version` | 3.2.20 |
| Apache MINA SSHD `sshd-sftp`, `sshd-mina` (test) | `sshd.version` | 2.19.0 |
| SLF4J API and `slf4j-nop` (test) | `slf4j.version` | 2.0.20 |
| google-java-format | `google.java.format.version` | 1.36.1 |
| scalafmt | Spotless configuration and `.scalafmt.conf` | 3.11.5 |
| Checkstyle engine | `checkstyle.version` | 14.1.0 |
| Maven | `.mvn/wrapper/maven-wrapper.properties` | 3.9.16 |
| Maven Wrapper | `.mvn/wrapper/maven-wrapper.properties` | 3.3.4 |

| Plugin | Version |
| --- | --- |
| maven-clean-plugin | 3.5.0 |
| maven-resources-plugin | 3.5.0 |
| maven-compiler-plugin | 3.16.0 |
| scala-maven-plugin | 4.9.10 |
| maven-surefire-plugin | 3.6.0 |
| scalatest-maven-plugin | 2.2.0 |
| maven-jar-plugin | 3.5.1 |
| maven-shade-plugin | 3.6.2 |
| maven-install-plugin | 3.2.0 |
| maven-deploy-plugin | 3.2.0 |
| maven-site-plugin | 3.22.0 |
| maven-dependency-plugin | 3.11.0 |
| maven-enforcer-plugin | 3.6.3 |
| spotless-maven-plugin | 3.10.3 |
| maven-checkstyle-plugin | 3.6.0 |

The install, deploy and site plugins are pinned because the Enforcer checks plugins up to the
`deploy` and `site` phases; maven-dependency-plugin serves the builder image's
`dependency:go-offline` step. Spark constrains two choices: `scala.version` is the Scala version
Spark 4.2.0 is built with (2.13.18), and Jackson stays on the 2.x line that Spark uses (decision 4
in [decisions.md](decisions.md)).

## Compiler settings

- **javac** (maven-compiler-plugin): `release` 25 through `maven.compiler.release`, `parameters`
  (method parameter names in class files), `showWarnings`, `-Xlint:all` and `failOnWarning`. Every
  lint category is on and any warning fails compilation.
- **scalac** (scala-maven-plugin, `compile` and `testCompile`): `-release:25` from the same
  property, `-deprecation`, `-feature`, `-unchecked`, `-Xlint` and `-Werror`, so any Scala warning
  fails too; `recompileMode` is `incremental`.

Code that would warn is written differently rather than suppressed: for example,
`DataCraftException` declares an `@Serial` `serialVersionUID`, and `SftpClient` hands the password
to JSch as bytes and wipes its copy. The only suppression in the JVM code is an
`@SuppressWarnings("unchecked")` on a test helper in `JobExecutionEngineTest` that throws a checked
exception the way Scala code can.

## Formatting

Spotless (`spotless-maven-plugin`, goal `check`, bound to `verify`) runs in every reactor project
with UTF-8 encoding and UNIX line endings:

- **Java**: google-java-format 1.36.1 with `removeUnusedImports`, on `src/main/java/**/*.java` and
  `tests/**/*.java`.
- **Scala**: scalafmt 3.11.5 with [`.scalafmt.conf`](../.scalafmt.conf) (dialect `scala213`,
  `maxColumn` 100, `align.preset = more`, rewrite rules `RedundantBraces`, `RedundantParens` and
  `SortImports`, asterisk docstrings), on `src/main/scala/**/*.scala` and `tests/**/*.scala`.
- **Other files**: trailing whitespace removed and a final newline required for `.gitattributes`,
  `*.md`, `*.xml`, `*.yml`, `*.yaml` and `*.properties` in the project directory, and for
  `config/**/*.xml`, `docs/**/*.css`, `docs/**/*.html`, `docs/**/*.md`, `design/**/*.md`,
  `orchestration/**/*.py`, `tests/**/*.py` and `tests/**/*.properties`.

The patterns are relative to each project's base directory. A module therefore checks its own
sources and POM, and the root project checks the root files, `config/`, `docs/`, `design/`,
`orchestration/` and `tests/`. Files no pattern matches, such as those under `deploy/` and
`.github/` and the shell and JavaScript tests under `tests/`, rely on
[`.editorconfig`](../.editorconfig): UTF-8, LF, a final newline, no trailing whitespace, 4-space
indents, 2-space indents for Java and Scala (as Spotless formats them), tab-indented Makefile
recipes and CRLF for `*.cmd` and `*.bat`, which [`.gitattributes`](../.gitattributes) also checks
out with CRLF (everything else is LF).

`./mvnw spotless:apply` (or `make format`) rewrites the files instead of checking them.

## Checkstyle

maven-checkstyle-plugin 3.6.0 runs the Checkstyle engine pinned through `checkstyle.version`
(14.1.0) as a plugin dependency. The plugin's default engine (9.3) cannot parse Java 21 and 25
syntax such as record patterns, guarded `case ... when` labels and flexible constructor bodies.
`ModernSyntaxFixtureTest` (`tests/jvm/datacraft-common`) keeps that syntax in the build, so an
engine that falls behind fails `verify` on the fixture instead of on the first production class
that uses the syntax.

The rules in [`config/checkstyle/checkstyle.xml`](../config/checkstyle/checkstyle.xml) are
`NewlineAtEndOfFile`, `AvoidStarImport` and `UnusedImports`. The `check` goal runs at `verify` over
`src/main/java` and the module's test source directory, prints to the console and fails the build
on a violation. Scala sources are covered by scalac (`-Xlint`, `-Werror`) and scalafmt instead.

## Enforcer

maven-enforcer-plugin runs two executions, both at `validate`:

1. **`enforce-build-environment`** (root, inherited by every module):
   - `requireJavaVersion [25.0.4.1,)`: "datacraft-lab builds with JDK 25.0.4.1 or newer and targets
     Java 25 bytecode: 25.0.4.1 carries the July/August 2026 security fixes, and Spark 4.2.0
     deprecates Java 25 releases older than 25.0.3." 25.0.4.1 is the out-of-band update for the
     OpenJDK vulnerability advisory of 2026-08-18; CI's `setup-java` and the builder image resolve
     to it or newer. The rule compares the fourth version component: `[25.0.4.2,)` rejects
     25.0.4.1.
   - `requireMavenVersion [3.9.0,)`.
   - `requirePluginVersions` for every plugin up to the `clean`, `deploy` and `site` phases, with
     `LATEST`, `RELEASE` and SNAPSHOT versions banned.
   - `banDuplicatePomDependencyVersions` and `requireNoRepositories` (no POM may declare its own
     repositories).
   - `bannedDependencies`: `jackson-core` and `jackson-databind` older than 2.22.3, which are
     affected by GHSA-wv8q-qhhj-9h54 (CVE-2026-91776), GHSA-cxp5-3px4-pw24 (CVE-2026-91777),
     GHSA-p6pp-m3f8-5c89 (CVE-2026-89407) and GHSA-7hhh-6rmp-j9qf (CVE-2026-89425). The two
     artifacts are named explicitly because `jackson-annotations` is versioned 2.22, without a
     patch number, which sorts below 2.22.3 and would fall under a group-wide ban.
2. **`enforce-module-boundaries`** (each module POM except `datacraft-cli`): the allow-list of
   project modules and the Spark ban described in
   [Module responsibilities](architecture.md#module-responsibilities).

## Testing

### Frameworks and wiring

| Modules | Framework | Runner |
| --- | --- | --- |
| common, config, io, engine, jobs, api | JUnit Jupiter 6.1.3 | maven-surefire-plugin 3.6.0 |
| spark, cli | ScalaTest 3.2.20 | scalatest-maven-plugin 2.2.0; `surefire.skip=true` |

- **Surefire** uses `argLine ${test.jvm.args}`, `excludedGroups ${test.excluded.tags}`,
  `failIfNoTests ${surefire.failIfNoTests}` (true), `skip ${surefire.skip}`, full stack traces
  (`trimStackTrace` false) and the class path (`useModulePath` false).
- **ScalaTest** uses the same `argLine` (`datacraft-spark` prepends `${spark.test.jvm.args}`) and
  `tagsToExclude ${test.excluded.tags}`, writes JUnit XML to `target/surefire-reports` like
  Surefire, and a text report `target/surefire-reports/<artifactId>-scalatest.txt`.
- A module whose JUnit tests all vanish, through a renamed class or a provider mismatch, fails
  instead of passing green (`failIfNoTests`). The ScalaTest modules skip Surefire: they have no
  JUnit classes, and Surefire cannot filter tags without a JUnit engine. An ad-hoc multi-module
  `-Dtest=...` run therefore needs `-Dsurefire.failIfNoTests=false` and
  `-Dsurefire.failIfNoSpecifiedTests=false` for the modules without a matching test.
- scalatest-maven-plugin passes when it finds no suites, and `failIfNoTests` only catches a module
  with zero tests, so CI also enforces per-module floors (see
  [Test inventory and floors](#test-inventory-and-floors)).

### Platform tags instead of skipped tests

Some tests need one platform: symbolic links without privilege, `?` in file names and Hadoop local
writes (which need winutils on Windows) need POSIX; NTFS junctions need Windows. They are tagged
rather than skipped:

- JUnit: `@Tag("posix-only")` and `@Tag("windows-only")`; ScalaTest: the `PosixOnly` tag
  (`posix-only`, defined in `tests/jvm/datacraft-spark/.../FileFixtures.scala`).
- The profiles `windows-host` (activated on the OS family `windows`) and `posix-host` (any other
  family) set `test.excluded.tags` to the other platform's tag, which Surefire (`excludedGroups`)
  and ScalaTest (`tagsToExclude`) exclude. Excluded tests are not reported, so a normal build shows
  no skipped, aborted or canceled tests on either OS, and Linux CI runs every `posix-only` test.
- Each tagged test keeps its own guard, an OS condition (`@EnabledOnOs`, `@DisabledOnOs`) or a
  helper that aborts or cancels on Windows when the precondition is missing, so running it
  directly, for example from an IDE without the profile, still reports why instead of failing.
- `SparkPipelineSpec` also cancels on a host whose JVM cannot open NIO selectors; CI treats a
  canceled Spark suite as a failure (see [CI gates](#ci-gates)).

### Test JVM options

`test.jvm.args` applies to every test JVM:

- `-Djdk.net.unixdomain.tmpdir=${project.build.directory}`: NIO selectors create an AF_UNIX wakeup
  socket in that directory. Some Windows profile TEMP trees cannot host one, which broke the
  `HttpServer` and Spark tests, so the module's build directory is used on every OS.
- `-Djava.util.logging.config.file=${datacraft.root}/tests/jvm/resources/logging.properties`: a
  java.util.logging configuration without handlers. Tests assert the failures they provoke, so the
  expected `ERROR` records are not printed.
- `-Ddatacraft.test.log.level=${datacraft.test.log.level}` (default `off`): the root level of
  `tests/jvm/resources/log4j2-test.properties`, the Log4j 2 configuration of the test JVMs that
  carry Spark. `./mvnw test -Ddatacraft.test.log.level=info` brings Spark's log output back.

`spark.test.jvm.args` (added in `datacraft-spark`) holds the options a JVM that embeds Spark needs:
the `--add-opens` set plus `--sun-misc-unsafe-memory-access=allow`,
`--enable-native-access=ALL-UNNAMED`, `-Dio.netty.tryReflectionSetAccessible=true` and
`-XX:+IgnoreUnrecognizedVMOptions`. They are a subset of the options Spark 4.2's launcher
(`org.apache.spark.launcher.JavaModuleOptions`) applies under `spark-submit`, without
`--add-opens` for `sun.security.action`, which JDK 25 no longer has.

`SftpClientTest` runs against an embedded Apache MINA SSHD server (`sshd-sftp`) on the
selector-based `sshd-mina` transport: the default NIO2 transport raced its executor shutdown
against Windows IOCP completions and printed uncaught errors. SSHD logs through SLF4J, which the
tests bind to `slf4j-nop`.

### Test inventory and floors

The JVM suites have 262 test cases.
[`cicd/build/check_test_counts.py`](../cicd/build/check_test_counts.py) reads each module's
`target/surefire-reports/TEST-*.xml`, counts executed tests (skipped, aborted and canceled ones do
not count) and fails when a module is below its floor. Floors are Linux counts and minimums: adding
tests needs no change, removing tests needs a lower floor in the same commit. A test in `tests/ci`
checks that the script's module map matches the POM's `<modules>`.

| Module | Linux executes (CI floor) | Windows executes | Tagged for the other OS |
| --- | --- | --- | --- |
| `datacraft-common` | 7 | 7 | none |
| `datacraft-config` | 12 | 12 | none |
| `datacraft-io` | 56 | 57 | 6 `windows-only`, 5 `posix-only` |
| `datacraft-engine` | 31 | 31 | none |
| `datacraft-jobs` | 67 | 65 | 2 `posix-only` |
| `datacraft-spark` | 48 | 41 | 7 `posix-only` |
| `datacraft-api` | 12 | 12 | none |
| `datacraft-cli` | 23 | 23 | none |
| Total | 256 | 248 | |

A Windows build is therefore below the floors of `datacraft-jobs` and `datacraft-spark` by
design; CI runs the floor check on Linux only.

### Python and Node suites

| Suite | Location | Needs | Runs in |
| --- | --- | --- | --- |
| DAG contracts | `tests/orchestration` | Airflow 3.3.2 and providers | CI `dags`; `make dags` |
| Real pipelines | `tests/smoke/airflow_runtime_smoke.py` | Airflow 3.3.2, pyspark 4.2.0, the jar | CI `dags` and `containers` |
| Images and stack | `tests/smoke/container_smoke.sh`, `compose_smoke.sh` | Docker; `compose_smoke.sh` refuses to run outside CI | CI `containers` |
| Deployment scripts and templates | `tests/deploy` | Python standard library | CI `scripts`; `make test-scripts` |
| CI/CD scripts and workflow structure | `tests/ci` | Python standard library; Bash for the pinning check (skipped on Windows) | CI `scripts`; `make test-scripts` |
| Documentation consistency | `tests/docs/test_docs_consistency.py` | Python standard library | CI `scripts`; `make test-scripts` |
| Cost calculator | `tests/docs/cloud-costs.test.cjs` | Node | CI `scripts`; `make test-scripts` |

## Packaging

Every module jar gets the default Implementation and Specification manifest entries from
maven-jar-plugin. `datacraft-cli` also runs maven-shade-plugin at `package` and produces the
executable fat jar `modules/interfaces/datacraft-cli/target/datacraft-cli.jar` (`finalName`
`datacraft-cli`, no dependency-reduced POM):

- **Manifest**: each dependency's `META-INF/MANIFEST.MF` is filtered out, so the manifest is the
  module's own plus the entries the `ManifestResourceTransformer` sets: `Main-Class`
  (`com.example.datacraft.cli.Runner`), `Multi-Release: true`, `Implementation-Title`,
  `Implementation-Version` and `Build-Jdk-Spec`. `Multi-Release` keeps the `META-INF/versions`
  classes of multi-release dependencies (JSch's Ed25519, X25519 and ML-KEM support, Jackson)
  active.
- **Module descriptors and signatures**: `module-info.class` and
  `META-INF/versions/*/module-info.class` are excluded, because a dependency's module descriptor
  must not describe the fat jar; so are signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`).
- **Notices and licenses**: the `ApacheNoticeResourceTransformer` merges the dependencies'
  `META-INF/NOTICE` files (jackson-core's adds third-party notices), and the byte-identical Apache
  License 2.0 texts of `jackson-core` and `jackson-databind` are excluded so that
  `jackson-annotations`' copy is the one `META-INF/LICENSE`.
- **Service files** are merged by the `ServicesResourceTransformer`, and `reference.conf` files are
  appended.

Spark is `provided`, so the jar is about 9 MB. The `bundled-spark` profile sets `spark.scope` to
`compile` and produces a large self-contained jar with the same shade configuration, meant for
laptop experiments without `spark-submit`. CI checks the shaded jar in the `build` job (see
[CI gates](#ci-gates)).

## Maven Wrapper and entry points

- The Apache Maven Wrapper 3.3.4 is script-only (`distributionType=only-script`): the repository
  commits `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties`, and no
  `maven-wrapper.jar`. The scripts download Maven 3.9.16 (`bin.zip`) and verify it against
  `distributionSha256Sum`; a mismatch stops with "Failed to validate Maven distribution SHA-256".
- The check runs only when the distribution is downloaded, that is when `MAVEN_USER_HOME`
  (default `~/.m2`) has no matching `wrapper/dists` entry; an existing one is reused. On Linux and
  macOS the script needs `unzip`: without it the script downloads the `.tar.gz`, whose checksum
  does not match the pinned zip, and stops with the same error. `mvnw.cmd` runs its download and
  checksum logic in `powershell.exe`.
- [`.mvn/jvm.config`](../.mvn/jvm.config) passes `--enable-native-access=ALL-UNNAMED` and
  `--sun-misc-unsafe-memory-access=allow` to the JVM that runs Maven. Without them, JDK 25 prints a
  warning when code calls restricted native methods or the memory-access methods of
  `sun.misc.Unsafe`, and code running inside the Maven JVM does so (Spotless and the zinc compiler
  of scala-maven-plugin use `sun.misc.Unsafe`). Forked test JVMs do not read this file; they get
  their options from the test properties above.
- The builder image `deploy/docker/Dockerfile.build` uses `maven:3.9.16-eclipse-temurin-25`, the
  wrapper's Maven version; a test in `tests/deploy` keeps the two equal.
- The [`Makefile`](../Makefile) targets call `./mvnw` (`make MVN=mvn ...` switches to a Maven on
  `PATH`), and `deploy/scripts/build-jar.sh` and `build.ps1` prefer the wrapper over a Maven on
  `PATH`.

## Python type checking

[`pyrightconfig.json`](../pyrightconfig.json) type-checks the Python in `orchestration/`, `deploy/`,
`tests/` and `cicd/` (excluding `__pycache__` and `target`) in pyright's `standard` mode, as Python
3.12 on Linux, the platform everything here runs on. Airflow, its providers, paramiko, pyspark and
packaging exist only in the Airflow environment, so the execution environments for
`orchestration/airflow/dags`, `tests/orchestration`, `tests/smoke` (which see the DAG helpers
through `extraPaths`) and `cicd/airflow` do not report missing imports; CI's `dags` job exercises
those imports with Airflow installed.

CI's `scripts` job runs `npx --yes pyright@1.1.414 --warnings`, and `make typecheck` runs the same
command (the `PYRIGHT` variable). Without `--warnings` pyright exits 0 when it reports only
warnings; with it, any error or warning fails the gate, so the Python code is held at zero
diagnostics.

## CI gates

GitHub reads workflows only from `.github/workflows`, so `ci.yml` stays there, but it only
orchestrates: triggers, permissions, runners, toolchain setup (the pinned actions) and job order.
Every check a job runs is a script in `cicd/`, grouped by the job that runs it (`build/`,
`airflow/`, `lint/`, `stacks/`, with shared helpers in `cicd/lib.sh`), or a test entry point in
`tests/`. The scripts therefore run and can be reviewed outside GitHub Actions: on a runner their
errors become `::error::` annotations, elsewhere they go to standard error.
[`tests/ci/test_workflow.py`](../tests/ci/test_workflow.py) keeps the split: a `run:` step must be
a single command, the scripts it names must exist, every file in `cicd/` must be used by the
workflow or by another `cicd/` script, and tests stay out of `cicd/`.

The workflow runs on pushes and pull requests to `main` and on demand, with read-only repository
permissions; a newer run on the same ref cancels the older one. Every job runs on `ubuntu-24.04`,
and every action is pinned to a full commit SHA with the release in a comment: `checkout` v7.0.1,
`setup-java` v6.0.1, `setup-python` v7.0.0, `setup-node` v7.0.0, `upload-artifact` v7.0.1 and
`download-artifact` v8.0.1. The `scripts` job fails on any non-local `uses:` that is not pinned to
a SHA (`cicd/lint/check-actions-pinned.sh`).

| Job | What it gates |
| --- | --- |
| `build`: Build & verify (JDK 25) | Temurin 25; `cicd/build/check-maven-wrapper.sh`: the wrapper must refuse a Maven download with a wrong SHA-256 (a copy with a zeroed `distributionSha256Sum` and an empty `MAVEN_USER_HOME`), and no `maven-wrapper.jar` may be committed; `./mvnw -B -ntp verify`; the test floors (`cicd/build/check_test_counts.py`); `cicd/build/check-spark-suite.sh`: the Spark suite must have run `SparkPipelineSpec` with `canceled 0`; the jar and exit-code checks below. |
| `dags`: Airflow contracts and real pipelines (3.3.2) | Python 3.13, the version the `apache/airflow:3.3.2` image runs; `cicd/airflow/install-airflow.sh`: `apache-airflow==3.3.2` and the providers under the official `constraints-3.3.2` file for the running Python (the script derives the version, as `Dockerfile.airflow` does, and a test in `tests/docs` keeps it from being fixed), `pip check`, then pyspark 4.2.0; `cicd/airflow/check_security_floor.py`: apache-airflow 3.3.2 and FAB provider 3.9.0 at least; `tests/orchestration`; `tests/smoke/airflow_runtime_smoke.py` against the jar built by `build`, with pyspark 4.2.0's `spark-submit`. |
| `scripts`: Script, documentation and type checks | `cicd/lint/check-actions-pinned.sh`; Python 3.13 and Node 24; `cicd/lint/check-shell-scripts.sh`: `bash -n` and ShellCheck at warning severity on every script in `cicd/`, `deploy/scripts/` and `tests/smoke/`; `tests/deploy`, `tests/ci` and `tests/docs`; `node --test tests/docs/cloud-costs.test.cjs`; pyright 1.1.414 with `--warnings`. |
| `compose`: Compose / Swarm file validation | `cicd/stacks/check-stack-files.sh`: `docker compose config` and `docker stack config` with test-only secrets; for each required secret, interpolation must fail when it is missing. Nothing is built or deployed; the script refuses to run while `deploy/compose/.env` exists, because it writes that file from `.env.example`. |
| `containers`: Build and smoke-test runtime images | `tests/smoke/container_smoke.sh`: builds the images; the build context must exclude `.env` and `backups/`; both runtime images carry the freshly pulled Temurin JRE, at least 25.0.4.1; the API runs as non-root, cannot modify its jar and answers `spark-version` with HTTP 500 FAILED; Airflow and FAB floors and `pip check` in the Airflow image; a fresh data volume is writable by an Airflow task and read-only for the API; the real pipelines inside the image. `tests/smoke/compose_smoke.sh`: the full Compose stack with generated secrets, a triggered `datacraft_engine_jobs` run through the scheduler and LocalExecutor, and a `pg_dump` backup restored into a separate database that must contain the successful run. |

Checks of the shaded jar in the `build` job:

- `cicd/build/smoke-cli-jar.sh`: `list-jobs`, `echo` and `noop` run under plain `java -jar`, which
  proves the jar is runnable and that these commands need no Spark; `csv-profile` with
  `expectedRows=2` and `file-checksum` with the file's `sha256sum` succeed on a sample CSV, and
  `csv-profile` with `expectedRows=3` exits 1.
- `cicd/build/check-jar-contents.sh`: the manifest contains `Multi-Release: true`, no entry is a
  `module-info.class`, and [`JschAlgorithmsProbe.java`](../cicd/build/JschAlgorithmsProbe.java),
  run from the jar, signs and verifies with Ed25519 and initialises X25519 key agreement; the
  bundled `jackson-core` and `jackson-databind` versions equal `jackson.version` in `pom.xml`.
- `cicd/build/check-cli-exit-codes.sh`: `spark-version` under plain `java -jar` exits 1 and still
  writes a FAILED `--result-file`; an unknown command, a malformed `--param` and a missing
  `--config` exit 2; none of them may end with an uncaught exception.

On failure the job uploads the test reports; on success it shares the verified jar with the
`dags` job for one day.

## Zero-warning policy

A warning is fixed at its cause, made fatal or, when a third-party tool emits it, removed by
configuration, so a new one stands out. What produces warnings, and how each source is kept at
zero:

| Source | Handling | Enforced by |
| --- | --- | --- |
| javac | `-Xlint:all`, `failOnWarning` | the build fails |
| scalac | `-deprecation -feature -unchecked -Xlint -Werror` | the build fails |
| Formatting and imports | Spotless `check`, Checkstyle | the build fails |
| Build environment, plugin versions, dependencies | Enforcer rules | the build fails, including on a JDK older than 25.0.4.1 |
| Shading | manifests filtered, NOTICE merged, one LICENSE, services merged, no `module-info.class` | configuration; CI checks the manifest and `module-info.class` |
| JVM warnings in the Maven JVM | `.mvn/jvm.config` | configuration |
| JVM warnings in Spark test JVMs | `spark.test.jvm.args` | configuration |
| Log noise from provoked failures | handler-less java.util.logging, Log4j 2 level `off`, `slf4j-nop`, the MINA transport for the SFTP test server | configuration |
| Skipped and canceled tests | platform tags and host profiles | CI counts executed tests only and fails a canceled Spark suite |
| Python | pyright configuration | CI and `make typecheck` fail on any pyright error or warning (`--warnings`) |
| Shell | ShellCheck at severity `warning` over every script; only its informational notes remain | CI; `make lint` |
| Pipeline logic | multi-line `run:` blocks are not allowed in the workflow; logic lives in `cicd/` | `tests/ci/test_workflow.py` |

A clean `./mvnw verify` on JDK 25.0.4.1 prints no Maven or JVM warning at all.

## Related documents

- [architecture.md](architecture.md): modules, boundaries and the runtime model.
- [decisions.md](decisions.md): why the build baseline and the test layout look like this.
- [Development guide](../docs/guides/development.md): building and testing day to day.
