# 完整控制周期与压力信号诊断

本诊断补足“是否实际完成两轮评估”的证据，不宣称修复了原性能劣势。三条长传输都完成窗口、压缩、块大小的评估；queueShare 在 27 条压力样本中没有判别力；高 persistShare 不能单独作为降窗依据。原[共同管线复评](../pipeline-v1/README.md)中的 FULL 负面结果不变。

## 方法与边界

2026-10-01，Windows 11 / Microsoft OpenJDK 17.0.7；每次独立 JVM，固定 4 个可用处理器、堆 128–768 MiB。源、目标、TLS 代理同一 JVM；每端字节预算 512 MiB、元数据预算 8 MiB，真实 TLS/HPKE、FileSink 写入/force、最终重读 SHA-256 验证。主机没有 CPU 排他隔离，期间存在短时验证负载，时间仅作诊断描述，不作性能验收或跨报告比较。

固定种子 104729，交替写入 64 KiB 零字节与 CPython random.randbytes 熵块。压力输入 16 MiB，控制周期输入 512 MiB；大小与摘要见 [manifest.json](manifest.json)。执行前固定全部 30 个组合及顺序，不重试失败进程、不丢弃样本。完整记录见 [results.json](results.json)，门禁与聚合见 [summary.json](summary.json)。源码以 manifest 中逐 Java 文件 SHA-256 标识，基于 `37149f2` 加本轮生命周期修复及独立诊断入口。

- 压力矩阵：固定 256 KiB 块、ZSTD 3；窗口 1/2/4 × stable/sink/journal × 3 次，共 27 次。每轮旋转窗口次序。sink 在载荷写入后延迟 80 ms；journal 在持有回执日志锁时延迟 80 ms；stable 不注入延迟。
- 控制周期：FULL-v1 / sink，初始 256 KiB、窗口 1、ZSTD 3；认证范围 256 KiB–1 MiB、窗口 1–4、ZSTD 1–5，三次。限制最大块使有限输入包含足够 ACK；不改变每轮至少 16 ACK / 2 秒、两轮评估及两轮冷却的规则。
- 所有组 Limits 保持最大明文 8 MiB、帧 9 MiB、槽位 16，maxChunks=4096。协商有效最大窗口为 4。没有通过降低最坏块预算为动态组制造优势。

## 完整周期

| 维度 | Started | Evaluated | Retained | RolledBack | Interrupted |
| --- | ---: | ---: | ---: | ---: | ---: |
| window | 8 | 7 | 7 | 0 | 0 |
| compression | 6 | 6 | 0 | 6 | 0 |
| chunk | 6 | 6 | 6 | 0 | 0 |

每个长样本的每个维度 evaluated 都大于 0，且 evaluated=retained+rolledBack；started 不少于 evaluated+interrupted。脚本将此作为门禁，未完成不能宣称通过。窗口剩余一次试探在 EOF 前没有完成，保留原始计数。

窗口与块大小在既定内部评估规则下被保留，压缩的六次完整试探均回退。内部“保留”不是对固定 B1 的独立收益证明；本组没有重新训练或比较 B1。该结果支持讨论减少压缩试探占用，不支持宣称任意数据都应固定压缩等级。

## 压力矩阵

下列时间及占比为三次中位数；ACK P95 本身只覆盖每次最后至多 64 个有效 ACK。

| 场景 | 窗口 | 完成秒数 | ACK P95 ms | queueShare | persistShare | BUSY 三次 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| stable | 1 | 1.413 | 15.2 | 0.000 | 0.203 | 0 / 0 / 0 |
| stable | 2 | 1.573 | 30.8 | 0.000 | 0.173 | 0 / 1 / 0 |
| stable | 4 | 2.423 | 68.4 | 0.000 | 0.187 | 1 / 0 / 1 |
| sink | 1 | 6.660 | 103.0 | 0.000 | 0.925 | 0 / 0 / 0 |
| sink | 2 | 4.223 | 103.8 | 0.000 | 0.917 | 0 / 0 / 0 |
| sink | 4 | 2.530 | 126.7 | 0.000 | 0.899 | 0 / 0 / 1 |
| journal | 1 | 6.438 | 97.7 | 0.000 | 0.925 | 0 / 0 / 0 |
| journal | 2 | 5.773 | 171.1 | 0.000 | 0.955 | 0 / 0 / 0 |
| journal | 4 | 5.885 | 496.4 | 0.000 | 0.974 | 0 / 0 / 0 |

queueShare 全部为 0。实现从取得 reservation 后到开始 commit 取时，不包括接收线程排队，也不包括 commit 内日志锁等待；不能据此断言接收端没有压力。

sink 与 journal 的 persistShare 都较高，但其窗口响应不同：sink 的窗口 4 完成时间较短；journal 的窗口 2 到 4 没有相应缩短，ACK P95 明显增大。提交占比包含锁等待，故“高占比就减半窗口”缺少依据。stable 的短样本受建连和瞬时 BUSY 影响，不能由此选出生产默认窗口。

BUSY 表示一次非阻塞准入失败，不区分预算、槽位或生命周期锁；响应 ACK 与归还接收资源之间也可能存在短间隔。不要把 BUSY 次数等同于磁盘拥塞程度，也不能把本矩阵中的全部 BUSY 归因于某一种竞争。

## 验证和下一步

30 次全部 COMPLETED，输出 SHA-256 与输入一致，两端许可归零。生命周期回归另在父实现分别复现 EXPIRED 和 UNKNOWN_COMMIT，修复后完整 `-Pbenchmarks verify` 通过 367 项测试。诊断不是负载测试或多主机验证。

在原报告范围外，本次补上了三维控制周期可完整执行的证据，也否定了直接以 persistShare 阈值降窗的简单方案。研究收益目标仍未完成；具体的[可选 v2 提案](../../feedback-v2-proposal.md)需单独确认，默认 v1 不变。

## 复现

安装 JDK 17 与 Python 3.10+，设置 JAVA_HOME 后执行：

```powershell
.\mvnw.cmd -Pbenchmarks package -DskipTests
python benchmarks/control_diagnostics.py benchmarks/results/control-diagnostics-new
```

输出目录必须不存在；脚本生成输入、依次启动独立 JVM，保存日志、输入摘要、源码摘要、所有结果及 summary.json。单次 300 秒超时或完整性/许可失败即停止并保留证据；三维完成门禁失败也返回非零。运行诊断时应保持其他负载静止。该命令不覆盖原冻结训练/评测矩阵，也不自动调整策略参数。
