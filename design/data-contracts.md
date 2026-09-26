# Data contracts

This document specifies what callers of the jobs can rely on: results and failures, parameters and
their messages, metrics, write modes, path confinement, IO boundaries and the HTTP status codes.
It describes the code in `modules/`; the tests that pin each contract are listed at the end. The
module structure behind it is in [architecture.md](architecture.md).

The same `DataJob` runs through CLI, HTTP, and Airflow. Spark jobs (`spark-version`,
`csv-to-parquet`, `row-count`) require the Spark runtime, normally `spark-submit`. On a plain JVM,
such as the `datacraft/jvm` image, they return FAILED with "Spark runtime is not on the classpath
(missing <class>); launch Spark jobs with spark-submit", where `<class>` is, for example,
`org/apache/spark/sql/SparkSession`. That hint appears only when Spark itself cannot be loaded:
under `spark-submit`, a missing class such as a file-system connector built for another Hadoop keeps
its original `NoClassDefFoundError`. The plain-JVM jobs `csv-profile` and `file-checksum` need no
Spark: `csv-profile` profiles small CSV files in memory and `file-checksum` streams a file of any
size. Managed Spark jobs in one JVM serialize session ownership, so one request cannot stop another
request's context. An externally owned active session is rejected ("requirement failed: A managed
Spark job requires exclusive session ownership") and left running.

## Failure contract

A run always ends in a result with `jobName`, `status`, `message` and `metrics`; the engine's
result factories add `durationMillis` to the metrics. `JobExecutionEngine` turns an unknown job
name, a `null` result and every `Throwable` a job throws into a FAILED result; only
`VirtualMachineError`s (`OutOfMemoryError`, `StackOverflowError`, `InternalError`) propagate. The
message is the exception's message, or its fully qualified class name when the message is blank.
For an `Error` it is `SimpleClassName: message` (the simple name alone when the message is blank),
e.g. `NoClassDefFoundError: org/apache/spark/sql/SparkSession`. Each such throwable is logged once
at `ERROR` as "Job <name> failed" with its stack trace through `System.Logger` (java.util.logging,
on stderr, by default); stdout JSON and `--result-file` do not change. A FAILED result that a job
returns itself, such as a quality-gate mismatch of a plain-JVM job or the Spark runtime hint above,
and a `null` result are not logged.

Parameter rejections are FAILED results too, not usage errors: the CLI exits 1 and still prints
the `--json` result and writes `--result-file`, and the HTTP API answers 500 with the result body.
Spark messages raised by Scala `require` carry the prefix `requirement failed: `; messages quoted
below without it have none.

## Spark job parameters

Spark jobs check their parameters before a SparkSession starts, in this order:

1. Parameter names are case-sensitive. A key that differs from a Spark job parameter only by case
   fails with "Unknown parameter <key>; did you mean <name>?", for example for `inferschema`,
   `Schema`, `expectedrows`, `Mode` or `SPARK.MASTER`. Correctly spelled unknown keys are still
   ignored, so one `--config` file can serve several jobs.
2. The runtime settings. `spark.shufflePartitions` must be a 32-bit integer >= 1
   ("spark.shufflePartitions must be a 32-bit integer >= 1"); without that parameter, a malformed
   launcher `spark.sql.shuffle.partitions` fails with the same message under that name;
   `spark.enableHive` must be true or false; and a blank `spark.appName` fails with "requirement
   failed: Spark appName must not be blank.".
