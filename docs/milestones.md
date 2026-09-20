# 里程碑验收与限制

安全传输、持久恢复、有界反馈实现及 SDK 演示已交付。受控实验和一次有界简化复测均未证实预定性能收益；**实现完成不等于研究目标达成**。本记录仅声明已运行的验收范围，不声明生产认证、跨平台兼容或不存在未知缺陷。

## 提交索引

Git 短哈希可通过 `git show <hash>` 检查；新增验证提交由 Git 历史定位，不为记录自身哈希另建提交。

| 编号 | 提交 | 交付 |
| --- | --- | --- |
| C01 | `4dad449` | Java 17 构建和依赖边界 |
| C02 | `e093b0c` | 有界帧、规范元数据及独立向量 |
| C03 | `328cc90` | 流、分块、摘要及 Merkle |
| C04 | `8f8bf45` | mTLS / HPKE 授权绑定 |
| C05 | `5e79e3f` | 持久 FileSink 与恢复 |
| C06 | `5faf3b8` | 固定安全传输闭环 |
| C07 | `bfaa3bb` | 有界 ZSTD 与启动器 |
| F01 | `2765b92` | 恢复 receipt 发布前补齐 force |
| C08 | `df84385` | 单任务有界并发 |
| C09 | `51a324f` | 分页对账及有界重试 |
| C10 | `79b5339` | 进程恢复、取消及校验生命周期 |
| F02 | `8f1943c` | 小帧对账完整峰值许可 |
| C11 | `56a2d83` | 有界计量 |
| C12 | `fa4bf1f` | 反馈试探、回退、冷却 |
| F03 | `0874118` | EOF 结束预算阻塞计时 |
| C13 | `8f191e7` | 五策略可复现实验入口 |
| C14 | `6324eeb` | 实验报告与匿名原始数据 |
| C15 | `b2478be` | SDK 指南、开发身份及双进程演示 |
| C16 | `d112586` | 验收证据与剩余限制 |

F04、F05 为条件式修复窗口；本轮检查未确认需要单独修复的实现缺陷，状态为 SKIPPED，不创建空提交。环境变化及性能目标未达成均保留为实验限制，不归类成已修复 bug。

## 任务状态

| 任务 | 状态 | 验收范围 |
| --- | --- | --- |
| T01 协议基础 | DONE | V01–V03 |
| T02 安全接纳 | DONE | V04–V06 |
| T03 持久适配 | DONE | V07–V09 |
| T04 固定闭环 | DONE | V10–V12 |
| T05 并发与恢复 | DONE | V13–V17 |
| T06 观测与反馈 | DONE | V18–V20，实现行为正确性 |
| T07 实验工具与报告 | DONE | V21–V22，受控证据已公开；研究收益未达成 |
| T08 SDK 交付 | DONE | V23，Windows/NTFS 范围内演示 |

## 验收证据

PASS 表示下表所列案例通过，不扩大为任意负载或任意平台保证。每项可通过源码测试入口复核；协议向量见[向量目录](../protocol/vectors/README.md)。

