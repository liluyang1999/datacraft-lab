# Using datacraft-lab

This guide covers running jobs: the CLI jar, `spark-submit`, `serve-api` and the HTTP API. Every
path below runs the same engine, so a job behaves the same whichever way it starts. Parameter rules,
metrics and failure messages are specified in
[data contracts](../../design/data-contracts.md); the Airflow DAGs, which launch this jar, are
described in [airflow.md](airflow.md).

## The CLI jar

```bash
./mvnw -B -ntp -pl :datacraft-cli -am package -DskipTests   # or: make package
JAR=modules/interfaces/datacraft-cli/target/datacraft-cli.jar
```

The shaded jar's main class is `com.example.datacraft.cli.Runner`. Spark is a `provided`
dependency and is not in the jar: plain `java -jar` runs the built-in and plain-JVM jobs, and Spark
jobs run under `spark-submit`. The examples below use the `JAR` variable and this sample file:

```bash
printf 'id,note\n1,"a, b"\n2,c\n' > sample.csv
```

Every invocation names one command:

```text
java -jar "$JAR" --command <job | list-jobs | serve-api> [options]
```

## Jobs

`java -jar "$JAR" --command list-jobs` prints the catalog, one line per job: the name, a tab and the
description, sorted by name.

| Job | Runs on | `list-jobs` description | Parameters |
| --- | --- | --- | --- |
| `csv-profile` | plain JVM | Profiles a small UTF-8 CSV file in the JVM: rows, columns, bytes and SHA-256. | `input`, `header`, `delimiter`, `expectedRows`, `maxBytes` |
| `csv-to-parquet` | Spark | Converts a CSV dataset to Parquet. | `input`, `output`, `mode`, `header`, `delimiter`, `schema`, `inferSchema`, `multiLine`, `escape` |
| `echo` | built-in | Returns the message parameter. | `message` |
| `file-checksum` | plain JVM | Computes the SHA-256 checksum and size of a file in the JVM. | `input`, `expectedSha256` |
| `noop` | built-in | Confirms the engine is reachable without processing data. | none |
| `row-count` | Spark | Counts rows in a dataset. | `input`, `inputFormat`, `expectedRows`, `schema`; for `inputFormat=csv` also `header`, `delimiter`, `inferSchema`, `multiLine`, `escape` |
| `spark-version` | Spark | Reports the running Spark runtime version. | none |

