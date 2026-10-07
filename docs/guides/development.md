# Development guide

How to build and test datacraft-lab, how the tests are organized, and what CI checks. Running jobs
is covered in [the usage guide](usage.md); the reasoning behind the build rules is in
[build and quality](../../design/build-and-quality.md).

## Prerequisites

- **JDK 25.0.4.1 or newer.** The build targets Java 25 bytecode, and the Enforcer fails it on an
  older JDK: "datacraft-lab builds with JDK 25.0.4.1 or newer and targets Java 25 bytecode:
  25.0.4.1 carries the July/August 2026 security fixes, and Spark 4.2.0 deprecates Java 25 releases
  older than 25.0.3." On Windows, set `$env:JAVA_HOME` (for example `C:\path\to\jdk-25`) if Maven
  does not find it.
- **Maven, through the wrapper.** `./mvnw` (Linux, macOS, WSL) and `mvnw.cmd` (Windows; it runs its
  logic in PowerShell) are Maven Wrapper 3.3.4 scripts; no wrapper jar is committed. On first use
  they download Maven 3.9.16 into `~/.m2/wrapper/dists` and verify it against the SHA-256 pinned in
  `.mvn/wrapper/maven-wrapper.properties`; an existing download there is reused without a new check.
  The Unix script needs `unzip` and `sha256sum` or `shasum`, and downloads with `wget` or `curl`,
  falling back to a small Java program. A Maven 3.9 or newer on `PATH` also works
  (`make MVN=mvn ...`). `.mvn/jvm.config` passes `--enable-native-access=ALL-UNNAMED` and
  `--sun-misc-unsafe-memory-access=allow` to the JVM that runs Maven.
- **Docker Engine with Compose v2**, only for building images, deploying, and the container smoke
  tests.
- **Python 3.12 or newer.** CI runs the Python suites and Airflow on 3.13, the Python of the
  `apache/airflow:3.3.2` image; its `build` job runs the two Python checks in `cicd/build` on the
  runner's own Python 3.12. The deployment, CI and documentation suites need only the standard
  library.
- **Linux or WSL** for Airflow, which does not run on Windows, and for the deployment tests, several
  of which run the Bash scripts or need POSIX terminals and file modes.
- **Node.js** (CI uses 24) for the cost calculator test and for running pyright through `npx`.
- **ShellCheck 0.11.0**, optionally, to repeat CI's shell checks with `make lint` (every finding
  fails, over every shell script git knows). `cicd/lint/install-shellcheck.sh` installs that
  release into the active Python environment, as CI does.

## Build and test

```bash
./mvnw -B -ntp verify                                       # the full gate, as CI runs it
./mvnw -B -ntp test                                         # compile and run the JVM tests
./mvnw -B -ntp spotless:apply                               # apply formatting
./mvnw -B -ntp -pl :datacraft-jobs -am verify               # one module and what it depends on
./mvnw -B -ntp -pl :datacraft-cli -am package -DskipTests   # only the shaded CLI jar
```

On Windows run `.\mvnw.cmd` with the same arguments. Modules are selected by artifactId
(`-pl :<artifactId>`), not by directory. `verify` runs the Enforcer rules (build environment and
module boundaries), compiles with warnings treated as errors, runs the JUnit and ScalaTest suites,
packages the jars including the shaded CLI jar, and checks formatting and Checkstyle.

- Windows PowerShell splits an unquoted argument at a dot after a `-D` name, so
  `-Dmaven.test.skip=true` reaches Maven as two arguments and the build stops with an unknown
  lifecycle phase. Quote such arguments (`'-Dsurefire.failIfNoTests=false'`), or use cmd or
  Git Bash. `-DskipTests` has no dot and needs no quotes.
- Keep `-am` with `-pl`. The repository never runs `install`, so without `-am` Maven cannot
  resolve the sibling modules; after an `install` it would silently use those stale jars.

