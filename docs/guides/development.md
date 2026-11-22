# Development guide

How to build and test datacraft-lab, how the tests are organized, and what CI checks. Running jobs
is covered in [the usage guide](usage.md); the reasoning behind the build rules is in
[build and quality](../../design/build-and-quality.md).

## Prerequisites

- **JDK 25.0.5 or newer.** The build targets Java 25 bytecode, and the Enforcer fails it on an
  older JDK: "datacraft-lab builds with JDK 25.0.5 or newer and targets Java 25 bytecode: 25.0.5
  carries the October 2026 security fixes, and Spark 4.2.0 deprecates Java 25 releases older than
  25.0.3." On Windows, set `$env:JAVA_HOME` (for example `C:\path\to\jdk-25`) if Maven
  does not find it.
- **Maven, through the wrapper.** `./mvnw` (Linux, macOS, WSL) and `mvnw.cmd` (Windows; it runs its
  logic in PowerShell) are Maven Wrapper 3.3.4 scripts; no wrapper jar is committed. On first use
  they download Maven 3.10.0 into `~/.m2/wrapper/dists` and verify it against the SHA-256 pinned in
  `.mvn/wrapper/maven-wrapper.properties`; an existing download there is reused without a new check.
  The Unix script needs `unzip` and `sha256sum` or `shasum`, and downloads with `wget` or `curl`,
  falling back to a small Java program. A Maven 3.9 or newer on `PATH` also works
  (`make MVN=mvn ...`). `.mvn/jvm.config` passes `--enable-native-access=ALL-UNNAMED` and
  `--sun-misc-unsafe-memory-access=allow` to the JVM that runs Maven, and `.mvn/maven.config` tells
  Maven 3.10 not to ask other repositories for prefix files, which would warn whenever one of them
  is unreachable.
- **Docker Engine with Compose v2**, only for building images, deploying, and the container smoke
  tests.
- **Python 3.12 or newer.** CI runs the Python suites and Airflow on 3.13, the Python of the
  `apache/airflow:3.3.2` image; its test stage's `backend` job runs the two Python checks in
  `cicd/build` on the runner's own Python 3.12. Both desktop matrix legs and the Windows installer
  job explicitly select Python 3.13 before the first wrapped command; the Windows output-pipe
  implementation needs at least Python 3.12. The deployment, CI, design and documentation suites
  need only the standard library.