| 验收 | 状态 | 证据和边界 |
| --- | --- | --- |
| V01 编码/帧边界 | PASS | [MetadataCodecTest、FrameCodecTest、TransferJsonTest](../modules/core/src/test/java/io/github/aullchen/lcp/core/protocol/)；拒绝非规范编码、溢出与尾随数据 |
| V02 Merkle 承诺 | PASS | [MerkleFrontierTest](../modules/core/src/test/java/io/github/aullchen/lcp/core/protocol/MerkleFrontierTest.java) 与独立向量 |
| V03 依赖隔离 | PASS | api/core 独立 reactor 构建与依赖树；api 仅 JDK，core 仅 api/Jackson 的运行依赖 |
| V04 HPKE | PASS | [HpkeVectorTest](../modules/security/src/test/java/io/github/aullchen/lcp/security/HpkeVectorTest.java)、认证帧篡改集成 |
| V05 身份授权 | PASS | [SecurityTest](../modules/transport-http/src/test/java/io/github/aullchen/lcp/http/SecurityTest.java)；CA、SAN、用途、SPKI、密钥/路由授权 |
| V06 Open 幂等 | PASS | [TransferIntegrationTest](../examples/src/test/java/io/github/aullchen/lcp/examples/TransferIntegrationTest.java)；重放、冲突、过期及持久接纳 |
| V07 持久 ACK | PASS | [FileSinkCrashTest](../examples/src/test/java/io/github/aullchen/lcp/examples/FileSinkCrashTest.java)；八个进程退出边界与重开，不等同断电 |
| V08 损坏恢复 | PASS | [FileSinkTest](../examples/src/test/java/io/github/aullchen/lcp/examples/FileSinkTest.java)；完整 CRC 损坏冻结、残片截断、载荷缺失 |
| V09 单写者 | PASS | FileSinkTest；跨 JVM 根锁、未知目录与路径逃逸 |
| V10 流完整性 | PASS | [SourceTest](../examples/src/test/java/io/github/aullchen/lcp/examples/SourceTest.java) 与 TransferIntegrationTest；空流、短读、未知长度、跨块 |
| V11 压缩约束 | PASS | [ZstdCompressionTest](../modules/compression-zstd/src/test/java/io/github/aullchen/lcp/compression/ZstdCompressionTest.java) 及真实 NONE/ZSTD 传输 |
| V12 输出重读 | PASS | TransferIntegrationTest；修改、截短、追加输出均不能完成 |
| V13 并发与对账 | PASS | [ConcurrentStorageTest](../examples/src/test/java/io/github/aullchen/lcp/examples/ConcurrentStorageTest.java)、丢 ACK 与跨页核对集成 |
| V14 满预算重试 | PASS | TransferIntegrationTest；保留原材料并重新封装，含小帧控制响应预算回归 |
| V15 进程恢复 | PASS | [TransferNodeTest](../examples/src/test/java/io/github/aullchen/lcp/examples/TransferNodeTest.java)、[SourceControlTest](../examples/src/test/java/io/github/aullchen/lcp/examples/SourceControlTest.java) 及源新 ID 恢复集成 |
| V16 Finish 恢复 | PASS | TransferIntegrationTest；后台单校验器、VERIFYING 重启、完成响应丢失 |
| V17 取消和关闭 | PASS | [LifecycleStorageTest](../examples/src/test/java/io/github/aullchen/lcp/examples/LifecycleStorageTest.java) 及取消竞争/超时许可集成 |
| V18 计量确定性 | PASS | [TransferMetricsTest](../modules/core/src/test/java/io/github/aullchen/lcp/core/stream/TransferMetricsTest.java)；固定时钟、去重与 EOF |
| V19 压力与恢复 | PASS | [FeedbackControllerTest](../modules/core/src/test/java/io/github/aullchen/lcp/core/stream/FeedbackControllerTest.java) 及真实 BUSY 降窗集成 |
| V20 试探回退 | PASS | FeedbackControllerTest；两轮评估、冷却、范围、无效观测 |
| V21 对照实验 | PASS（受控范围） | [实验报告](experiments/feedback-v1/README.md)：90 次训练、275 次评测及 20 次简化复测；可见处理器数变化已披露，不能作严格恒定环境的因果结论 |
| V22 资源边界 | PASS（已测规模） | 同报告的 4 次资源检查：maxChunks=3 明确拒绝、4 精确完成；1/512 MiB RSS；另有[百万槽位近满恢复与精确配额验证](experiments/index-scale-v1/README.md)，不外推任意长度 |
| V23 无数据库演示 | PASS（Windows/NTFS） | [SDK 指南](sdk.md)：无构建产物的源码副本完成构建、开发身份生成、双 JVM 传输及完成态重查；不是全新操作系统安装或其他平台验收 |

## 可重复验证入口

使用 JDK 17，在源码根目录执行。Windows 命令如下；失败必须保留并定位，不以重复运行掩盖失败。

```powershell
.\mvnw.cmd -Pbenchmarks clean verify
.\mvnw.cmd -pl modules/core -am dependency:tree "-Dscope=runtime"
```

最终快速回归包含 303 项测试（常规 302 项加代理字节流测试），失败、错误和跳过均为 0。`benchmarks` profile 只构建工具并执行快速测试，不自动跑性能矩阵。Surefire 报告位于各模块 `target/surefire-reports`，为生成产物，不纳入源码。

恢复演示已包含在回归中，也可单独运行：

```powershell
.\mvnw.cmd -pl examples -am test "-Dtest=TransferNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

该入口执行真实双 JVM：普通传输，以及目标在 receipt force 后退出、同身份重启、存活源对账完成；然后用新源 JVM 重查原结果，校验完整输出且不生成重复任务。开发身份生成器复用于该测试，已有目录必须拒绝且原密钥保持不变。

SDK 指南另从无构建产物的源码副本运行 `clean install`、复制演示依赖和默认双节点命令。8,388,609 字节生成流逐字节匹配独立的固定 seed 参考序列；重跑源返回同一结果，目标只有一份输出。这是示例复现证据，不是新的性能测量。构建使用已有依赖缓存，不声称验证了离线首次安装或新机器网络配置。

性能原始档案及 SHA-256 见[实验环境文件](experiments/feedback-v1/environment.json)；[实验入口](../benchmarks/README.md)定义完整复现步骤。文档交付没有改变调度阈值，不重跑或替换原有负面结果。

## 剩余限制与后续选择

- M0 固定闭环与 M1 恢复在上述验收范围通过。M2 的反馈实现及实验交付完成，预定研究收益未达成；FULL 相对 B1 在阶跃、慢 Sink 的完成时间中位数分别增加约 9.5%、41.2%。WC 确认轮也未达到目标。
- 同 JVM 的源、目标和代理共享 CPU/RSS；部分运行的可见处理器数不同。只有五次重复，短任务可能未完成一次试探；不能宣传 WAN、稳态联合调度或单端内存收益。
- 超百万槽位、百万次真实网络发送、任意乱序日志成本、独立双主机恒定资源性能、Linux/macOS 与机器断电测试均为 NOT_RUN。现有小配额边界和 RSS 证据不替代这些验证。
- FileSink 只面向专用可信文件系统；没有业务导入事务、自动删除、多任务调度、数据库适配、UI 或发布自动化。原生调用不承诺立即中断；关闭超时仍需等待实际消费者退出。

下一步可维持现有固定策略交付范围，按具体宿主需求接入；若继续研究性能，应单独安排固定资源的实验与管线利用率分析，再决定是否改变策略版本。两者都不以现有负面结果推导额外功能需求。
