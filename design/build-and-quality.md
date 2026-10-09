# Build and quality

The JVM code is one Maven reactor driven by the root [`pom.xml`](../pom.xml). `./mvnw verify` is
the single local gate; GitHub Actions ([`ci.yml`](../.github/workflows/ci.yml)) is the
authoritative one. This document explains how the build is organised and why. Day-to-day commands
are in the [development guide](../docs/guides/development.md).

## Principles

- The root POM is the only place that manages versions, plugin configuration and quality gates.
  Module POMs declare their coordinates, their dependencies (third-party ones without versions),
  their `enforce-module-boundaries` rule and only the plugins specific to the module: Scala
  compilation and ScalaTest in `datacraft-spark` and `datacraft-cli` (which also switch Surefire
  off, having no JUnit classes) and shading in `datacraft-cli`.
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
- `.dockerignore` excludes `tests/`, `docs/`, `design/` and `cicd/`, so the builder image packages
  with `-Dmaven.test.skip=true`, which skips compiling the tests as well as running them. Tests
  run locally and in CI, and CI's `build` job runs the builder's Maven command in a copy of that
  build context (see [Skipping tests](#skipping-tests) and [CI gates](#ci-gates)).

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
| scopt | `scopt.version` | 4.2.0 |
| JUnit Jupiter (test) | `junit.jupiter.version` | 6.1.3 |
| ScalaTest (test) | `scalatest.version` | 3.2.20 |
| Apache MINA SSHD `sshd-sftp`, `sshd-mina` (test) | `sshd.version` | 2.20.0 |
| SLF4J API and `slf4j-nop` (test) | `slf4j.version` | 2.0.20 |
| google-java-format | `google.java.format.version` | 1.36.1 |
| scalafmt | Spotless configuration and `.scalafmt.conf` | 3.11.5 |
| Checkstyle engine | `checkstyle.version` | 14.3.0 |
| Maven | `.mvn/wrapper/maven-wrapper.properties` | 3.10.0 |
| Maven Wrapper | `.mvn/wrapper/maven-wrapper.properties` | 3.3.4 |

| Plugin | Version |
| --- | --- |
| maven-clean-plugin | 3.5.0 |
| maven-resources-plugin | 3.5.0 |
| maven-compiler-plugin | 3.16.0 |
| scala-maven-plugin | 4.10.0 |
| maven-surefire-plugin | 3.6.0 |
| scalatest-maven-plugin | 2.2.0 |
| maven-jar-plugin | 3.5.1 |
| maven-shade-plugin | 3.6.2 |
| maven-install-plugin | 3.2.0 |
| maven-deploy-plugin | 3.2.0 |
| maven-site-plugin | 3.22.0 |
| maven-dependency-plugin | 3.11.0 |
| maven-enforcer-plugin | 3.6.3 |
| spotless-maven-plugin | 3.10.4 |
| maven-checkstyle-plugin | 3.6.0 |

The install, deploy and site plugins are pinned because the Enforcer checks plugins up to the
`deploy` and `site` phases; maven-dependency-plugin serves the builder image's
`dependency:go-offline` step. Spark constrains two choices: `scala.version` is the Scala version
Spark 4.2.0 is built with (2.13.18), and Jackson stays on the 2.x line that Spark uses (decision 4
in [decisions.md](decisions.md)). One component is deliberately one release behind:
google-java-format 1.37.0 does not run under Spotless 3.10.4, the newest release, whose formatter
step fails on the first Java file (checked on 2026-10-09), so it stays at 1.36.1 until a Spotless
release runs it.

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

- **Java**: google-java-format 1.36.1 with `removeUnusedImports`, on `src/main/java/**/*.java`,
  `tests/**/*.java` and `cicd/**/*.java`.
- **Scala**: scalafmt 3.11.5 with [`.scalafmt.conf`](../.scalafmt.conf) (dialect `scala213`,
  `maxColumn` 100, `align.preset = more`, rewrite rules `RedundantBraces`, `RedundantParens` and
  `SortImports`, asterisk docstrings), on `src/main/scala/**/*.scala` and `tests/**/*.scala`.
- **Other files**: trailing whitespace removed and a final newline required for `.gitattributes`,
  `*.md`, `*.xml`, `*.yml`, `*.yaml` and `*.properties` in the project directory, and for
  `config/**/*.xml`, `docs/**/*.css`, `docs/**/*.html`, `docs/**/*.md`, `design/**/*.md`,
  `orchestration/**/*.py`, `tests/**/*.py`, `tests/**/*.properties`, `cicd/**/*.py` and
  `cicd/**/*.sh`.

The patterns are relative to each project's base directory. A module therefore checks its own
sources and POM, and the root project checks the root files, `config/`, `docs/`, `design/`,
`orchestration/`, `tests/` and `cicd/`. Files no pattern matches, such as those under `deploy/`
and `.github/` and the JavaScript test under `tests/`, rely on
[`.editorconfig`](../.editorconfig): UTF-8, LF, a final newline, no trailing whitespace, 4-space
indents, 2-space indents for Java and Scala (as Spotless formats them), tab-indented Makefile
recipes and CRLF for `*.cmd` and `*.bat`, which [`.gitattributes`](../.gitattributes) also checks
out with CRLF (everything else is LF).

`./mvnw spotless:apply` (or `make format`) rewrites the files instead of checking them.

## Checkstyle

maven-checkstyle-plugin 3.6.0 runs the Checkstyle engine pinned through `checkstyle.version`
(14.3.0) as a plugin dependency. The plugin's default engine (9.3) cannot parse Java 21 and 25
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
| spark, cli | ScalaTest 3.2.20 | scalatest-maven-plugin 2.2.0; Surefire skipped in the module POM |

- **Surefire** uses `argLine ${test.jvm.args}`, `excludedGroups ${test.excluded.tags}`,
  `failIfNoTests ${surefire.failIfNoTests}` (true), full stack traces (`trimStackTrace` false)
  and the class path (`useModulePath` false).
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

### Skipping tests

Two Maven flags skip tests, and both must keep working:

| Flag | Effect | Used by |
| --- | --- | --- |
| `-DskipTests` | compiles the tests, does not run them | `make package`, `build-jar.sh`, `build.ps1` |
| `-Dmaven.test.skip=true` | neither compiles nor runs them | `Dockerfile.build`, whose build context has no `tests/` |

- Surefire follows both flags through the defaults of its own `skip` and `skipTests` parameters,
  so the root configuration sets no `<skip>`. An explicit value there, even one that resolves to
  `false`, replaces the default `${maven.test.skip}`: Surefire then runs in a tree without tests
  and fails the build with "No tests to run!" (`failIfNoTests`). That is how the builder image
  broke on 2026-09-26. The ScalaTest-only modules set a literal `<skip>true</skip>`, which can
  only skip.
- scalatest-maven-plugin reads only `skipTests`. The `maven-test-skip` profile, activated by
  `maven.test.skip=true`, sets that property, so the plugin does not start and warn about a
  missing `target/test-classes`.

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

The JVM suites have 300 test cases.
[`cicd/build/check_test_counts.py`](../cicd/build/check_test_counts.py) reads each module's
`target/surefire-reports/TEST-*.xml`, counts executed tests (skipped, aborted and canceled ones do
not count) and fails when a module is below its floor. Floors are minimums: adding tests needs no
change, removing tests needs a lower floor in the same commit. A test in `tests/ci` checks that
the script's module map matches the POM's `<modules>`.

| Module | Linux executes (CI floor) | Windows executes (Windows floor) | Tagged for the other OS |
| --- | --- | --- | --- |
| `datacraft-common` | 7 | 7 | none |
| `datacraft-config` | 13 | 13 | none |
| `datacraft-io` | 57 | 59 | 7 `windows-only`, 5 `posix-only` |
| `datacraft-engine` | 32 | 32 | none |
| `datacraft-jobs` | 70 | 68 | 2 `posix-only` |
| `datacraft-spark` | 56 | 47 | 9 `posix-only` |
| `datacraft-api` | 27 | 27 | none |
| `datacraft-cli` | 31 | 31 | none |
| Total | 293 | 284 | |

The script keeps a set of floors per platform, because each platform excludes the other's tagged
tests: `--platform` names the platform that ran the tests and defaults to the one the script runs
on. CI therefore checks the Linux floors, and the same command on a Windows workstation checks
the Windows ones, where it used to report two modules below their Linux floors. On a runner a
passing check also records the counts as a notice annotation (see [CI gates](#ci-gates)).

### Python and Node suites

| Suite | Location | Needs | Runs in |
| --- | --- | --- | --- |
| DAG contracts | `tests/orchestration` | Airflow 3.3.2 and providers | CI `dags`; `make dags` |
| Real pipelines | `tests/smoke/airflow_runtime_smoke.py` | Airflow 3.3.2, pyspark 4.2.0, the jar | CI `dags` and `containers` |
| Images and stack | `cicd/images/smoke-images.sh`, `cicd/images/smoke-compose.sh` (pipeline steps, not test suites) | Docker; `smoke-compose.sh` refuses to run outside CI | CI `containers` |
| Deployment scripts and templates | `tests/deploy` | Python standard library | CI `scripts`; `make test-scripts` |
| CI/CD scripts, workflow structure and build settings | `tests/ci` | Python standard library; Bash for the tests that run a `cicd/` shell script (skipped on Windows) | CI `scripts`; `make test-scripts` |
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
  `maven-wrapper.jar`. The scripts download Maven 3.10.0 (`bin.zip`) and verify it against
  `distributionSha256Sum`; a mismatch stops with "Failed to validate Maven distribution SHA-256".
  The pinned value was computed from a download whose SHA-512 matched the one published on both
  Maven Central and downloads.apache.org.
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
- [`.mvn/maven.config`](../.mvn/maven.config) holds one option for every Maven run of this build,
  `-Daether.remoteRepositoryFilter.prefixes.resolvePrefixFiles=false`. Maven 3.10 brings Resolver
  2, which by default asks every remote repository it meets for a prefix file, including the
  repositories that only a dependency's POM names. On this build that meant requests to half a
  dozen third-party hosts on each online build, and a `[WARNING]` whenever one of them was
  unreachable or, like `maven.java.net`, served an expired certificate, so a clean build depended
  on servers the project does not use. With the option Maven resolves as 3.9 did. Both versions
  compute the same class path, in the same order, for every module.
- The builder image `deploy/docker/Dockerfile.build` uses `maven:3.10.0-eclipse-temurin-25`, the
  wrapper's Maven version; a test in `tests/deploy` keeps the two equal, and another one requires
  the image to copy `.mvn/` before its first Maven run, so the option above applies there too. It
  builds from the Docker build context, not from the checkout:
  `cicd/build/check-image-build.sh` runs its Maven command in a copy of that context.
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
Every step runs one command: a script in `cicd/`, grouped by the job that runs it (`build/`,
`airflow/`, `lint/`, `stacks/`, `images/`, with shared helpers in `cicd/lib.sh`), a test entry
point in `tests/`, or a single tool (`./mvnw -B -ntp verify`, pyright, a clean-up `rm`). The
scripts therefore run and can be reviewed outside GitHub Actions. The shell scripts report through
`fail` in `cicd/lib.sh`: an `::error::` annotation on a runner, standard error elsewhere; the
Python checks print `::error::` lines on standard output everywhere.

A job log can be read only with access to the repository, whereas the annotations of a run are
public (`GET /repos/{owner}/{repo}/check-runs/{job id}/annotations`). The pipeline therefore puts
what a reader needs into annotations:

- **Failures.** The workflow sets `defaults.run.shell` to `python3 -B cicd/step.py bash -e {0}`, so
  every `run:` step goes through [`cicd/step.py`](../cicd/step.py). It runs the step with
  `bash -e`, the shell the runner would use anyway, passes the output through unchanged and keeps
  the exit status. When the step fails on a runner it adds one `::error` annotation: the job and
  the step's command in the title, and in the message the first lines that name a failure (Maven
  and Docker errors, failed JUnit, ScalaTest and unittest tests, exception headers) followed by
  the last 30 lines of output, within the 4096 characters the runner keeps. Without it, a failed
  step left only "Process completed with exit code 1.", which is all the two failed runs of
  2026-09-26 showed. The script is part of the checkout, so the checkout is the first step of
  every job; `tests/ci/test_workflow.py` requires both.
- **The state behind a failure.** A stack that failed is removed before the step ends, so the smoke
  scripts first collect what explains it: `diagnose TITLE COMMAND...` in `cicd/lib.sh` prints a
  command's output as a named group of the log (`::group::` and `::endgroup::`, which the runner
  folds), and `cicd/step.py` repeats the last eight groups of a failed step as annotations of
  their own, each under its title and within its own 4096 characters. `smoke-compose.sh` reports
  the log of each service, the Airflow task logs and the container states this way. The lines of
  a group stay out of the step's own annotation, which therefore shows what the step printed
  before it failed; the teardown itself prints nothing. Eight groups, the step's annotation and
  the one from `fail` are the ten error annotations a runner keeps for a step.
- **Messages that span lines.** `fail` escapes `%`, CR and LF, so a message with a line break stays
  one annotation instead of ending at the first line.
- **Evidence of a passing run.** `notice` in `cicd/lib.sh` records what a check established:
  `check_test_counts.py` the number of tests each module executed, `smoke-images.sh` the Java
  runtime of the images and what was verified, `smoke-compose.sh` the stack checks. Outside a
  runner the same text is ordinary output.
[`tests/ci/test_workflow.py`](../tests/ci/test_workflow.py) keeps the split: a `run:` step must be
a single command (no block scalar and no shell operator such as `&&`, `||`, `;` or `|`), the
scripts the workflow and the Makefile name must exist, every file in `cicd/` must be used by the
workflow or by another `cicd/` script (a mention in a comment does not count), tests stay out of
`cicd/`, and a shell script outside `cicd/` and `deploy/scripts/` fails the test.

`cicd/` holds what a pipeline job runs, `tests/` the suites a test runner or a developer runs, and
`deploy/scripts/` what an operator runs on the host; the image smoke test in `cicd/images` calls
the operator's `build-images.sh`, and nothing under `deploy/` depends on `cicd/`.

The workflow runs on pushes and pull requests to `main` and on demand, with read-only repository
permissions; a newer run on the same ref cancels the older one. Every job runs on `ubuntu-24.04`,
and every action is pinned to a full commit SHA with the release in a comment: `checkout` v7.0.1,
`setup-java` v6.0.1, `setup-python` v7.0.0, `setup-node` v7.1.0, `upload-artifact` v7.0.2 and
`download-artifact` v8.0.2. The `scripts` job fails on any `uses` that is not written as
`uses: owner/repo@<40-character SHA> # vX.Y.Z` or does not name a local `./` action
(`cicd/lint/check-actions-pinned.sh`). The check reads lines, not YAML, so it also fails on
anything it could misread: another spelling of the key (flow style, a quoted or explicit key, the
value on the next line), a `uses:` in a comment, which may be the continuation of a quoted scalar,
and a key it cannot read (an escape in a quoted key, an alias as a key).

`setup-java` in the `build` job sets `check-latest: true`: the build enforces a JDK patch floor,
and without it the action would use the Temurin 25 preinstalled on the runner image even when a
newer release exists. The `containers` job, the longest, declares `needs: [build, scripts,
compose]`, so it does not start while a cheaper gate is failing.

| Job | What it gates |
| --- | --- |
| `build`: Build & verify (JDK 25) | Temurin 25; `cicd/build/check-maven-wrapper.sh`: the wrapper must refuse a Maven download with a wrong SHA-256 (a copy with a zeroed `distributionSha256Sum` and an empty `MAVEN_USER_HOME`), and no `maven-wrapper.jar` may be committed; `./mvnw -B -ntp verify`; the test floors (`cicd/build/check_test_counts.py`); `cicd/build/check-spark-suite.sh`: the Spark suite must have run `SparkPipelineSpec` with `canceled 0`; the jar and exit-code checks and the build-context check below. |
| `dags`: Airflow contracts and real pipelines (3.3.2) | Python 3.13, the version the `apache/airflow:3.3.2` image runs; `cicd/airflow/install-airflow.sh`: `apache-airflow==3.3.2` and the providers under the official `constraints-3.3.2` file for the running Python (the script derives the version, as `Dockerfile.airflow` does, and a test in `tests/docs` keeps it from being fixed), `pip check`, then pyspark 4.2.0; `cicd/airflow/check_security_floor.py`: apache-airflow 3.3.2 and FAB provider 3.9.0 at least; `tests/orchestration`; `tests/smoke/airflow_runtime_smoke.py` against the jar built by `build`, with pyspark 4.2.0's `spark-submit`. |
| `scripts`: Script, documentation and type checks | `cicd/lint/check-actions-pinned.sh`; Python 3.13 and Node 24; `cicd/lint/install-shellcheck.sh`: ShellCheck 0.11.0 from the PyPI package `shellcheck-py`, so the result does not depend on the runner image; `cicd/lint/check-shell-scripts.sh`: `bash -n` and ShellCheck on every `*.sh` git knows (tracked or not yet ignored), with sourced files followed and every finding fatal; `tests/deploy`, `tests/ci` and `tests/docs`; `node --test tests/docs/cloud-costs.test.cjs`; pyright 1.1.414 with `--warnings`. |
| `compose`: Compose / Swarm file validation | `cicd/stacks/check-stack-files.sh`: `docker compose config` and `docker stack config` with test-only secrets; for each required secret, the API token included, interpolation must fail when it is missing. Nothing is built or deployed; the script refuses to run while `deploy/compose/.env` exists, because it writes that file from `.env.example`. |
| `containers`: Build and smoke-test runtime images | Runs after `build`, `scripts` and `compose`. `cicd/images/smoke-images.sh`: builds the images; the build context must exclude `.env` and `backups/`; both runtime images carry the freshly pulled Temurin JRE, at least 25.0.4.1; the API image refuses to start without `DATACRAFT_API_TOKEN`, and with one it answers 401 to a `/jobs` request without or with a wrong token; it runs as non-root, cannot modify its jar and answers `spark-version` with HTTP 500 FAILED; `pip check` and `cicd/airflow/check_security_floor.py` inside the Airflow image; a fresh data volume is writable by an Airflow task and read-only for the API; the real pipelines inside the image, a GBK file converted with `encoding=GBK` among them. `cicd/images/smoke-compose.sh`: the full Compose stack with generated secrets, the generated API token opening `datacraft-api` on its loopback port, all three image-baked DAGs registered, a triggered `datacraft_engine_jobs` run through the scheduler and LocalExecutor, and a `pg_dump` backup restored into a separate database that must contain the successful run; a failure prints the task logs before the stack is removed. |

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

The build-context check, also in the `build` job:

- `docker build` sends only the build context, the checkout minus what `.dockerignore` excludes,
  so the builder image compiles a tree that `./mvnw verify` never sees: no `tests/`, `docs/`,
  `design/` or `cicd/`.
- `cicd/build/check-image-build.sh` copies that context with
  [`docker_context.py`](../cicd/build/docker_context.py), which applies `.dockerignore` with the
  rules of Docker's pattern matcher, reads the Maven arguments from `Dockerfile.build` itself,
  runs them in the copy (offline, from the local Maven repository that `verify` filled) and
  requires the jar.
- A build that only works with the full checkout therefore fails in `build`, with Maven's own
  error, instead of inside the image build of the `containers` job. It needs no Docker, so it
  also runs on a workstation without one.
- [`tests/deploy/test_dockerignore.py`](../tests/deploy/test_dockerignore.py) uses the same
  matcher and requires every path a Dockerfile copies from the context to exist in it;
  [`tests/ci/test_docker_context.py`](../tests/ci/test_docker_context.py) tests the copy and
  the argument parsing.

On failure the job uploads the test reports; on success it shares the verified jar with the
`dags` job for one day.

### Advisory check

Pinning every version keeps the build reproducible, and also keeps a version in place after an
advisory is published for it. A second workflow,
[`advisories.yml`](../.github/workflows/advisories.yml), therefore runs
[`cicd/security/check_advisories.py`](../cicd/security/check_advisories.py) every Monday, on demand
and on a push that changes a file which pins a version:

- It reads the pins from the files that hold them: the dependencies the root POM manages (the
  Jackson BOM stands for `jackson-core` and `jackson-databind`), `apache-airflow` and `pyspark`
  from `install-airflow.sh`, the FAB provider floor from `check_security_floor.py`, `shellcheck-py`,
  `pyright` from the Makefile, and every action at the release its pinned commit is commented
  with. A file in which no pin is found is an error, so a reworded pin cannot drop out unnoticed.
- It asks [OSV](https://osv.dev) about each pin. OSV matches versions itself for Maven, PyPI and
  npm; for GitHub Actions it only lists advisories, so the script evaluates their version ranges
  the way the OSV schema describes.
- Exit status 0 means no pin has an advisory, 1 that one has (one `::error::` line per pin, with
  links), and 2 that OSV could not be asked, so a check that did not run never counts as a pass.

It does not see transitive dependencies, which Spark and the Airflow constraints file pin, or the
base images. No bot opens pull requests: a failed scheduled run is the signal, and the fix is an
ordinary commit that raises the pin (decision 13 in [decisions.md](decisions.md)).

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
| Resolver warnings about third-party repositories | prefix discovery switched off in `.mvn/maven.config` | configuration; a test in `tests/deploy` keeps the option and the builder image's copy of `.mvn/` |
| JVM warnings in Spark test JVMs | `spark.test.jvm.args` | configuration |
| Log noise from provoked failures | handler-less java.util.logging, Log4j 2 level `off`, `slf4j-nop`, the MINA transport for the SFTP test server | configuration |
| Skipped and canceled tests | platform tags and host profiles | CI counts executed tests only and fails a canceled Spark suite |
| Python | pyright configuration | CI and `make typecheck` fail on any pyright error or warning (`--warnings`) |
| Shell | ShellCheck 0.11.0 over every shell script git knows, following sourced files; every finding fails, style notes included | CI (`cicd/lint/install-shellcheck.sh` installs that release); `make lint` |
| Pipeline logic | a `run:` step is one command; logic lives in `cicd/`; every step runs through `cicd/step.py` | `tests/ci/test_workflow.py` |

A clean `./mvnw verify` on JDK 25.0.4.1 prints no Maven or JVM warning at all.

## Related documents

- [architecture.md](architecture.md): modules, boundaries and the runtime model.
- [decisions.md](decisions.md): why the build baseline and the test layout look like this.
- [Development guide](../docs/guides/development.md): building and testing day to day.
