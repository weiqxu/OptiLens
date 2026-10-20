# OptiLens · 优化之镜

看见 SQL 优化器的每一步。

OptiLens 是一个本地运行的 Web 研究工具：输入 SQL 和表结构，通过 Apache Calcite 采集真实优化事件，在浏览器中查看完整计划的阶段变化和单次规则的局部变换。

## 当前版本：0.1.0

已实现：

- 数据库类型选择（目前仅 Calcite）、可搜索和分类筛选的内置规则目录、单条规则与常用规则组选择。
- 可编辑的 SQL 和声明式表结构。
- Calcite 1.39.0 / HepPlanner 的真实规则尝试与表达式产生事件。
- 初始与最终计划、阶段边界快照、规则事件的前后图形对比。
- 差异高亮、图形缩放、节点详情及原始计划查看。
- 6 个内置案例，覆盖常量、谓词、投影、外连接与 NULL 条件。
- 异步运行状态、本地记录保存、历史查看、JSON 导出和导入。
- 独立工作进程的超时与内存限制、输入校验及错误提示。

这不是完整数据库，也不执行用户查询。首版没有真实表数据或统计信息，不展示性能收益。源码自动分析、多数据库接入和等价候选搜索图仍属于后续规划。

## 启动

需要 **JDK 17+、Maven 3.8+**。首次构建需要能够访问 Maven 依赖仓库。

```bash
cd /home/weiqxu/src/OptiLens
mvn package
java -jar target/optilens.jar
```

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，选择内置案例，点击“运行优化”。前端资源已经打包进 JAR，不需要单独安装 Node.js 或启动前端开发服务。

自定义端口和记录目录：

```bash
OPTILENS_PORT=8081 OPTILENS_DATA_DIR=./data/runs java -jar target/optilens.jar
```

服务只监听 `127.0.0.1`，用于本机研究。默认运行记录在 `data/runs/`，已被 Git 忽略；关闭服务后可备份该目录。服务重启会恢复记录，并将中断的任务标为失败。

支持浏览器通过另一个本地端口转发访问，例如 `http://localhost:49894`。访问地址校验接受 `localhost`、`127.0.0.1` 和 `[::1]` 的合法端口，不要求浏览器端口与后端监听端口相同；非本地地址仍会被拒绝。

## 如何阅读结果

1. **初始 → 最终**：SQL 转换之后的初始逻辑计划与所选规则程序的最终输出。
2. **规则阶段**：某个规则组运行前后的完整计划；一个组可能包含多条 Calcite 规则。
3. **局部事件**：单次规则调用产生的新表达式及原匹配表达式，不是整条查询计划。
4. **节点详情**：属性、输出字段、输入节点，以及差异匹配依据。
5. **原始记录**：Calcite 输出、调用标识、匹配节点和尝试记录。

规则组按页面顺序执行。这是受控实验，不等同于 Calcite 默认优化流程。单次规则的 before/after 回调只记录一次；快照保存独立值，不保留可变计划对象。

差异优先使用同一运行的节点标识，随后尝试唯一属性或算子/表来源匹配。结构推断会在节点详情中标注；匹配不唯一时不会强行配对。“仅见于应用前/后”表示没有建立对应关系，不一定是实际删除/新增。

导入文件仅做结构校验，界面明确标注“未重新执行验证来源”。没有记录到事件不能证明数据库不支持某项优化；生成候选也不证明它最终被采用或提高了性能。

## 数据库与规则目录

在“数据库类型”中选择 Apache Calcite 后，页面加载当前依赖版本 1.39.0 的内置规则目录。保留原有 5 个常用组合，其他条目可按名称、实现或分类搜索，并提供源码链接。搜索只过滤展示，不取消已选规则；“常用组合”恢复默认选择，“清空选择”生成无规则程序。

目录从 `CoreRules`、`PruneEmptyRules`、`SingleValuesOptimizationRules`、`DateRangeRules`、`MeasureRules`、`SpatialRules`、`MaterializedViewRules`、`StreamRules`、`EnumerableRules`、`Bindables` 的公开静态规则和规则列表中读取，并列出 `JdbcRules` 的具体规则类型及 JDBC→Enumerable 转换。公开常量别名分别显示，列表中已有常量的实例不会重复收录。该范围不包括额外适配器、自定义规则及运行时配置产生的所有变体；Calcite 并没有跨所有插件的固定全局规则全集。

逻辑规则可以单独选择运行；物理转换、流式、空间、度量、物化视图和适配器规则会展示所需环境，并禁止选择当前不能运行的条目。选择一条规则不保证其在当前 SQL 中触发，也不保证任意组合兼容。每次最多选择 32 条规则或组合，按目录顺序执行；常用组合与单条规则可能有重叠，重复执行属于用户所选程序。

