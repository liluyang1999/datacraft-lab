# 版本管理

datacraft-lab 只有一个产品版本号，只在一处修改。后端各模块、命令行 jar、引擎 API、桌面控制台
数艺工坊、它的应用目录与 Windows 安装包都使用这同一个版本。数据格式（SQLite 的 `PRAGMA user_version`）、
依赖与镜像的固定版本各有自己的标识，不与产品版本混用。

## 唯一来源

根 `pom.xml` 的 `<revision>` 属性是全仓库唯一可编辑的产品版本字面值，当前为 `1.0.0`。
`python3 -B cicd/release/version.py` 输出该值并校验格式，脚本与工作流都从这里读取。

| 消费方 | 取得方式 |
| --- | --- |
| 根 POM 与八个后端模块、`desktop/pom.xml` | `<version>${revision}</version>`；模块的 `<parent>` 同样写 `${revision}` |
| 命令行用法首行、引擎 API `GET /status` 的 `version` | `datacraft-common` 构建时过滤 `version.properties`，`AppInfo.version()` 读取 |
| 桌面侧栏、设置“关于” | 桌面构建时过滤自己的 `version.properties`，`BuildInfo.version()` 读取 |
| 应用目录 `DatacraftLab`（jpackage 的 `--app-version`、启动器版本资源） | `cicd/desktop/package_desktop.py` 读取 `version.py`，并要求 jar 名中的版本与之相同 |
| `Setup.exe` 与“已安装的应用”中的版本 | `cicd/desktop/build_installer.py` 以 `/DAppVersion` 传给 Inno Setup |
| CI 的安装包构件名、发布核对 | `cicd/desktop/release.py outputs` 与 `cicd/release/deploy.py` 读取 `version.py` |

`tests/ci/test_versioning.py` 核对：各 POM 只继承 `${revision}`；构建清单、源码、脚本、工作流、安装器、
入口与测试中都没有第二个版本字面值；[更新日志](CHANGELOG.md) 有当前版本的条目。

## 格式与递增

版本为语义化版本的 `MAJOR.MINOR.PATCH`，三段都是不超过 65535 的十进制数，不带 `-SNAPSHOT` 等后缀：
Windows 版本资源与安装器的升级、降级判断只比较数字。

- `MAJOR`：不兼容的变化，例如引擎 API 或作业参数的契约变化、需要协调升级的部署变更。
- `MINOR`：向后兼容的新功能，例如新作业、新视图、新接口字段。
- `PATCH`：向后兼容的缺陷修复。

安装器拒绝用较低版本覆盖较高版本；同一版本再次运行是修复安装。

## 修改流程

1. 只改根 `pom.xml` 中 `<revision>` 这一行。
2. 在 [CHANGELOG](CHANGELOG.md) 把 `[Unreleased]` 下的条目移到新版本标题 `## [x.y.z] - 日期` 下。
3. 提交到开发分支；CI 的 check 阶段运行 `version.py`，test 阶段运行 `test_versioning.py`。
4. 验收后合入 `main`。操作人员在 Actions 页手动运行 **CI/CD**，分支选 `main`，在 `deploy-version`
   中填写同一版本号：入口先核对分支、版本与标签，再完整执行 check、test、build，最后由 deploy 阶段
   核对安装包的版本（见[开发指南的 CI/CD 一节](guides/development.md#cicd)）。
5. 发布渠道确定后，由发布流程在已发布的提交上创建 `v<版本>` 标签；同一标签不得指向两个提交。
   在此之前 deploy 阶段只汇总安装包与校验和，不上传、不打标签。

不要在工作流、脚本、安装器、测试夹具或源码中再写产品版本字面值；需要时读取上述来源。
