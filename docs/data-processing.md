# Data processing contracts

The same `DataJob` runs through CLI, HTTP, and Airflow. Spark jobs require the Spark runtime (normally
`spark-submit`); the small JVM image serves lightweight jobs. Managed Spark jobs in one JVM serialize
session ownership, so one request cannot stop another request's context. An externally owned active
session is rejected and left running.

## CSV and numeric precision

`csv-to-parquet` accepts `input`, `output`, `mode`, `header`, `delimiter`, `schema`, `inferSchema`,
`multiLine`, and `escape`. CSV defaults are header=true, delimiter=comma, inferSchema=true,
multiLine=true, and RFC double-quote escaping. Invalid booleans and malformed records fail visibly;
headers must agree with an explicit schema. Use `multiLine=false` for known single-line CSV files
when splitting large files across tasks matters. Backslash-escaped sources can set `escape=\`.

Schema inference is convenient, but it cannot know whether `001` is an identifier or whether a
number requires decimal precision. For identifiers, money, and contractual schemas, pass explicit
types:

```bash
spark-submit --master 'local[2]' --class com.example.datacraft.cli.Runner \
  datacraft-cli/target/datacraft-cli.jar --command csv-to-parquet --master 'local[2]' \
  --param input=data/input.csv --param output=data/output.parquet \
  --param 'schema=id STRING, amount DECIMAL(22,4), note STRING' --json
```

`inferSchema=false` preserves text columns when no schema is supplied. The small-file `CsvFiles`
helper preserves quoted empty records, BOM-prefixed input, delimiters, quotes, and embedded line
breaks; it rejects malformed quoting rather than silently changing data. It loads the entire small
file in memory. Use Spark for larger inputs; SHA-256 file hashing streams through a bounded buffer.

## Write and verification behavior

Conversion rejects equal, ancestor, and descendant input/output paths (including local symlink
aliases). It validates and caches a complete CSV snapshot before writing; counting and writing
reuse the snapshot, and the cache is released on failure as well as success. Malformed input cannot
erase a previously valid destination. Source files must remain immutable while a job is running.

| Mode | Existing destination | `metrics.rows` |
| --- | --- | --- |
| `overwrite` (default) | Replaced after input validation | Rows in this conversion |
| `append` | Additional data is appended | Newly appended rows only |
| `ignore` | Left unchanged; source is not scanned | 0, with `skipped=true` |
| `error` / `errorifexists` | Fails before scanning input | Failed result |

An overwrite retry is appropriate for an immutable batch. Append retries can duplicate records;
callers must supply deduplication or transaction semantics if they choose append. Spark output is
not a general atomic transaction: disk/network failure during the write can leave partial output.
Keep the source and retry to recover; storage-specific atomic publication needs a separate design.

`row-count` accepts `input`, `inputFormat` (default parquet), and optional `expectedRows` (nonnegative
64-bit integer). CSV counts share the same CSV options, avoid unnecessary type inference by default,
and evaluate complete records so column pruning cannot bypass parser validation. A count mismatch
fails the job. Airflow passes the conversion count into this parameter automatically.

## IO and request boundaries

`LocalStorageService` accepts only relative paths under its root and refuses symbolic links in an
access path. This is an application guard, not an OS sandbox against concurrent hostile filesystem
mutation. Protect the root with filesystem permissions. The generic `LocalFiles` helper has no root
restriction.

`SftpClient.connect(config)` uses `~/.ssh/known_hosts`; the overload accepting a `Path` uses an explicit
trusted-host file. Connect and read timeouts are bounded to 1..2147483647 milliseconds. Failed channel
setup closes its session; config string representations redact passwords. Configure host trust
before use. The Airflow SFTP connection uses its own provider-managed credentials and trust settings.

The HTTP API returns 400 for invalid input, 404 for unknown routes/jobs, and 405 for unsupported
methods. Path segments are decoded once; JSON escaping is shared with the CLI. The API has no auth
layer: Compose publishes it on loopback only, and Swarm leaves it on the overlay network. Put an
authenticated gateway in front before exposing it externally.