## 表结构格式

```json
[
  {
    "name": "customers",
    "columns": [
      { "name": "id", "type": "INTEGER", "nullable": false },
      { "name": "name", "type": "VARCHAR", "nullable": true }
    ]
  }
]
```

支持 `INTEGER`、`BIGINT`、`DOUBLE`、`DECIMAL`、`VARCHAR`、`BOOLEAN`、`DATE`、`TIMESTAMP`。`DECIMAL` 固定为 `(18,2)`，`VARCHAR` 固定为 `(255)`；省略 `nullable` 时为可空。表名与列名采用 ASCII 标识符，查询名称匹配不区分大小写，支持反引号引用。

## API

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/health` | 服务状态 |
| `GET /api/catalog` | 数据库类型、规则目录、目录范围和内置案例 |
| `POST /api/runs` | 提交 `{database, sql, schema, rules}`，返回任务 ID |
| `GET /api/runs` | 最近 30 条运行摘要 |
| `GET /api/runs/{id}` | 任务状态及结果 |
| `GET /api/runs/{id}/download` | 导出运行结果 JSON |
| `POST /api/import` | 导入结果，生成新的本地记录 ID |

POST 请求使用 `Content-Type: application/json`。`database` 当前仅接受 `calcite`；旧请求省略时默认为 Calcite。`rules` 是目录中可运行条目的有序 ID 列表，例如 `CoreRules.PROJECT_REDUCE_EXPRESSIONS`。旧版规则组 ID 继续支持：`reduce`、`filter-join`、`filter-project`、`project-join`、`project-cleanup`；空列表只生成初始计划。

任务状态为 `queued`、`running`、`succeeded`、`failed`。成功结果的 `formatVersion` 为 `1`，包含 `input`、`initial`、`finalPlan`、`stages` 和 `limitations`。

## 资源限制

- 最多 2 个并发优化工作进程、8 个排队任务，每进程 384 MB 堆内存。
- 每个工作进程最多运行 25 秒；每组匹配上限 100 次。
- SQL 最多 10000 字符；最多 16 张表，每表 32 列。
- 每个快照最多 250 个节点、40 层；总尝试最多 500 次，每阶段变换最多 80 次。
- 导入和工作进程输出最多 8 MB；最多保留 200 条本地记录。

超出采集规模时运行失败，不将截断结果冒充完整记录。HepPlanner 的匹配上限则属于本次受控优化配置，不保证已达到全局最优或穷尽所有改写。

## 验证

Java 测试覆盖真实规则变换、常量求值、外连接/NULL 边界、无规则程序、非法输入、记录往返、HTTP 生命周期、重启恢复和工作进程超时：

```bash
mvn test
```

图差异与 DAG 布局测试需要 Node.js 18+，无需安装依赖：

```bash
node --test tests/graph.test.mjs
```

可选浏览器测试（先启动服务）：

```bash
npm ci
npx playwright install chromium
npm run test:browser
```

本地端口转发回归测试会自行启动临时后端和转发服务（需先完成 `mvn package`）：

```bash
npm run test:forwarding
```

可用 `OPTILENS_TEST_URL` 指定测试地址，默认 `http://127.0.0.1:8080`。浏览器测试会在目标服务创建真实运行及导入记录；建议使用单独的 `OPTILENS_DATA_DIR`。`OPTILENS_SCREENSHOT_DIR` 可指定截图目录。

## 代码布局

```text
src/main/java/dev/optilens/
  Server.java        本地 HTTP API 与静态资源
  Jobs.java          任务队列、持久化与工作进程生命周期
  Worker.java        隔离的 Calcite 优化入口
  Optimizer.java     SQL 转换、HepPlanner 与事件采集
  Snapshots.java     独立快照、Hep 节点展开及原始计划
  Model.java         版本化运行数据结构
  Rules.java         规则程序与源码依据
  Input.java         表结构校验与内置案例
  Records.java       导入记录校验
src/main/resources/web/  无外部 CDN 依赖的网页
src/test/java/           后端测试
tests/                   图差异与浏览器测试
plan/                    设计及实施记录
```

前端采用原生 JavaScript 模块和 SVG，后端采用 JDK HTTP Server，避免首版引入额外前端构建和服务框架。可选 npm 依赖仅用于浏览器测试。

## 计划与参考

- [设计与实施计划](plan/implementation-plan.md)
- [首版实现记录](plan/v0.1-implementation.md)
- [Calcite 1.39.0 源码](https://github.com/apache/calcite/tree/calcite-1.39.0)
- [RelOptListener](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptListener.html)
- [RuleMatchVisualizer](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/visualizer/RuleMatchVisualizer.html)
