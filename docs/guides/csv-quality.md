# 转换前检查 CSV 数据质量

`csv-quality` 用于检查小型 UTF-8 CSV 是否满足你设定的行数、空白值和重复行门槛。
它只读取文件，返回质量指标，不删除重复项、不补齐空值，也不改变原始数据。
可通过 CLI、引擎 API 或桌面控制台运行；三种入口使用同一个作业实现。

## 选择合适的作业

| 任务 | 作业 |
| --- | --- |
| 看行数、列数、字节数和校验和，或核对精确行数 | `csv-profile` |
| 在转换前限制空白单元格、重复行，并要求最少行数 | `csv-quality` |
| 核对任意文件的 SHA-256 | `file-checksum` |
| 将大型 CSV 转为 Parquet，或处理 UTF-8 以外的文本编码 | `csv-to-parquet`（需要 Spark） |

质量门槛由数据用途决定。例如重复事件可能是错误，重复的观测值却可能有效。
默认门槛是至少一行数据、没有空白单元格、没有重复行；若业务允许这些情况，应显式设置可接受数量。
本作业不验证列类型、业务主键、范围约束或跨文件一致性，通过也不等于数据满足所有业务规则。

## 参数

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `input` | 必填 | 引擎所在机器上的本地 CSV 文件路径 |
| `header` | `true` | 第一条记录是否为表头；表头不计入数据行或质量指标 |
| `delimiter` | `,` | 单个分隔字符；不能为引号、换行或 NUL |
| `minRows` | `1` | 至少需要多少条数据记录，可以为 0 |
| `maxBlankCells` | `0` | 最多允许多少个空白单元格，可以为 0 |
| `maxDuplicateRows` | `0` | 最多允许多少条重复记录，可以为 0 |
| `maxBytes` | `67108864`（64 MiB） | 输入字节上限；仍受 JVM 内存预算限制 |

三个质量门槛都必须是非负整数。输入必须是有效 UTF-8；可带 UTF-8 BOM。
文件不能在读取过程中被修改。启用 `DATACRAFT_DATA_ROOT` 时，路径须位于该数据根之内，
与 `csv-profile` 使用相同的路径约束。具体数值范围和错误契约见
[数据契约](../../design/data-contracts.md#plain-jvm-small-file-jobs)。

## 计数方式

- `rows` 是解析后的数据记录数；引号中的换行不会另算一条记录。
- `columns` 是每条记录的字段数。行列不齐、引号错误或无效 UTF-8 属于输入错误，先修正输入格式。
- `blankCells` 统计空字符串或仅含 Java 空白字符的字段，例如 `""`、空格和制表符。
- `blankRows` 统计所有字段均为空白的记录。它用于说明结果，不单独设置门槛；
  这些字段仍计入 `blankCells`。
- `duplicateRows` 统计每种完整记录首次出现之后的次数。三条完全相同的记录计为两条重复。
  比较解析后的全部字段，区分大小写，不去掉前后空格，也不把 `001` 与 `1` 视为同一个值。
- `bytes` 和 `sha256` 标识本次读取的原始字节，包括 BOM 和换行；原始单元格内容不进入质量指标。

空文件或仅有表头的文件包含 0 条数据记录，默认因 `minRows=1` 失败；明确设置 `minRows=0` 才允许。
一个全空白的 CSV 行也必须有正确的列数：两列文件应写为 `,`，单纯空行只有一列，会先触发格式错误。

## 从命令行运行

先按[开发指南](development.md)构建 CLI jar，再对已有文件执行：

```bash
JAR=modules/interfaces/datacraft-cli/target/datacraft-cli.jar
java -jar "$JAR" --command csv-quality --param input=data/customers.csv --json
```

例如输入文件内容为：

```csv
id,name
1,Alice
2,
2,
```

默认检查的结果为失败，指标为 `rows=3`、`columns=2`、`blankCells=2`、`blankRows=0`、
`duplicateRows=1`。失败说明数据未达到门槛；已成功计算的指标仍保留在 JSON 的 `metrics` 中，
同时包含 `bytes`、`sha256` 和引擎添加的 `durationMillis`。

如果这两个空值和一条重复记录是业务允许的，明确放宽对应门槛：

```bash
java -jar "$JAR" --command csv-quality --param input=data/customers.csv \
  --param minRows=3 --param maxBlankCells=2 --param maxDuplicateRows=1 --json
```

此时三项计数都在允许范围内，结果为成功。作业没有修改文件；门槛描述的是接受条件。
命令退出码沿用项目约定：成功为 `0`，作业失败为 `1`，CLI 参数使用错误为 `2`。
如需保存结果，可使用现有的 `--result-file`，见[运行指南](usage.md)。

## 从桌面运行

1. 在“设置”连接引擎，打开“作业”。搜索“质量”或 `csv-quality`；选择 JVM 分类可缩小列表。
2. 选中 `csv-quality`，填写引擎能读取的文件路径和质量门槛。留空的可选值采用后台默认值。
3. 运行后查看状态与指标。失败仍有完整质量计数时，先对照门槛判断原因；格式或读取失败时按错误提示处理。
4. 常用门槛可保存为该作业的预设。搜索、分类和页面切换保留当前草稿，实际执行前核对右侧作业标题。

桌面不会上传本地文件。若引擎在 Docker 中，`input` 是容器的数据挂载路径，例如
`/opt/datacraft/data/customers.csv`，不是桌面机器的 `C:\...` 路径。
搜索只查找服务端当前公布的作业；旧引擎没有 `csv-quality` 时，需要更新引擎。

## 放进现有工作流

可在调用 `csv-to-parquet` 之前先执行本作业，并仅在退出码为 `0` 时继续转换。
现有 Airflow DAG 不会因注册新作业而自动增加质量步骤；需要在自己的流程里显式编排。
HTTP 入口仍是 `POST /jobs/csv-quality/runs`，参数按原有查询串规则传递；质量失败返回 HTTP `500`
及带指标的 FAILED 结果。程序应先解析结果，不能将这种有结果的失败误判为连接断开。

这是有界的小文件检查：它保存解析后的记录及重复检测索引，和 `csv-profile` 共享单个 JVM 的
CSV 内存使用限制。增大 `maxBytes` 不会绕过内存预算；不适合把整份大型数据集装进桌面或 API 进程。
