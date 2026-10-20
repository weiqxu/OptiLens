# OptiLens · 优化之镜

看见 SQL 优化器的每一步。

OptiLens 是一个计划开发的数据库优化规则研究服务。它将关联优化器源码、查询计划和运行事件，帮助研究者理解：有哪些优化规则、何时触发、产生了什么计划变化，以及哪些候选最终被采用。

## 当前状态

项目处于设计阶段。当前仓库包含项目说明和实施计划，尚未实现服务、数据库适配器或可运行的前端。此前讨论的交互界面使用模拟数据，不是数据库运行结果。

## 第一版目标

先接入 Apache Calcite，完成真实优化数据的采集和展示：

```text
SQL + 表结构
    → 解析与校验
    → 构造逻辑计划
    → 运行指定优化规则并采集事件
    → 展示前后计划树、变化说明和步骤回放
```

界面采用左侧规则步骤列表、中间前后计划树、下方变化说明的布局。用户可以选择步骤、查看节点属性，并核查对应的事件证据。

第一版优先研究常量折叠、谓词下推、投影裁剪等逻辑优化。使用真实引擎生成的数据；演示数据必须明确标注。

## 研究原则

- 区分源码中存在规则、运行时尝试规则、产生候选、最终采用候选和实际性能提升。
- 单份最终计划不能证明完整的规则应用历史；计划差异也不能单独证明具体规则的归因。
- 保留原始计划、引擎版本、配置和事件，确保可核查与复现。
- 没有采集到事件不等于规则不受支持；未知原因应明确显示为未知。
- 跨数据库比较关注语义和行为，不直接比较不同引擎的 cost 数值。

## 后续方向

- 接入 Spark、DuckDB、PostgreSQL 等引擎的日志或计划数据。
- 建立包含适用条件、源码位置和测试用例的规则知识库。
- 支持等价候选图、规则开关对照实验与跨数据库案例比较。

这些方向属于后续规划，不代表当前已具备相应能力。

## 文档

- [设计与实施计划](plan/implementation-plan.md)：范围、架构、数据模型、界面、里程碑和验收标准。

## 仓库结构

```text
OptiLens/
├── README.md
└── plan/
    └── implementation-plan.md
```

## 技术方向

初步建议采用 Java + Calcite 实现优化与采集，Web 前端实现树形对比。具体框架、依赖版本和构建方式将在首个技术验证阶段确定，并在实现后补充安装与启动说明。

## 参考

- [Calcite RuleMatchVisualizer](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/visualizer/RuleMatchVisualizer.html)
- [Calcite RelOptListener](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptListener.html)
- [Spark RuleExecutor](https://github.com/apache/spark/blob/master/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/rules/RuleExecutor.scala)
