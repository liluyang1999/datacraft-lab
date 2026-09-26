# Design documentation

This directory explains how datacraft-lab is built and why. The documents describe the code in
this repository; when a document and the code disagree, the code is right and the document needs
fixing. How to install, run and operate the project is in [`docs/`](../docs/README.md).

| Document | Contents |
| --- | --- |
| [architecture.md](architecture.md) | Repository layout, module responsibilities and their enforced dependency rules, the unified job model, packaging, the online engine, deployment topology and how to extend the platform. |
| [data-contracts.md](data-contracts.md) | What callers can rely on: failure results, Spark and plain-JVM job parameters and messages, CSV rules, write modes, data-root confinement, IO boundaries, the HTTP status contract and the tests that pin them. |
| [build-and-quality.md](build-and-quality.md) | The Maven reactor, source and test layout, version management, compiler, formatting, Checkstyle and Enforcer settings, testing, packaging, the Maven Wrapper, Python type checking, CI gates and the zero-warning policy. |
| [decisions.md](decisions.md) | The architecture decision log: context, decision and consequences of the choices behind the current design, including the cloud platform. |

## Design principles

1. **One job contract.** Every runnable unit is an `engine.DataJob`. The CLI, the HTTP API and
   Airflow reach it through one catalog and one `JobExecutionEngine`, so validation, failure
   handling and the result shape are the same for every caller.
2. **Thin interfaces.** `datacraft-cli` parses arguments and composes the catalog,
   `datacraft-api` adapts HTTP, and Airflow only orchestrates the built jar. None of them holds
   business logic.
3. **Ports and adapters for IO.** Jobs depend on the `StorageService` and `RemoteFileTransfer`
   ports and the engine on the `JobCatalog` port; `LocalStorageService`, `SftpClient` and
   `JobRegistry` are replaceable implementations.
4. **Spark is provided.** `spark-submit` or a cluster supplies Spark at runtime, so the CLI jar
   stays small, and a missing Spark runtime is a FAILED result that says how to launch the job, not
   a crash.
5. **Fail visibly.** Invalid values, case variants of parameter names, malformed CSV and
   quality-gate mismatches fail the job with a message instead of falling back to a default. The
   build does the same: vanished tests, a canceled Spark suite or an unpinned CI action fail it.
6. **Confine file access.** `DATACRAFT_DATA_ROOT`, the root-bound `LocalStorageService`, the
   Airflow path parameters, the read-only data mount of the API and its loopback binding keep job
   paths inside one known directory.
7. **Zero-warning builds.** Compiler warnings fail the build, formatting and Checkstyle are gates,
   and every other source of warnings is removed at its cause, so a new warning stands out.

## Related documents

- [Project README](../docs/README.md): overview and quick start.
- [Usage guide](../docs/guides/usage.md): the CLI, the HTTP API and the jobs.
- [Development guide](../docs/guides/development.md): building, testing and changing the code.
- [Airflow guide](../docs/guides/airflow.md): the DAGs and their configuration.
- [Deployment guide](../docs/guides/deployment.md) (Chinese): Compose and Swarm deployment,
  operations, backup and restore.
- [Evaluation report](../docs/reports/evaluation-report.html) (Chinese): the current assessment
  with architecture diagrams, verification evidence, the cloud platform analysis with its cost
  model and calculator, and the residual risks.
- [Pricing evidence](../docs/reports/pricing-evidence.md) (Chinese): price sources, SKUs and
  formulas.
- [Changelog](../docs/CHANGELOG.md): the history of changes.