The Spark jobs also read the runtime settings `spark.master`, `spark.appName`,
`spark.shufflePartitions`, `spark.warehouseDir` and `spark.enableHive`. Defaults, validation rules,
metrics and messages are specified in
[Spark job parameters](../../design/data-contracts.md#spark-job-parameters) and
[Plain-JVM small-file jobs](../../design/data-contracts.md#plain-jvm-small-file-jobs).

## Options

| Option | Used by | Default | Meaning |
| --- | --- | --- | --- |
| `--command <name>` | all | required | A job name, or the control command `list-jobs` or `serve-api`; must not be blank |
| `--param key=value` | jobs | none | One job parameter; repeatable. Overrides `--config` and `--master` |
| `--config <file>` | jobs | none | UTF-8 `java.util.Properties` file merged into the job parameters |
| `--master <url>` | jobs | none | Spark master, such as `local[4]` or `spark://host:7077`; must not be blank |
| `--lifecycle <name>` | jobs | `dev` | Lifecycle label passed to the job: `dev` or `prod` |
| `--json` | jobs | off | Print the result, including metrics, as one JSON line |
| `--result-file <file>` | jobs | none | Also write that JSON line to a file |
| `--host <host>` | `serve-api` | `127.0.0.1` | Interface the HTTP API binds to |
| `--port <port>` | `serve-api` | `8080` | HTTP API port, 0 to 65535 |

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success. |
| `1` | The job failed, or its `--result-file` could not be written (the result is still printed). |
| `2` | Invalid usage: a parse error (including job options given to a control command), an unknown command or job, or an unreadable or malformed `--config` file. |

`serve-api` also exits `2` when it refuses a non-loopback `--host` (see
[Serving the API](#serving-the-api)). A job that rejects its parameters is a failed job, not a
usage error: it exits `1` and still prints its FAILED result. Usage errors are reported on stderr,
for example `Unknown command or job: <name>`.

### Parameters and `--config`

- `--param key=value` sets one job parameter; repeat it for more. The value is everything after the
  first `=`, may be empty, and is passed verbatim. When a key repeats, the last value wins.
- Parameter names are case-sensitive. The Spark and plain-JVM jobs reject a key that differs from
  one of their parameters only by case, for example "Unknown parameter expectedrows; did you mean
  expectedRows?". Other unknown keys are ignored, so one `--config` file can serve several jobs.
- `--config <file>` merges a `java.util.Properties` file into the job parameters. The file is read
  as UTF-8 and one leading byte order mark is ignored. Keys are trimmed and must not be blank;
  values are kept verbatim, as `Properties` unescapes them.
- Backslash is an escape character in a Properties file and a trailing backslash continues the
  line, so write Windows paths as `C:/data/in.csv` or `C:\\data\\in.csv`.
- A `--config` file that cannot be read or parsed stops the command before the job runs: one stderr
  line, `Invalid --config <path>: <cause>`, and exit code `2`.
- Precedence, highest first: `--param`, then `--master` (as `spark.master`), then the `--config`
  entries.

```bash
printf 'input=sample.csv\nexpectedRows=2\n' > profile.properties
java -jar "$JAR" --command csv-profile --config profile.properties --param header=true
```

### Output: `--json` and `--result-file`

Without `--json`, a job prints one line, `<job> <STATUS> <message>`, for example
`echo SUCCEEDED hello`.

- `--json` prints the result as one JSON line instead: `jobName`, `status` (`SUCCEEDED` or
  `FAILED`), `message` and `metrics`, whose values are strings and include `durationMillis`. The
  line is UTF-8 and ends in LF on every platform.
- `--result-file <path>` writes that same line, byte for byte, to a caller-owned file, with or
  without `--json`. Missing parent directories are created and an existing file is replaced. The
  result is printed first; when the file cannot be written, stderr reports
  `Failed to write --result-file <path>: <cause>` and the exit code is `1`. Airflow's Spark tasks
  read their results this way.

### Spark settings

- Master, highest precedence first: `--param spark.master`, `--master`, `spark.master` in
  `--config`, the `spark-submit --master` value, then `local[*]`. Without an explicit master, a
  Spark job therefore runs on the master that `spark-submit` names.
- Shuffle partitions: the `spark.shufflePartitions` job parameter (from `--param` or `--config`),
  then `spark-submit --conf spark.sql.shuffle.partitions=<n>`, then `8`.
- The application name is `datacraft-lab-<job>` unless `--param spark.appName=<name>` sets it;
  `spark-submit --name` is not used.

### Lifecycle

`--lifecycle` accepts `dev` or `prod` (also `development` or `production`, in any case) and defaults
to `dev`; any other value is a parse error. It is a label carried in the job request, and no job
reads it yet. Over HTTP the same label is the `lifecycle` query parameter.

### Control commands

- `list-jobs` prints the catalog and `serve-api` starts the HTTP API. Every other `--command` value
  is dispatched through the engine as a job.
- Control commands reject job options: `--config`, `--master`, `--lifecycle`, `--param`, `--json`
  or `--result-file` on `list-jobs` or `serve-api` is a parse error ("<command> does not accept job
  options: ...") and exits `2`.
- `--host` and `--port` are used only by `serve-api`; jobs ignore them.
- A job name cannot contain `/`, because it is one path segment of `POST /jobs/{name}/runs`, or
  equal a control command. The catalog refuses such a job when it is built.

## Spark jobs with `spark-submit`

Under plain `java -jar`, a Spark job that passes its parameter checks returns FAILED (exit `1`) with
"Spark runtime is not on the classpath (missing <class>); launch Spark jobs with spark-submit".
Launch Spark jobs with `spark-submit` from Spark 4.2.0, the version the build compiles against,
running on Java 25, because the jar targets Java 25 bytecode. The `spark-submit` installed by the
Python package `pyspark==4.2.0` works; the Airflow image and CI use it.

```bash
spark-submit --master 'local[*]' --class com.example.datacraft.cli.Runner "$JAR" \
  --command csv-to-parquet --param input=sample.csv --param output=sample.parquet --json
spark-submit --master 'local[*]' --class com.example.datacraft.cli.Runner "$JAR" \
  --command row-count --param input=sample.parquet --param expectedRows=2
```

- A job inherits the `spark-submit --master` unless the CLI is given a master (see
  [Spark settings](#spark-settings)). To pin both, pass the same `--master` to `spark-submit` and
  to the CLI, as the Airflow `spark_task` does.
- `csv-to-parquet` needs a literal input file or directory: an input containing any of
  `{ } [ ] * ?` or a backslash is rejected (on Windows, Hadoop first turns backslash separators
  into `/`). Input and output must not overlap, and the default write mode, `overwrite`, replaces
  the output.
- On Windows, Hadoop's local file system needs `winutils.exe` (`HADOOP_HOME` or `hadoop.home.dir`)
  to write files: provide it, or run writing jobs such as `csv-to-parquet` on Linux, macOS or WSL.

### Without `spark-submit`: the `bundled-spark` profile

For laptop-scale experiments, this profile switches Spark to `compile` scope, so the shaded jar
bundles Spark and becomes large:

```bash
./mvnw -Pbundled-spark -pl :datacraft-cli -am package
```

A JVM that embeds Spark needs the module options that `spark-submit` normally adds; the root
`pom.xml` lists them in the `spark.test.jvm.args` property.

## Serving the API

```bash
java -jar "$JAR" --command serve-api                  # binds 127.0.0.1:8080
java -jar "$JAR" --command serve-api --port 9090
DATACRAFT_DATA_ROOT=/srv/datacraft/data \
  java -jar "$JAR" --command serve-api --host 0.0.0.0 --port 8080
```

`serve-api` prints `datacraft-api listening on http://<host>:<port>/` and serves until the process
stops. On shutdown it stops accepting connections and waits up to 8 seconds for in-flight requests;
runs still going after that are cut off.

The API has no authentication and its file jobs read local paths, so `serve-api` binds a
non-loopback `--host`, such as `0.0.0.0`, only when `DATACRAFT_DATA_ROOT` is set and not blank.
Otherwise it exits `2` with one stderr line that begins
`serve-api refuses --host <host> without DATACRAFT_DATA_ROOT`. `127.0.0.1`, `::1` and `localhost`
are loopback; a host name that does not resolve counts as non-loopback.

`DATACRAFT_DATA_ROOT` confines job paths for the CLI and the API alike:

- The `input` of `csv-profile` and `file-checksum`, and the `input` and `output` of
  `csv-to-parquet`, must lie strictly inside the root. `row-count`, `spark-version`, `echo` and
  `noop` are not confined.
- The value is trimmed and read when the process builds its job catalog, at startup. Unset or blank
  means unconfined, which suits local CLI use.
- The root must be an absolute path; the plain-JVM jobs also require an existing directory and
  fail their runs otherwise.
- The `datacraft/jvm` image sets `DATACRAFT_DATA_ROOT=/opt/datacraft/data` and starts `serve-api`
  on `0.0.0.0:8080`. In the Compose stack that port is published on `127.0.0.1` only, as
  `DATACRAFT_API_PORT` (default `8088`).

The complete rules are in
[Data root](../../design/data-contracts.md#data-root-datacraft_data_root).

## HTTP API

| Request | Response |
| --- | --- |
| `GET` or `HEAD /health` | 200 `{"status":"UP","service":"datacraft-api"}` |
| `GET` or `HEAD /jobs` | 200 `{"jobs":[{"name":...,"description":...}]}` |
| `POST /jobs/{name}/runs?lifecycle=dev&key=value` | The job result JSON: 200 when the job SUCCEEDED, 500 when it FAILED |

- Query parameters other than `lifecycle` become job parameters, and `lifecycle` defaults to `dev`.
  Keys and values must be percent-encoded UTF-8; `+` decodes to a space. The request body is not
  read.
- A run answers with the same result JSON as the CLI's `--json`. A parameter rejection is a FAILED
  run, so it answers 500 with the result body.
- Errors are `{"error":"<code>"}`: 400 `invalid_request` for a malformed job path, an unknown
  `lifecycle` or a query that is not percent-encoded UTF-8; 403 `cross_origin_forbidden` for a run
  request that carries an `Origin` header, which browsers send; 404 `unknown_job` or `not_found`;
  405 `method_not_allowed` with an `Allow` header; 500 `internal_error` when a handler fails
  unexpectedly.
- There is no authentication: keep the API on loopback or behind an authenticated gateway.

```bash
curl -s http://127.0.0.1:8080/jobs
curl -s -X POST 'http://127.0.0.1:8080/jobs/echo/runs?message=hello'
curl -s -X POST -G --data-urlencode 'input=sample.csv' --data-urlencode 'expectedRows=2' \
  http://127.0.0.1:8080/jobs/csv-profile/runs
```

The full contract, including the order of the checks and the JDK server's own 400 responses, is
the [HTTP status contract](../../design/data-contracts.md#http-status-contract).