3. The job's own parameters: "Missing required parameter: <key>", "requirement failed: Invalid
   write mode", "<key> must be true or false", "requirement failed: delimiter must be nonempty and
   must not contain quotes, newlines or NUL", "requirement failed: escape must contain exactly one
   character", the glob rule below, and the `row-count` rules in
   [Write and verification behavior](#write-and-verification-behavior).

Data-root confinement, the input/output overlap guard and the existing-output check need Spark's
Hadoop configuration, so they run after the session starts. On a JVM without Spark, `row-count`
still reports every parameter error first. `csv-to-parquet` reports case variants, missing
`input`/`output`, an invalid mode and CSV option errors first, but its glob check needs Hadoop
classes, so a glob input gets the spark-submit hint there.

`csv-to-parquet` input must be a literal file or directory path. An input containing any of
`{ } [ ] * ?` or a backslash fails with "requirement failed: input must be a literal file or
directory path; glob patterns cannot be overlap-checked". On Windows, Hadoop converts backslash
separators to `/` before this check.

Master precedence: `--param spark.master` > `--master` > `spark.master` in `--config` >
`spark-submit --master` > `local[*]`. Shuffle partitions: `spark.shufflePartitions` (`--param`
over `--config`) > `spark-submit --conf spark.sql.shuffle.partitions` > 8. `spark.master`,
`spark.appName` and `spark.warehouseDir` are trimmed, and a blank `spark.master` parameter falls
back to the launcher or `local[*]`. Without an explicit master a job therefore runs on whatever
master `spark-submit` names. The application name is never taken from `spark-submit --name`: it
defaults to `datacraft-lab-<job name>`; set it with `--param spark.appName`.

## CSV and numeric precision

`csv-to-parquet` accepts `input`, `output`, `mode`, `header`, `delimiter`, `schema`, `inferSchema`,
`multiLine`, and `escape`. CSV defaults are header=true, delimiter=comma, inferSchema=true,
multiLine=true, and RFC double-quote escaping. The delimiter may have several characters (for
example `||`) but must not contain a double quote, CR, LF or NUL. Invalid booleans and malformed
records fail visibly; headers must agree with an explicit schema. Use `multiLine=false` for known
single-line CSV files when splitting large files across tasks matters. Backslash-escaped sources
can set `escape=\`.

Schema inference is convenient, but it cannot know whether `001` is an identifier or whether a
number requires decimal precision. For identifiers, money, and contractual schemas, pass explicit
types:

```bash
spark-submit --master 'local[2]' --class com.example.datacraft.cli.Runner \
  modules/interfaces/datacraft-cli/target/datacraft-cli.jar \
  --command csv-to-parquet --master 'local[2]' \
  --param input=data/input.csv --param output=data/output.parquet \
  --param 'schema=id STRING, amount DECIMAL(22,4), note STRING' --json
```

`inferSchema=false` preserves text columns when no schema is supplied.

## Write and verification behavior

Conversion rejects equal, ancestor, and descendant input/output paths with "requirement failed:
Input and output paths must not overlap". Local paths are compared after resolving relative, `..`
and symbolic-link spellings. HDFS paths are compared after canonicalising the authority (host case,
default port), with scheme-less paths resolved against `fs.defaultFS`: with
`fs.defaultFS=hdfs://nn:8020`, the paths `hdfs://nn:8020/x`, `hdfs://nn/x` and `/x` overlap. Paths
on different file systems never overlap. Conversion validates and caches a complete CSV snapshot
before writing; counting and writing reuse the snapshot, and the cache is released on failure as
well as success. Malformed input cannot erase a previously valid destination. Source files must
remain immutable while a job is running.

| Mode | Existing destination | `metrics.rows` |
| --- | --- | --- |
| `overwrite` (default) | Replaced after input validation | Rows in this conversion |
| `append` | Additional data is appended | Newly appended rows only |
| `ignore` | Left unchanged; source is not scanned; message `Skipped: <output> already exists (mode=ignore)` | 0, with `skipped=true` |
| `error` / `errorifexists` | Fails with `requirement failed: Output already exists: <output>` before scanning input | Failed result |

An overwrite retry is appropriate for an immutable batch. Append retries can duplicate records;
callers must supply deduplication or transaction semantics if they choose append. Spark output is
not a general atomic transaction: disk/network failure during the write can leave partial output.
Keep the source and retry to recover; storage-specific atomic publication needs a separate design.

`row-count` accepts `input`, `inputFormat` (default parquet), optional `schema`, and optional
`expectedRows`. `inputFormat` is trimmed; a blank value fails with "requirement failed: inputFormat
must not be blank", and the `inputFormat` metric reports the trimmed value in its original case.
`expectedRows` must be a 64-bit integer >= 0: a blank, malformed or negative value fails before
Spark starts with "expectedRows must be a 64-bit integer >= 0", and a count mismatch fails the job
with "requirement failed: Expected <n> rows but found <m>". `csv` and `json`, in any case, are read
in FAILFAST mode and counted over complete records, so column pruning cannot bypass parser or type
validation; CSV counts share the CSV options above and do not infer types by default. Other formats
(parquet, orc, ...) use Spark's reader defaults. Airflow passes the conversion count into
`expectedRows` automatically. `row-count` is not confined by `DATACRAFT_DATA_ROOT`.

## Data root (`DATACRAFT_DATA_ROOT`)

`DATACRAFT_DATA_ROOT` confines job paths. The value is trimmed and read when the job catalog is
built (process start for the CLI and `serve-api`); unset or blank means unconfined, which suits
local CLI use.

- `csv-to-parquet`: the root must be absolute ("requirement failed: DATACRAFT_DATA_ROOT must be an
  absolute path"). `input`, then `output`, must lie strictly inside it on the same file system after
  `..` resolution and, for local paths, symbolic-link resolution; the root itself is not inside.
  Otherwise the job fails with "requirement failed: input must be inside the configured data root"
  (or `output`). The check covers the input path itself, not symbolic links that Spark follows
  inside an input directory.
- `csv-profile` and `file-checksum`: see [Plain-JVM small-file jobs](#plain-jvm-small-file-jobs).
- `row-count`, `spark-version`, `echo` and `noop` are not confined.

Overwrite, the default mode, recursively deletes the target before writing, so anyone who can
trigger `csv-to-parquet`, including through the Airflow DAGs, can replace or delete data inside the
root.

- The `datacraft/jvm` image sets `DATACRAFT_DATA_ROOT=/opt/datacraft/data` but does not create that
  directory, so the file jobs of a bare `docker run` of the API fail closed ("DATACRAFT_DATA_ROOT
  must name an existing directory") until a volume is mounted there.
- Compose and Swarm set `DATACRAFT_DATA_ROOT=/opt/datacraft/data` under `environment:` for every
  Airflow container and for `datacraft-api`. That overrides `env_file`, so a value in `.env` is
  ignored; change the stack files and the volume mount together. `datacraft-api` mounts the
  `datacraft-data` volume there read-only.
- `serve-api` refuses to start (exit 2, one stderr line) on a non-loopback `--host` while the
  variable is unset or blank, for `--host 0.0.0.0` with "serve-api refuses --host 0.0.0.0 without
  DATACRAFT_DATA_ROOT: the API is unauthenticated and its file jobs could read any path. Bind
  127.0.0.1 or set DATACRAFT_DATA_ROOT."
- Airflow checks the trigger-conf paths `input`/`output` of `datacraft_spark_etl` and
  `local_path`/`output` of `datacraft_sftp_ingest` against the same root (default
  `$DATACRAFT_HOME/data`) when a run is created. A path must be absolute with at least one segment
  below the root, and must not contain `.` or `..` segments, NUL, CR, LF or any of `{}[]*?\`, or end
  in a space or a C0 control character (U+0000 to U+001F); anything else, including `hdfs://` and
  `s3a://` paths, is a `ParamValidationError`. The root itself must be an absolute directory other
  than `/` that does not start with `//`, carry surrounding whitespace or contain any of those
  characters; duplicate and trailing slashes are collapsed. Otherwise all three DAGs fail to import.

## Plain-JVM small-file jobs

`csv-profile` and `file-checksum` (module `datacraft-jobs`) run with plain
`java -jar datacraft-cli.jar`, in the `datacraft/jvm` image, and through
`POST /jobs/<name>/runs?input=...`.

Rules for both jobs:

- `input` is required and trimmed; it is a local file path, not a URI. A missing or blank value
  fails with "Missing required parameter: input", a missing file with "Input file does not exist:
  <input>", and a directory or other non-regular file with "Input is not a regular file: <input>".
- Parameter names are case-sensitive: a case variant of a documented name fails, for example
  "Unknown parameter expectedrows; did you mean expectedRows?". Other unknown keys are ignored.
  Messages for malformed or out-of-range `header`, `delimiter`, `expectedRows`, `maxBytes` and
  `expectedSha256` values name the key but never echo the rejected value.
- A quality-gate mismatch (`expectedRows`, `expectedSha256`) returns FAILED and keeps the metrics.
- When `DATACRAFT_DATA_ROOT` is set, it must be an absolute path to an existing directory; otherwise
  the run fails with "DATACRAFT_DATA_ROOT must be an absolute path", "DATACRAFT_DATA_ROOT must name
  an existing directory" or "DATACRAFT_DATA_ROOT is not a valid path", and the directory is never
  created. The root is validated when a job runs, so a bad value does not stop `list-jobs`, `echo`
  or `serve-api`. The input, made absolute (a relative path resolves against the working directory)
  and normalised, must lie strictly inside the root: the root itself, `..` escapes and prefix
  siblings fail with "input must be inside the configured data root". This comparison is lexical,
  so spell inputs the way the root is spelled (a Windows 8.3 short name does not match the long
  name). The file is then read only through `LocalStorageService`, rooted at the root's real path:
  the configured root may itself be a symbolic link, but a symbolic link below it fails with
  "Storage path must not traverse symbolic links: <path>", and a junction or mount point below it
  whose target lies outside the root fails with "Storage path must not leave the root through a
  link or junction: <path>" (see [IO boundaries](#io-boundaries)).
- Source files must not change while a job reads them. `csv-profile` computes every metric from one
  read; `file-checksum` reads the size and then streams the hash.

`csv-profile` profiles a small UTF-8 CSV file in memory:

| Parameter | Default | Rule |
| --- | --- | --- |
| `input` | required | Path of the file to profile |
| `header` | `true` | `true`/`false` in any case, surrounding spaces allowed; else "header must be true or false" |
| `delimiter` | `,` | Exactly one character other than `"`, CR, LF or NUL, not trimmed (TAB works); else "delimiter must be exactly one character other than a quote, newline or NUL". Multi-character delimiters are Spark-only |
| `expectedRows` | none | 64-bit integer >= 0, else "expectedRows must be a 64-bit integer >= 0"; a different count fails with "Expected <n> rows but found <m>" |
| `maxBytes` | 67108864 (64 MiB) | Integer from 1 to 2147483639, else "maxBytes must be an integer from 1 to 2147483639" |

Metrics: `rows` (data records, excluding the header when `header=true`), `columns` (fields in the
first record), `bytes` (file size, including any BOM), `sha256` (lower-case hex of the profiled
bytes) and `durationMillis`. The success message is "<rows> rows, <columns> columns in <input>".

- The file must be valid UTF-8 ("Input <input> is not valid UTF-8 at byte offset <n>"); a leading
  BOM is ignored. Records follow [`CsvFiles`](#csvfiles): RFC 4180 quoting (quoted fields may
  contain delimiters, doubled quotes and line breaks), LF, CRLF or CR record terminators, and
  malformed quoting fails with the `CsvFiles` message, e.g. "Unterminated quoted CSV field.".
- Every record must have as many fields as record 1, e.g. "CSV record 3 has 1 field but the header
  has 2 fields" (with `header=false`: "... but record 1 has 2 fields"). Record numbers are 1-based,
  count the header, and follow records rather than lines when a quoted field spans lines.
- A blank line is a record with one empty field, so it fails a multi-column file. Spark's CSV
  reader skips blank lines, so `row-count` can report a different count for such a file.
- An empty file (0 bytes or only a BOM) gives 0 rows and 0 columns; a header-only file gives 0
  rows and the header's field count as columns.
- A file above `maxBytes` fails before it is read: "Input <input> is <size> bytes, above
  maxBytes=<n>; csv-profile loads the whole file into memory, so use the Spark row-count job
  (inputFormat=csv) for larger files". The job also fails when a conservative heap estimate exceeds
  a quarter of the JVM's maximum heap: before reading when three times the file size does, and
  before parsing when the estimate for the parsed records does. Parsed records take about six times
  the file size for typical data and far more for very short fields or blank lines. The message is
  "Input <input> needs up to <n> MiB of heap to profile, above the csv-profile budget of <m> MiB (a
  quarter of the maximum heap); use the Spark row-count job (inputFormat=csv) or give the JVM more
  heap".

`file-checksum` streams any regular file, text or binary and of any size, through SHA-256 with a
bounded buffer. Parameters: `input` and optional `expectedSha256` (64 hexadecimal characters in any
case, surrounding whitespace trimmed; else "expectedSha256 must be 64 hexadecimal characters"). A
mismatch fails with "Expected SHA-256 <expected, lower-case> but found <actual>". Metrics: `bytes`,
`sha256` (lower-case hex) and `durationMillis`. The success message is "SHA-256 <sha256> for
<bytes> bytes in <input>".

### CsvFiles

`CsvFiles` (`datacraft-io`) is a dependency-free CSV reader/writer for small files, using RFC 4180
quoting. It loads the entire file in memory; use Spark for larger inputs. SHA-256 file hashing in
`LocalFiles` streams through a bounded buffer instead.

- The reader accepts CRLF, LF or CR record terminators and skips a leading BOM. It preserves quoted
  empty records, delimiters, doubled quotes and embedded line breaks, and rejects malformed quoting
  ("Unexpected quote at CSV offset <n>", "Unexpected character after closing CSV quote at offset
  <n>", "Unterminated quoted CSV field.") rather than silently changing data.
- The writer ends every record with LF on every OS, not the RFC 4180 CRLF. It quotes a field that
  contains the delimiter, a quote, CR or LF or that starts with U+FEFF; writes a record made of one
  empty or `null` field as `""`, so it is not a blank line that Spark or Python readers drop; and
  rejects a row with no fields ("A CSV row must contain at least one field.").
- The delimiter must not be a double quote, CR, LF or NUL.

## IO boundaries

`LocalStorageService` accepts only relative paths under its root. It refuses a path that escapes
the root after normalisation, a path that traverses a symbolic link, and a path in which an existing
component's real location is outside the root, such as a Windows directory junction or volume mount
point; a junction whose target stays inside the root is allowed. The root itself (`""`, `.`,
`a/..`) is valid only as a listing directory: file operations on it throw
`IllegalArgumentException`, and `exists` returns true for it. Listings omit symbolic links,
including links to files inside the root. Recursive listings skip reparse-point directories
(junctions, including those whose target is gone, and mount points) and directories whose real
location is outside the root. Any other operation on a path through an existing component whose real
location cannot be resolved, such as a junction whose target was deleted, fails with
`DataCraftException`. `LocalStorageService.at` creates a missing root, and the root must not itself
be a symbolic link. This is an application guard, not an OS sandbox: hard links, Windows reserved
device names such as `NUL`, and concurrent hostile filesystem changes are not detected. Protect the
root with filesystem permissions.

The generic `LocalFiles` helper has no root restriction. `LocalFiles.deleteRecursively` removes
symbolic links and Windows junctions, including dangling ones and a start path that is itself a
link, without deleting their targets. Any other non-empty Windows reparse-point directory makes it
fail with `DataCraftException` instead of descending into it, and traversal errors also surface as
`DataCraftException`.

SFTP:

- Remote paths of `RemoteFileTransfer` and `SftpClient` are literal. `*`, `?` and `\` are escaped
  before they reach JSch, so every operation addresses exactly the named entry, never a pattern
  match.
- `download(remote, Path)` streams into a dot-prefixed sibling `.<name>.<uuid>.part` (hidden on
  POSIX, an ordinary visible file on Windows while the transfer runs) and atomically moves it onto
  the target only after the transfer completes, creating parent directories as needed. A failure
  leaves an existing target unchanged and no partial file behind. Because the target is replaced by
  a new file, it gets default permissions and ownership, hard links to the old file keep the old
  content, and a symbolic link at the target is replaced rather than written through. A target that
  is an existing directory is rejected.
- `upload(Path, remote)` streams the local file to a remote file path; uploading onto an existing
  remote directory fails.
- `list(dir)` returns the sorted names of regular files. Links are followed: links to files are
  included, links to directories and dangling links are not. FIFOs, sockets and devices are
  excluded; entries whose type the server does not report are kept.
- `SftpClient.connect(config)` uses `~/.ssh/known_hosts`; the overload accepting a `Path` uses an
  explicit trusted-host file. Strict host-key checking stays on unless the `SftpConfig` disables it
  (`strictHostKeyChecking=false` for `fromProperties`). Connect and read timeouts are bounded to
  1..2147483647 milliseconds. Failed channel setup closes its session; config string
  representations redact passwords. `SftpConfig.fromProperties` trims `host` and `username` and
  keeps `password` verbatim. Configure host trust before use.
- The Airflow SFTP connection uses its own provider-managed credentials and trust settings. The
  `datacraft_sftp_ingest` download fails without retry unless the `datacraft_sftp` connection
  verifies the server key: a `host_key` extra, or `"no_host_key_check": false` with a known_hosts
  file for the Airflow user, and `allow_host_key_change` not enabled.

## HTTP status contract

| Request | Response |
| --- | --- |
| `GET` or `HEAD /health` | 200 `{"status":"UP","service":"datacraft-api"}` |
| `GET` or `HEAD /jobs` | 200 `{"jobs":[{"name":...,"description":...}]}` |
| `POST /jobs/{name}/runs`, job succeeded | 200 with the result `{"jobName","status","message","metrics"}` |
| `POST /jobs/{name}/runs`, job failed | 500 with the same result body, including parameter rejections such as "Missing required parameter: input" and a missing Spark runtime |
| Malformed run request | 400 `{"error":"invalid_request"}`: a missing, blank or nested job path, an unknown `lifecycle`, a percent-escape that does not decode as UTF-8, or raw non-ASCII bytes in the query |
| Run request with an `Origin` header | 403 `{"error":"cross_origin_forbidden"}`, whatever the value (`null` included) |
| Unknown job | 404 `{"error":"unknown_job"}` |
| Unknown route, e.g. `/`, `/healthz`, `/api/...` | 404 `{"error":"not_found"}` |
| Other method | 405 `{"error":"method_not_allowed"}` with `Allow: GET, HEAD` (`/health`, `/jobs`) or `Allow: POST` (runs) |
| Unexpected handler failure | 500 `{"error":"internal_error"}`; a `RuntimeException` is logged at `ERROR` as "Request <METHOD> <raw path> failed" |

A run request is checked in this order: method (405), `Origin` (403), job path, query and
`lifecycle` (400), job lookup (404), then the run (200 or 500). A 400 body does not say which check
failed. `HEAD` returns the status and headers of the matching `GET`, including its
`Content-Length`, without a body. Every exchange is closed; when a handler fails, the request
still gets the JSON 500 unless the response had already started or the client is gone.

Query keys and values must be percent-encoded UTF-8; `+` in the query decodes to a space. For
example, against the port that Compose publishes on loopback:

```bash
curl -X POST -G --data-urlencode 'input=/opt/datacraft/data/résumé.csv' \
  http://127.0.0.1:8088/jobs/csv-profile/runs
```

Path segments are decoded once and keep `+`. JSON escaping is shared with the CLI. The JDK server
itself refuses request targets that `java.net.URI` cannot parse, such as a malformed percent-escape
or raw bytes 0x80-0xA0 (present in unencoded UTF-8 for `€`, `日` or `à`), with its plain HTML 400
before any handler runs; other raw non-ASCII bytes reach the handler and get the JSON 400.

The API has no authentication. Binding to loopback does not stop a browser on the same machine, or
one reaching the port through an SSH tunnel, from sending requests; the `Origin` check blocks
browser-originated run requests, including cross-site and DNS-rebinding POSTs, while curl,
`java.net.http`, Airflow and the healthchecks send no `Origin`. Compose publishes the API on
`127.0.0.1` only ([`deployment_env.py`](../deploy/scripts/deployment_env.py) `check` rejects a
`datacraft-api` port on any host address other than `127.0.0.1` or `::1`, and any non-tmpfs
`datacraft-api` volume that is not read-only), Swarm leaves it on the overlay network, and
`serve-api` needs `DATACRAFT_DATA_ROOT` off loopback. Put an authenticated gateway in front before
exposing it externally.

## Where the contracts are tested

The JVM tests run in the build of the module they test (`./mvnw verify`); the Airflow suites run
in CI's `dags` job (see [CI gates](build-and-quality.md#ci-gates)).

| Contract | Tests |
| --- | --- |
| Failure contract, result factories | [`JobExecutionEngineTest`](../tests/jvm/datacraft-engine/java/com/example/datacraft/engine/JobExecutionEngineTest.java), [`JobExecutionResultTest`](../tests/jvm/datacraft-engine/java/com/example/datacraft/engine/JobExecutionResultTest.java), [`JobRegistryTest`](../tests/jvm/datacraft-engine/java/com/example/datacraft/engine/JobRegistryTest.java) |
| CLI exit codes, output, `--config` and master precedence | [`RunnerSpec`](../tests/jvm/datacraft-cli/scala/com/example/datacraft/cli/RunnerSpec.scala), [`CliParserSpec`](../tests/jvm/datacraft-cli/scala/com/example/datacraft/cli/CliParserSpec.scala) |
| Spark job parameters, runtime hint and case variants | [`SparkJobsSpec`](../tests/jvm/datacraft-spark/scala/com/example/datacraft/spark/SparkJobsSpec.scala), [`SparkRuntimeConfigSpec`](../tests/jvm/datacraft-spark/scala/com/example/datacraft/spark/SparkRuntimeConfigSpec.scala), [`CsvReadOptionsSpec`](../tests/jvm/datacraft-spark/scala/com/example/datacraft/spark/CsvReadOptionsSpec.scala) |
| Conversion, write modes and `row-count` on a real SparkSession | [`SparkPipelineSpec`](../tests/jvm/datacraft-spark/scala/com/example/datacraft/spark/SparkPipelineSpec.scala) |
| Overlap guard and Spark data root | [`PathGuardsSpec`](../tests/jvm/datacraft-spark/scala/com/example/datacraft/spark/PathGuardsSpec.scala) |
| Plain-JVM jobs and their data root | [`CsvProfileJobTest`](../tests/jvm/datacraft-jobs/java/com/example/datacraft/jobs/CsvProfileJobTest.java), [`FileChecksumJobTest`](../tests/jvm/datacraft-jobs/java/com/example/datacraft/jobs/FileChecksumJobTest.java), [`DataRootConfinementTest`](../tests/jvm/datacraft-jobs/java/com/example/datacraft/jobs/DataRootConfinementTest.java), [`JvmJobsTest`](../tests/jvm/datacraft-jobs/java/com/example/datacraft/jobs/JvmJobsTest.java) |
| `CsvFiles` | [`CsvFilesTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/CsvFilesTest.java) |
| IO boundaries | [`StorageServiceTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/StorageServiceTest.java), [`LocalFilesTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/LocalFilesTest.java) |
| SFTP | [`SftpClientTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/SftpClientTest.java) (embedded SFTP server), [`RemoteFileTransferTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/RemoteFileTransferTest.java), [`SftpConfigTest`](../tests/jvm/datacraft-io/java/com/example/datacraft/io/SftpConfigTest.java) |
| HTTP status contract | [`EngineHttpServerTest`](../tests/jvm/datacraft-api/java/com/example/datacraft/api/EngineHttpServerTest.java) |
| Airflow data paths, host-key guard and task wiring | [`tests/orchestration/test_dags.py`](../tests/orchestration/test_dags.py), [`tests/smoke/airflow_runtime_smoke.py`](../tests/smoke/airflow_runtime_smoke.py) |
| API port and mount checks of `deployment_env.py` | [`tests/deploy/test_deployment_env.py`](../tests/deploy/test_deployment_env.py) |
| Exit codes and results of the shaded jar | [`cicd/build/smoke-cli-jar.sh`](../cicd/build/smoke-cli-jar.sh) and [`cicd/build/check-cli-exit-codes.sh`](../cicd/build/check-cli-exit-codes.sh), run by the `build` job of [`ci.yml`](../.github/workflows/ci.yml) |