- Spotless formats Java with google-java-format 1.36.1 (and removes unused imports) and Scala with
  scalafmt 3.11.5 (`.scalafmt.conf`). It also trims trailing whitespace and requires a final newline
  in the text files the root `pom.xml` lists, such as the Markdown in `docs/` and `design/`, the
  Python in `orchestration/`, `tests/` and `cicd/`, and the shell scripts in `cicd/`.
- Checkstyle 14.3.0 checks the main and test Java sources against
  `config/checkstyle/checkstyle.xml`.

The Makefile wraps the common commands:

| Target | Runs |
| --- | --- |
| `make build` | `$(MVN) -B -ntp compile` |
| `make test` | `$(MVN) -B -ntp test` |
| `make verify` | `$(MVN) -B -ntp verify` |
| `make format` | `$(MVN) -B -ntp spotless:apply` |
| `make package` | `$(MVN) -B -ntp -pl :datacraft-cli -am package -DskipTests` |
| `make clean` | `$(MVN) -B -ntp clean` |
| `make test-scripts` | The `tests/deploy`, `tests/ci` and `tests/docs` suites with `$(PYTHON) -B -m unittest discover`, then `node --test tests/docs/cloud-costs.test.cjs` |
| `make typecheck` | `$(PYRIGHT)` |
| `make lint` | `cicd/lint/check-shell-scripts.sh` (`bash -n` and ShellCheck; needs git and ShellCheck) and `cicd/lint/check-actions-pinned.sh` |
| `make dags` | `$(PYTHON) -B -m unittest discover -s tests/orchestration -v` (needs Airflow) |
| `make images`, `up`, `down`, `swarm` | `deploy/scripts/build-images.sh`, `compose-up.sh`, `compose-down.sh`, `swarm-deploy.sh` |
| `make help` | Lists the targets |

The variables default to `MVN ?= ./mvnw`, `PYTHON ?= python3` and
`PYRIGHT ?= npx --yes pyright@1.1.414 --warnings`; override them on the command line, for example
`make MVN=mvn verify`.

## Test layout

```text
tests/
├── jvm/<artifactId>/java/        JUnit Jupiter tests of a Java module
├── jvm/<artifactId>/scala/       ScalaTest suites (datacraft-spark, datacraft-cli)
├── jvm/<artifactId>/resources/   optional test resources of one module
├── jvm/resources/                shared: logging.properties, log4j2-test.properties
├── orchestration/                Airflow DAG contract tests (test_dags.py)
├── smoke/                        the Airflow runtime smoke test (airflow_runtime_smoke.py)
├── deploy/                       deployment script and template tests
├── ci/                           tests of the cicd/ scripts and of the workflow structure
└── docs/                         test_docs_consistency.py, cloud-costs.test.cjs
```

JVM tests live outside the modules, but each module's own build compiles and runs them. The root
`pom.xml` points every module at its test directory by artifactId:

```xml
<datacraft.root>${maven.multiModuleProjectDirectory}</datacraft.root>
<datacraft.tests.dir>${datacraft.root}/tests/jvm/${project.artifactId}</datacraft.tests.dir>
<!-- in <build> -->
<testSourceDirectory>${datacraft.tests.dir}/java</testSourceDirectory>
```

- scala-maven-plugin derives the Scala test directory as
  `${project.build.testSourceDirectory}/../scala`. Test resources come from
  `tests/jvm/<artifactId>/resources` and the shared `tests/jvm/resources`.
- Put a new test under `tests/jvm/<artifactId>/java` or `scala`, in the package of the code it
  tests. Moving a module directory does not move its tests; renaming an artifactId does.
- The root project's Spotless check covers `tests/**/*.java` and `tests/**/*.scala`, and Checkstyle
  covers each module's Java test directory.
- `datacraft-spark` and `datacraft-cli` hold only ScalaTest suites and skip Surefire in their
  own POM. scalatest-maven-plugin writes their JUnit XML reports and an
  `<artifactId>-scalatest.txt` summary to the module's `target/surefire-reports`.