- **Inno Setup 6**, only on Windows for `build.bat`, which builds the desktop installer
  `dist/Setup.exe` (or set `DATACRAFT_ISCC` to an `ISCC.exe`; CI uses the pinned 6.7.4).
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
  `config/checkstyle/checkstyle.xml`: final newlines, explicit and used imports, plus naming rules
  for packages, types, methods, fields, constants, parameters, locals and record components.
  Catch and lambda parameters use the same lower-camel-case convention; failures are build errors.
  The exact twelve naming checks are listed in [build and quality](../../design/build-and-quality.md#checkstyle).

The Makefile wraps the common commands:

| Target | Runs |
| --- | --- |
| `make build` | `$(MVN) -B -ntp compile` |
| `make test` | `$(MVN) -B -ntp test` |
| `make verify` | `$(MVN) -B -ntp verify` |
| `make checks` | After `make verify`: the checks CI's test-stage `backend` job runs on the reports and the jar, namely the test-count floors, the Spark suite, the CLI jar, its contents and exit codes, and the builder image's Maven command (`cicd/build`; needs Bash and `unzip`, no Docker) |
| `make format` | `$(MVN) -B -ntp spotless:apply` |
| `make package` | `$(MVN) -B -ntp -pl :datacraft-cli -am package -DskipTests` |
| `make clean` | `$(MVN) -B -ntp clean` |
| `make test-scripts` | The `tests/deploy`, `tests/ci`, `tests/design` and `tests/docs` suites with `$(PYTHON) -B -m unittest discover`, then `node --test tests/docs/cloud-costs.test.cjs` |
| `make design` | `$(PYTHON) -B design/ui/build.py`: writes the desktop themes and the prototype's tokens from `design/ui/tokens.json`; CI runs it with `--check` |
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
├── orchestration/                Airflow DAG contract tests (test_dags.py) and the security floor
│                                 (test_security_floor.py)
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
- Desktop tests use the same external layout (`tests/jvm/datacraft-desktop/java`) and the optional
  `-Pdesktop -pl :datacraft-desktop -am verify` reactor. Parameter mode switching, connection-test
  revisions, cache-clear notifications, field labels and CSS contrast have focused regressions;
  [desktop testing](../desktop/testing.md) describes their contracts. Use controlled UI/store
  queues or backend responses to reproduce callback order instead of timing-dependent sleeps.
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
Python version, and the fixed releases of `orchestration/airflow/security-overrides.txt` over
them; [airflow.md](airflow.md) shows the setup, and `cicd/airflow/install-airflow.sh`
does the same inside an activated virtual environment (CI runs it on Python 3.13). Then run
`python -B -m unittest discover -s tests/orchestration -v`, or `make dags`.

The smoke tests exercise real runtimes. The first is a test a developer can run; the others are
steps of the build stage's `images` job and live with the pipeline scripts in `cicd/images`:

| Script | Needs | What it proves | CI job |
| --- | --- | --- | --- |
| `tests/smoke/airflow_runtime_smoke.py --jar <jar>` | The Airflow environment plus `pyspark==4.2.0`, Java 25, Bash | Runs the three DAGs for real against the packaged CLI, local Spark and a temporary local SFTP server, including a GBK file converted with `encoding=GBK`, and then requires a run of the SFTP DAG through a connection that verifies no host key to fail before it downloads anything; all state lives in a temporary directory | test stage `airflow`, and inside the Airflow image in `smoke-images.sh` |
| `cicd/images/smoke-images.sh` | Docker | Builds the images, the optional `datacraft/spark` among them; checks that the builder and the three runtime images run the Temurin release pinned in `deploy/docker/Dockerfile.temurin`, at least 25.0.5, the API image (no start without a token, health, 401 without the token, non-root user, read-only jar, a Spark job answering 500 FAILED), the Airflow image's dependency and security floors (`cicd/airflow/check_security_floor.py`, run inside the image), the shared data volume's permissions, a conversion and a count inside the Spark image's data directory and the refusal of an output outside it, and the stacks' one-shot initializer under UID 1000; then runs the Airflow smoke test inside the Airflow image | build stage `images` |
| `cicd/images/smoke-compose.sh` | Docker, the images, `CI=true` | Starts the Compose stack with fresh secrets, checks the admin login and that the generated API token opens `datacraft-api`, waits for all three image-baked DAGs to be registered, runs the engine DAG through the scheduler, and restores a metadata backup into a separate database; then removes the stack and its volumes. When it fails it prints the service logs, the task logs and the container states before it removes the stack | build stage `images` |
| `cicd/images/smoke-swarm.sh` | Docker outside any swarm, the images, `CI=true` | Rehearses the documented Swarm deployment on a swarm of one node: a loopback registry, both images under an exact tag, `swarm-deploy.sh` and `airflow-init.sh`; requires every service to run (healthy where it has a health check), the engine API to answer its token only and only on the overlay network, the generated admin to sign in through the routing mesh, and the engine DAG to run through Redis and a Celery worker; then deploys a second tag and requires the initializer to run again, the idle worker to stop, the earlier run to survive and the DAG to run again. Leaves the swarm and removes the stack, its volumes, the registry and the names it gave the images, and fails when a volume stays. One node says nothing about placement or failover | build stage `images` |

The `cicd/images` scripts are meant for disposable CI runners: the first creates and removes the
canary files `.env` and `backups/ci-probe.dump` and refuses to run when they exist; the other two
refuse to run unless `CI=true` and when `deploy/compose/.env` exists, and `smoke-swarm.sh` also
when the Docker engine is already part of a swarm. Both stack scripts check credentials with
`cicd/images/check_access.py`, which runs anywhere: given the stack's `.env` or the same values in
the environment, `--airflow URL` requires a token for the admin password and none for another
password, and `--api URL` requires `/jobs` to open for the API token only.
The probe connects directly, ignores proxy environment settings and never follows redirects,
including same-origin redirects. A `3xx` is reported as the original endpoint's unexpected
status and makes the check fail; neither bearer credentials nor login bodies are forwarded to
another URL. Supply the actual API base URL instead of a redirecting front page. Success proves
that the requested endpoint itself answered the expected authentication checks.

With Docker on the workstation the four container scripts also run locally, and it is worth doing
before a push that changes an image, a stack file or a deployment script: two defects of the
rehearsal's teardown only showed on an engine that is kept. The commands, and how to run them on
Docker Desktop for Windows, are in the
[deployment guide](deployment.md#7-验证与已知范围).

## Type checking

`pyrightconfig.json` covers `orchestration`, `deploy`, `tests` and `cicd`, analysed for Linux and
Python 3.12 in `standard` mode. Airflow, its providers, paramiko, pyspark and packaging exist only
in the Airflow environment, so missing imports are not reported in `orchestration/airflow/dags`,
`tests/orchestration`, `tests/smoke` and `cicd/airflow`.

```bash
make typecheck   # npx --yes pyright@1.1.414 --warnings
```

The CI check stage's `lint` job runs the same command. `--warnings` makes pyright exit non-zero on warnings as
well as errors, so the gate keeps the tree at `0 errors, 0 warnings, 0 informations`.

## CI/CD

`.github/workflows/` holds one entry, `cicd.yml`, and four stage files. A stage file is a reusable
workflow (`on: workflow_call`) with no trigger of its own; the entry calls the stages in order and
its `needs` stop the pipeline at the first stage that fails, so a broken check spends nothing on the
tests and a failed test nothing on the builds. Every result is recorded on the commit under test
(a `workflow_run` chain would run the default branch's file on its latest commit instead).

| Trigger | Stages |
| --- | --- |
| Push or pull request to `main` or `develop` | check → test → build |
| Monday 03:17 UTC (schedule) | check (new advisories for pins nobody touched) |
| Manual run, `deploy-version` left empty | check → test → build |
| Manual run on `main` with `deploy-version` | deploy request → check → test → build → deploy |

| File | Jobs |
| --- | --- |
| `cicd.yml` (entry) | `deploy-request` on a deploy run only (`cicd/release/deploy.py request`: branch `main`, the entered version equals the [version source](../versioning.md), the tag `v<version>` names no other commit), then the four stages |
| `check.yml` | `security`: `cicd/lint/check-actions-pinned.sh` (every `uses:` is `owner/repo@<full commit SHA> # vX.Y.Z` or a local path, on one line, and none appears in a comment), `cicd/security/check_advisories.py` and `cicd/security/check_temurin.py` (below). `lint`: `cicd/release/version.py`, `design/ui/build.py --check` (the themes generated from the design tokens are current and `base.css` stays on its scales), the pinned ShellCheck 0.11.0 and `cicd/lint/check-shell-scripts.sh` (`bash -n` and ShellCheck over every shell script git knows), `npx --yes pyright@1.1.414 --warnings`, and `cicd/stacks/check-stack-files.sh` (`docker compose config` and `docker stack config` with test-only secrets, and that each missing secret is rejected) |
| `test.yml` | `backend`: `cicd/build/check-maven-wrapper.sh`; `./mvnw -B -ntp verify` on JDK 25; `cicd/build/check_test_counts.py`; `cicd/build/check-spark-suite.sh` (`SparkPipelineSpec` ran with no canceled tests); `cicd/build/smoke-cli-jar.sh` (`list-jobs`, `echo`, `noop`, `csv-profile`, `csv-quality`, `file-checksum`, failing row/quality gates that must exit `1`); `cicd/build/check-jar-contents.sh` (Multi-Release manifest, no `module-info.class`, JSch's modern algorithms through `cicd/build/JschAlgorithmsProbe.java`, the Jackson version); `cicd/build/check-cli-exit-codes.sh`; `cicd/build/check-image-build.sh` (the Maven command of `Dockerfile.build` in a copy of the Docker build context that `cicd/build/docker_context.py` computes); it shares the verified jar with `airflow`. `airflow`: Python 3.13 and JDK 25; `cicd/airflow/install-airflow.sh`; `cicd/airflow/check_security_floor.py`; `tests/orchestration`; `tests/smoke/airflow_runtime_smoke.py` with the verified jar. `scripts`: the `tests/deploy`, `tests/ci`, `tests/design` and `tests/docs` suites on Python 3.13 and `node --test tests/docs/cloud-costs.test.cjs` on Node.js 24. `desktop`: a Windows 2025 / Ubuntu 24.04 matrix (Linux with D-Bus and Xvfb) running `./mvnw -B -ntp -Pdesktop -pl :datacraft-desktop -am verify` and `cicd/build/check_test_counts.py --project desktop` (Windows 206 / Linux 203 tests); UI snapshots are uploaded |
| `build.yml` | `images`: `cicd/images/smoke-images.sh`, `cicd/images/smoke-compose.sh`, `cicd/images/smoke-swarm.sh` (below). `app-image`: the desktop packaged on Linux and `cicd/desktop/package_desktop.py` under Xvfb (jlink, jpackage, AOT training on all views and themes, 20 fresh and reused starts each confirming AOT, with no runtime diagnostics and at most 1500 ms). `installer`: on Windows 2025, `cicd/desktop/prepare_inno.py` (Inno Setup 6.7.4 by SHA-256), `build.bat` (the developer's entry point: clean package, the app image with the same 20 timed starts, `dist/Setup.exe`), `cicd/desktop/verify_install.py` (below), `cicd/desktop/release.py outputs`; uploads `Setup.exe` with its checksum (kept 7 days, 30 for a release) and the lifecycle report. Its outputs, version, artifact and SHA-256, go to deploy |
| `deploy.yml` | `publish` in the `release` environment: `cicd/release/deploy.py publish` checks the branch again and that the installer carries the confirmed version, then writes the job summary. The publication target is not decided yet: it uploads nothing and creates no tag |

GitHub reads workflows only from `.github/workflows`, so the files stay there, but they only
orchestrate: triggers, runners, toolchain setup and job order. Every step runs one command: a script
in `cicd/`, a test entry point in `tests/`, the root `build.bat`, or a single tool (the Maven Wrapper,
pyright, a clean-up `rm`), so the pipeline logic can be run and reviewed outside GitHub Actions.
`tests/ci/test_workflow.py` fails when a step is more than one command (a block scalar or a shell
operator such as `&&` or `|`), names a script that does not exist, when a file in `cicd/` is used by
neither a workflow, a build manifest nor another `cicd/` script (a mention in a comment does not
count), or when a shell script appears outside `cicd/` and `deploy/scripts/`. Checkouts do not keep
the token (`persist-credentials: false`); every workflow has `contents: read` only.

What goes where: `cicd/` holds what a pipeline job runs, `tests/` holds the suites a test runner
or a developer runs (including the tests of the `cicd/` scripts, in `tests/ci`), and
`deploy/scripts/` holds what an operator runs on the host.

Every `run:` step goes through `cicd/step.py`, which each workflow sets as its default shell
(`python3 -B cicd/step.py bash -e {0}`, with `python` for the Windows jobs). On Windows it locates
Bash beside the installed Git executable, avoiding the system's WSL launcher without changing PATH.
After the command exits, the wrapper drains output for at most two seconds on both platforms,
even if a background process retains the pipe. Windows uses nonblocking pipes (Python 3.12 or
newer); the wrapper neither leaves a reader thread running nor terminates background processes.
The step runs unchanged; when it fails on a runner, the
script adds an error annotation with the step's command, the first lines that name a failure and
the last 30 lines of output. A script that tears a failed stack down reports its state first with
`diagnose TITLE COMMAND...` from `cicd/lib.sh`; each of the last eight such groups becomes an
annotation of its own (for example "Compose: log of airflow-scheduler"). A job log needs access to
the repository, but annotations are public, so the reason for a failure and the evidence of a
passing run can be read with plain `curl`:

```bash
api=https://api.github.com/repos/liluyang1999/datacraft-lab
curl -s "$api/actions/runs?per_page=5"                # the latest runs and their conclusions
curl -s "$api/actions/runs/<run id>/jobs"             # the jobs of one run, with their ids
curl -s "$api/check-runs/<job id>/annotations"        # what failed, or what a passing job recorded
```

A passing run records notices there too: the number of tests each module executed, the Java
runtime of the images and what the stack check verified. Because the script comes from the
checkout, the checkout must be the first step of every job. A platform-specific `shell` override
may select `python` instead of `python3`, but must keep the same `cicd/step.py bash -e {0}` wrapper;
`tests/ci/test_workflow.py` checks both requirements and requires each Windows job (the desktop
matrix and the installer) to select Python 3.13 after checkout and before its first wrapped command.
Every action reference is pinned to a full commit SHA.

```text
cicd/
├── step.py     the shell of every run step: on failure, the decisive output as an annotation
├── lib.sh      shared helpers: REPO_ROOT, CLI_JAR, fail, notice, diagnose, scratch_dir,
│               python_command
├── release/    the single version source (version.py) and the deploy gate (deploy.py)
├── build/      test stage, backend: wrapper checksum, test-count floors, Spark suite, CLI jar
│               checks, the builder image's Maven command in the Docker build context
├── airflow/    test stage, airflow: constrained Airflow install and the security floor
├── lint/       check stage: the pinned ShellCheck, shell syntax and ShellCheck, pinned actions
├── stacks/     check stage: the Compose file and the Swarm stack
├── security/   check stage: known advisories for every pinned version, the Temurin pin
├── images/     build stage, images: image build and smoke test, Compose stack smoke test, Swarm
│               rehearsal on one node, the credential check both stacks share
└── desktop/    build stage: the 数艺工坊 app image (jlink, jpackage, AOT cache) and its starts,
                build.bat's chain (release.py), the installer (build_installer.py, prepare_inno.py)
                and its lifecycle check (verify_install.py)
```

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

### Desktop release: build.bat, run.bat and the installer lifecycle

On Windows x64 with JDK 25.0.5+, Python 3.12+ and Inno Setup 6 (or `DATACRAFT_ISCC` naming its
`ISCC.exe`), double-click `build.bat` or run it in a console. It runs `cicd/desktop/release.py build`:
a clean desktop package, `package_desktop.py` (the app image and its 20 timed starts) and
`build_installer.py`, which writes `dist/Setup.exe` and `dist/Setup.exe.sha256` and prints the path,
the version and the SHA-256. A second build of the same checkout refuses to start while the first
runs. `run.bat` starts the console from the sources (`./mvnw -f desktop/pom.xml javafx:run`).

`cicd/desktop/verify_install.py` walks the installer through its lifecycle: a clean machine; a
silent install into `C:\Program Files\DatacraftLab` with its uninstall key (name 数艺工坊,
publisher, the source version, location) and Start menu shortcut; the installed app reaching its
first frame with a temporary data folder; Setup refusing while that app runs; a repair; a refused
downgrade (a higher version registered for the moment); an uninstall that removes the folder, the
shortcut and the key and keeps the data folder; then an install into a chosen folder and its
uninstall. CI runs it elevated on the installer job. Without administrator rights, build and walk
the per-user fixture variant instead, which has its own identity, folder and key and leaves nothing
behind (the running-app check needs the machine installer):

```powershell
python -B cicd/desktop/build_installer.py --fixture local
python -B cicd/desktop/verify_install.py --fixture local
```

### Releases

A deploy is a manual run of **CI/CD** on `main` with `deploy-version` set to the product version
(see [Versioning](../versioning.md)). The `deploy-request` job refuses another branch, another
version or a tag `v<version>` that names a different commit before anything else runs; then check,
test and build run on the same commit, and `deploy` compares the installer's version with the one
confirmed. Until a publication target is chosen, `deploy` only writes the version, the artifact name
and the SHA-256 into the run's summary; the installer stays in the run's artifacts for 30 days.

### Advisory check

The check stage runs `python3 -B cicd/security/check_advisories.py` on every push and pull request
and on the Monday schedule. The script collects every pin (the dependencies the root `pom.xml`
manages, those `desktop/pom.xml` pins itself (JavaFX and sqlite-jdbc), Airflow, pyspark, the FAB
provider floor, the fixed releases of `orchestration/airflow/security-overrides.txt`, shellcheck-py,
pyright and the actions of the workflows) and asks [OSV](https://osv.dev) about each. It exits `1`
and prints one `::error::` line per affected pin when a pinned version has an advisory, and `2` when
OSV could not be asked, so a dead check never looks like a pass. `--list` prints the pins without
asking anything. Transitive dependencies and the base images are outside its reach. A second step,
`python3 -B cicd/security/check_temurin.py`, asks Adoptium about the Temurin release that every image
runs: it fails while a newer Temurin 25 release exists, or when a checksum pinned in
`deploy/docker/Dockerfile.temurin` differs from the one Adoptium publishes, and exits `2` when
Adoptium could not be asked. Nothing opens pull requests: a failed run is the signal, and raising
the pin is a normal commit.

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
| `datacraft-common` | 10 | 10 |
| `datacraft-config` | 14 | 14 |
| `datacraft-io` | 64 | 66 |
| `datacraft-engine` | 34 | 34 |
| `datacraft-jobs` | 91 | 89 |
| `datacraft-spark` | 64 | 54 |
| `datacraft-api` | 36 | 36 |
| `datacraft-cli` | 32 | 32 |

`--project desktop` checks the desktop console instead (`desktop/target/surefire-reports`): the
floor is 203 on Linux and 206 on Windows, which also runs three real native accessibility tests.
Each runs its own `VaultPlatformTest` case; `verify` excludes `e2e`. The desktop matrix checks the
floor after `verify`.

## Zero-warning policy

- javac runs with `-Xlint:all` and `failOnWarning`, so any Java compiler warning, in main or test
  code, fails the build.
- scalac runs with `-deprecation -feature -unchecked -Xlint -Werror`, so any Scala compiler warning
  fails the build.
- pyright stays at zero errors and zero warnings (see [Type checking](#type-checking)).
- ShellCheck 0.11.0 reports nothing at all, style notes included, for any shell script
  (`make lint`).
- A local `./mvnw verify` on JDK 25.0.5 prints no Maven or JVM warning at all; an older JDK fails
  the Enforcer instead of warning.

The reasons and the rest of the build rules are in
[build and quality](../../design/build-and-quality.md).

## Troubleshooting

- **"Failed to validate Maven distribution SHA-256"** from `./mvnw`: without `unzip`, the Unix
  wrapper downloads the `.tar.gz` distribution, whose checksum cannot match the pinned zip. Install
  `unzip` and run it again. Without `sha256sum` or `shasum` it stops with "Checksum validation was
  requested but neither 'sha256sum' or 'shasum' are available."
- **`validate` fails on `requireJavaVersion`**: the JDK is older than 25.0.5. Install 25.0.5
  or newer and point `JAVA_HOME` at it.
- **`validate` fails on `enforce-module-boundaries`**: a new dependency crosses a module boundary.
  The message ends in `(design/architecture.md, "Module responsibilities")`; see
  [Module responsibilities](../../design/architecture.md#module-responsibilities).
- **An ad-hoc `-Dtest=...` run fails in modules without a matching test**: add
  `-Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.failIfNoTests=false`.
- **The Spotless check fails**: run `./mvnw -B -ntp spotless:apply` (or `make format`) and review
  the changes.
- **A CI job failed and its log is not accessible**: read the job's annotations (see [CI](#ci));
  the error annotation of the failed step carries the lines that name the failure.
- **`check_test_counts.py` reports a module BELOW FLOOR**: tests that used to run did not. The
  first output line names the platform whose floors were used; pass `--platform` when the reports
  come from another machine.
- **Maven fails with "Unknown lifecycle phase" in PowerShell**: an unquoted `-D` argument with a
  dot was split; quote it (see [Build and test](#build-and-test)).
- **A Spark job fails with "Spark runtime is not on the classpath"**: launch it with
  `spark-submit`, as described in [the usage guide](usage.md#spark-jobs-with-spark-submit).