- `-DskipTests` compiles the tests without running them; `-Dmaven.test.skip=true` skips
  compiling them too, which is how the builder image builds (its Docker build context has no
  `tests/`). Do not give the root Surefire configuration a `<skip>`: an explicit value overrides
  `-Dmaven.test.skip` and breaks that build.
- Surefire fails a module whose JUnit tests all vanish (`surefire.failIfNoTests=true`), so an
  ad-hoc run of selected tests across modules needs two more flags. `-DfailIfNoTests=false`,
  which Surefire's own error message suggests, has no effect here, because the POM sets the
  parameter from the `surefire.failIfNoTests` property.
- `-Dtest=` selects JUnit classes only. In `datacraft-spark` and `datacraft-cli` choose ScalaTest
  suites with `-Dsuffixes=` (suites whose class name ends in the value) or `-DwildcardSuites=` (a
  package); either simply matches nothing in the other ScalaTest module, and the JUnit tests of
  the modules built along with `-am` still run. `-Dsuites=<class>` does not work across the
  reactor: the other ScalaTest module aborts with "Unable to load a Suite class".

```bash
./mvnw -B -ntp -pl :datacraft-jobs -am test -Dtest=CsvProfileJobTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.failIfNoTests=false
./mvnw -B -ntp -pl :datacraft-cli -am test -Dsuffixes=RunnerSpec
```

### Platform-specific tests

Tests that depend on the operating system carry a tag instead of skipping themselves at run time:

| Tag | Declared as | Covers |
| --- | --- | --- |
| `posix-only` | JUnit `@Tag("posix-only")`, ScalaTest `PosixOnly` | Symbolic links (Windows needs Developer Mode or elevation), `?` and `*` in file names (NTFS rejects them), Spark tests whose Hadoop local writes need winutils on Windows |
| `windows-only` | JUnit `@Tag("windows-only")` | NTFS directory junctions |

The `windows-host` and `posix-host` profiles, activated by the OS family, set `test.excluded.tags`
to the other platform's tag, which Surefire (`excludedGroups`) and scalatest-maven-plugin
(`tagsToExclude`) exclude. No test reports as skipped or canceled, and Linux CI runs every
`posix-only` test. Tag a new platform-specific test the same way.

### Test logging

Every test JVM gets the root pom's `test.jvm.args`:

- `-Djdk.net.unixdomain.tmpdir=${project.build.directory}` (see [Windows notes](#windows-notes)).
- `-Djava.util.logging.config.file=${datacraft.root}/tests/jvm/resources/logging.properties`: a
  java.util.logging configuration without handlers. The engine and the API log through
  java.util.logging, and the tests assert the failures they provoke through their own handlers, so
  nothing reaches the console.
- `-Ddatacraft.test.log.level=${datacraft.test.log.level}`, default `off`: the root level of Log4j 2
  (`tests/jvm/resources/log4j2-test.properties`), which Spark logs through.

To see Spark's log output, raise the level:

```bash
./mvnw -B -ntp -pl :datacraft-spark -am test -Ddatacraft.test.log.level=info
```

java.util.logging output needs a configuration with a `ConsoleHandler` in
`java.util.logging.config.file`.

### Windows notes

- Use `.\mvnw.cmd` from PowerShell or cmd.
- The build sets `jdk.net.unixdomain.tmpdir` to each module's `target` directory for every test JVM,
  on every OS. NIO selectors create an AF_UNIX wakeup socket in that directory, and some Windows
  profile TEMP directories cannot host one, which broke the HTTP server and Spark tests. Pass the
  same property when you run those tests outside Maven.
- The `windows-host` profile excludes the `posix-only` tests: symbolic links, `?` and `*` in file
  names, and the Spark tests that write through Hadoop and would need winutils. The `windows-only`
  junction tests run only on Windows.
- Test counts therefore differ by OS, and `cicd/build/check_test_counts.py` keeps a floor per
  platform: it checks a Windows build against the Windows floors, so the gate works here too.
- Airflow does not run on Windows. Use WSL or Linux for `tests/orchestration`, the Airflow smoke
  test and the deployment tests that run Bash.

## Python, Airflow and smoke tests

The deployment, CI tooling and documentation suites, which `make test-scripts` also runs:

```bash
python3 -B -m unittest discover -s tests/deploy -v   # deployment scripts and templates
python3 -B -m unittest discover -s tests/ci -v       # the CI/CD scripts and the workflow
python3 -B -m unittest discover -s tests/docs -v     # documentation links and consistency
node --test tests/docs/cloud-costs.test.cjs          # the evaluation report's cost calculator
```

`tests/orchestration` imports Airflow. It needs an Airflow 3.3.2 environment with the providers in
`orchestration/airflow/requirements.txt`, installed under Airflow's constraints file for your
Python version; [airflow.md](airflow.md) shows the setup, and `cicd/airflow/install-airflow.sh`
does the same inside an activated virtual environment (CI runs it on Python 3.13). Then run
`python -B -m unittest discover -s tests/orchestration -v`, or `make dags`.

The smoke tests exercise real runtimes. The first is a test a developer can run; the other two are
steps of the `containers` job and live with the pipeline scripts in `cicd/images`:

| Script | Needs | What it proves | CI job |
| --- | --- | --- | --- |
| `tests/smoke/airflow_runtime_smoke.py --jar <jar>` | The Airflow environment plus `pyspark==4.2.0`, Java 25, Bash | Runs the three DAGs for real against the packaged CLI, local Spark and a temporary local SFTP server; all state lives in a temporary directory | `dags`, and inside the Airflow image in `smoke-images.sh` |
| `cicd/images/smoke-images.sh` | Docker | Builds the images; checks that the JRE is at least 25.0.4.1, the API image (health, non-root user, read-only jar, a Spark job answering 500 FAILED), the Airflow image's dependency and security floors (`cicd/airflow/check_security_floor.py`, run inside the image), and the shared data volume's permissions; then runs the Airflow smoke test inside the Airflow image | `containers` |
| `cicd/images/smoke-compose.sh` | Docker, the images, `CI=true` | Starts the Compose stack with fresh secrets, waits for all three image-baked DAGs to be registered, runs the engine DAG through the scheduler, and restores a metadata backup into a separate database; then removes the stack and its volumes. When it fails it prints the task logs first | `containers` |

Both `cicd/images` scripts are meant for disposable CI runners: the first creates and removes the
canary files `.env` and `backups/ci-probe.dump` and refuses to run when they exist; the second
refuses to run unless `CI=true` and when `deploy/compose/.env` exists.

## Type checking

`pyrightconfig.json` covers `orchestration`, `deploy`, `tests` and `cicd`, analysed for Linux and
Python 3.12 in `standard` mode. Airflow, its providers, paramiko, pyspark and packaging exist only
in the Airflow environment, so missing imports are not reported in `orchestration/airflow/dags`,
`tests/orchestration`, `tests/smoke` and `cicd/airflow`.

```bash
make typecheck   # npx --yes pyright@1.1.414 --warnings
```

The CI `scripts` job runs the same command. `--warnings` makes pyright exit non-zero on warnings as
well as errors, so the gate keeps the tree at `0 errors, 0 warnings, 0 informations`.

## CI

`.github/workflows/ci.yml` runs five jobs on `ubuntu-24.04` for pushes and pull requests to `main`
and on manual dispatch. Actions are pinned to full commit SHAs. GitHub reads workflows only from
`.github/workflows`, so the file stays there, but it only orchestrates: triggers, runners, toolchain
setup and job order. Every step runs one command: a script in `cicd/`, a test entry point in
`tests/`, or a single tool (the Maven Wrapper, pyright, a clean-up `rm`), so the pipeline logic can
be run and reviewed outside GitHub Actions. `tests/ci/test_workflow.py` fails when a step is more
than one command (a block scalar or a shell operator such as `&&` or `|`), names a script that
does not exist, when a file in `cicd/` is used by neither the workflow nor another `cicd/` script
(a mention in a comment does not count), or when a shell script appears outside `cicd/` and
`deploy/scripts/`.

What goes where: `cicd/` holds what a pipeline job runs, `tests/` holds the suites a test runner
or a developer runs (including the tests of the `cicd/` scripts, in `tests/ci`), and
`deploy/scripts/` holds what an operator runs on the host.

```text
cicd/
├── lib.sh      shared helpers: REPO_ROOT, CLI_JAR, fail, scratch_dir, python_command
├── build/      build job: wrapper checksum, test-count floors, Spark suite, CLI jar checks,
│               the builder image's Maven command in the Docker build context
├── airflow/    dags job: constrained Airflow install and the security floor
├── lint/       scripts job: the pinned ShellCheck, shell syntax and ShellCheck, pinned actions
├── stacks/     compose job: the Compose file and the Swarm stack
└── images/     containers job: image build and smoke test, Compose stack smoke test
```

| Job | What it runs |
| --- | --- |
| `build` | `cicd/build/check-maven-wrapper.sh` (the wrapper refuses a Maven download with the wrong checksum); `./mvnw -B -ntp verify` on JDK 25; `cicd/build/check_test_counts.py` (test-count floors); `cicd/build/check-spark-suite.sh` (`SparkPipelineSpec` ran with no canceled tests); `cicd/build/smoke-cli-jar.sh` (`list-jobs`, `echo`, `noop`, `csv-profile`, `file-checksum`, and a failing row gate that must exit `1`); `cicd/build/check-jar-contents.sh` (Multi-Release manifest, no `module-info.class`, JSch's modern algorithms through `cicd/build/JschAlgorithmsProbe.java`, the Jackson version in `jackson.version`); `cicd/build/check-cli-exit-codes.sh` (exit codes `1` and `2`); `cicd/build/check-image-build.sh` (the Maven command of `Dockerfile.build`, run in a copy of the Docker build context that `cicd/build/docker_context.py` computes from `.dockerignore`). It then shares the verified jar with `dags` |
| `dags` | After `build`: Python 3.13 and JDK 25; `cicd/airflow/install-airflow.sh` (Airflow 3.3.2 and its providers under the official constraints for the running Python, then `pyspark==4.2.0`); `cicd/airflow/check_security_floor.py` (Airflow 3.3.2 and FAB provider 3.9.0); `tests/orchestration`; `tests/smoke/airflow_runtime_smoke.py` with the verified jar |
| `scripts` | `cicd/lint/check-actions-pinned.sh` (every `uses:` is `owner/repo@<full commit SHA> # vX.Y.Z` or a local action, on one line, and none appears in a comment); `cicd/lint/install-shellcheck.sh` (ShellCheck 0.11.0); `cicd/lint/check-shell-scripts.sh` (`bash -n` and ShellCheck over every shell script git knows; every finding fails, sourced files are followed); the `tests/deploy`, `tests/ci` and `tests/docs` suites on Python 3.13; `node --test tests/docs/cloud-costs.test.cjs` on Node.js 24; `npx --yes pyright@1.1.414 --warnings` |
| `compose` | `cicd/stacks/check-stack-files.sh`: `docker compose config` and `docker stack config` with test-only secrets, and that each missing secret is rejected |
| `containers` | After `build`, `scripts` and `compose`: `cicd/images/smoke-images.sh`, then `cicd/images/smoke-compose.sh` |

The `cicd/build` scripts run after `./mvnw verify` on Linux, WSL or Git Bash (they use GNU tools
and Bash 4, so not on a stock macOS); they need `java`, `unzip` and `sha256sum`, and `CLI_JAR`
overrides the jar path.
`check-image-build.sh` also needs Python 3 (`PYTHON` selects the interpreter) and builds offline
from the local Maven repository; it needs no Docker, so it shows on any workstation whether the
builder image would still compile.
`check-maven-wrapper.sh` needs network access, because the wrapper downloads Maven before it
rejects the checksum. `cicd/airflow/install-airflow.sh` installs into the current Python
environment, so run it inside a virtual environment. `cicd/stacks/check-stack-files.sh` needs
Docker, and refuses to run while `deploy/compose/.env` exists because that file may hold real
secrets.

### Test-count floors

`cicd/build/check_test_counts.py` sums the JUnit XML reports in each module's
`target/surefire-reports` and fails when a module executed fewer tests than its floor. Skipped,
aborted and canceled tests do not count, so a renamed class or a suite that silently cancels itself
cannot pass as green. The floors are minimums: adding tests needs no change, but deliberately
removing tests needs a lower floor in the same commit. They differ by platform, because each
platform excludes the other's tagged tests; `--platform linux` or `--platform windows` names the
one that ran the tests and defaults to the one the script runs on. `MODULE_DIRS` in the same script
maps each artifactId to its module directory, and a test in `tests/ci` keeps it equal to the root
pom's `<modules>`.

| Module | Linux floor (CI) | Windows floor |
| --- | --- | --- |
| `datacraft-common` | 7 | 7 |
| `datacraft-config` | 13 | 13 |
| `datacraft-io` | 57 | 59 |
| `datacraft-engine` | 32 | 32 |
| `datacraft-jobs` | 70 | 68 |
| `datacraft-spark` | 52 | 44 |
| `datacraft-api` | 17 | 17 |
| `datacraft-cli` | 28 | 28 |

## Zero-warning policy

- javac runs with `-Xlint:all` and `failOnWarning`, so any Java compiler warning, in main or test
  code, fails the build.
- scalac runs with `-deprecation -feature -unchecked -Xlint -Werror`, so any Scala compiler warning
  fails the build.
- pyright stays at zero errors and zero warnings (see [Type checking](#type-checking)).
- ShellCheck 0.11.0 reports nothing at all, style notes included, for any shell script
  (`make lint`).
- A local `./mvnw verify` on JDK 25.0.4.1 prints no Maven or JVM warning at all; an older JDK fails
  the Enforcer instead of warning.

The reasons and the rest of the build rules are in
[build and quality](../../design/build-and-quality.md).

## Troubleshooting

- **"Failed to validate Maven distribution SHA-256"** from `./mvnw`: without `unzip`, the Unix
  wrapper downloads the `.tar.gz` distribution, whose checksum cannot match the pinned zip. Install
  `unzip` and run it again. Without `sha256sum` or `shasum` it stops with "Checksum validation was
  requested but neither 'sha256sum' or 'shasum' are available."
- **`validate` fails on `requireJavaVersion`**: the JDK is older than 25.0.4.1. Install 25.0.4.1
  or newer and point `JAVA_HOME` at it.
- **`validate` fails on `enforce-module-boundaries`**: a new dependency crosses a module boundary.
  The message ends in `(design/architecture.md, "Module responsibilities")`; see
  [Module responsibilities](../../design/architecture.md#module-responsibilities).
- **An ad-hoc `-Dtest=...` run fails in modules without a matching test**: add
  `-Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.failIfNoTests=false`.
- **The Spotless check fails**: run `./mvnw -B -ntp spotless:apply` (or `make format`) and review
  the changes.
- **`check_test_counts.py` reports a module BELOW FLOOR**: tests that used to run did not. The
  first output line names the platform whose floors were used; pass `--platform` when the reports
  come from another machine.
- **Maven fails with "Unknown lifecycle phase" in PowerShell**: an unquoted `-D` argument with a
  dot was split; quote it (see [Build and test](#build-and-test)).
- **A Spark job fails with "Spark runtime is not on the classpath"**: launch it with
  `spark-submit`, as described in [the usage guide](usage.md#spark-jobs-with-spark-submit).
