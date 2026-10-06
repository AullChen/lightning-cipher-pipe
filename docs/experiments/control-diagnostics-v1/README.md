# 完整控制周期与压力信号诊断

三条长传输均完成窗口、压缩和块大小的两轮评估，30 次传输全部通过完整性与资源检查。压力矩阵进一步揭示 queueShare 与 persistShare 的计量范围，为[v2 策略简化](../../policy-v2.md)提供依据。性能基线见[共同管线复评](../pipeline-v1/README.md)。

## 方法与范围

2026-10-01，Windows 11 / Microsoft OpenJDK 17.0.7；每次独立 JVM，固定 4 个可用处理器、堆 128–768 MiB。源、目标、TLS 代理同一 JVM；每端字节预算 512 MiB、元数据预算 8 MiB，真实 TLS/HPKE、FileSink 写入/force、最终重读 SHA-256 验证。主机采用共享 CPU 调度，期间存在短时验证负载；时间指标用于描述本批诊断。

固定种子 104729，交替写入 64 KiB 零字节与 CPython random.randbytes 熵块。压力输入 16 MiB，控制周期输入 512 MiB；大小与摘要见 [manifest.json](manifest.json)。执行前固定全部 30 个组合及顺序，每个组合执行一次并完整保存结果。完整记录见 [results.json](results.json)，门禁与聚合见 [summary.json](summary.json)。源码以 manifest 中逐 Java 文件 SHA-256 标识，基于 `37149f2` 加生命周期修复及独立诊断入口。

- 压力矩阵：固定 256 KiB 块、ZSTD 3；窗口 1/2/4 × stable/sink/journal × 3 次，共 27 次。每轮旋转窗口次序。sink 在载荷写入后延迟 80 ms；journal 在持有回执日志锁时延迟 80 ms；stable 使用自然提交时延。
- 控制周期：FULL-v1 / sink，初始 256 KiB、窗口 1、ZSTD 3；认证范围 256 KiB–1 MiB、窗口 1–4、ZSTD 1–5，三次。限制最大块使有限输入包含足够 ACK；保持每轮至少 16 ACK / 2 秒、两轮评估及两轮冷却的规则。
- 所有组 Limits 保持最大明文 8 MiB、帧 9 MiB、槽位 16，maxChunks=4096。协商有效最大窗口为 4。各组采用相同的最坏块预算。

## 完整周期

| 维度 | Started | Evaluated | Retained | RolledBack | Interrupted |
| --- | ---: | ---: | ---: | ---: | ---: |
| window | 8 | 7 | 7 | 0 | 0 |
| compression | 6 | 6 | 0 | 6 | 0 |
| chunk | 6 | 6 | 6 | 0 | 0 |

每个长样本的每个维度 evaluated 都大于 0，且 evaluated=retained+rolledBack；started 不少于 evaluated+interrupted。脚本按完整评估计数执行验收。EOF 时另有一次窗口试探处于进行状态，原始计数完整保留。

窗口与块大小在既定内部评估规则下被保留，压缩的六次完整试探均回退。内部“保留”反映当前评估规则的判定。此组聚焦控制周期，结果推动了固定压缩等级的独立对照研究。

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

queueShare 全部为 0。该指标测量取得 reservation 至调用 commit 的间隔；接收线程排队和 commit 内日志锁等待分别属于其他阶段。

sink 与 journal 的 persistShare 都较高，但其窗口响应不同：sink 的窗口 4 完成时间较短；journal 窗口 2 与 4 的耗时分别为 5.773 与 5.885 秒，ACK P95 随窗口增大。提交占比综合反映锁等待和持久化；stable 的短样本同时受建连和瞬时 BUSY 影响。

BUSY 汇总预算、槽位和生命周期锁等准入压力；响应 ACK 与归还接收资源之间也可能存在短间隔。原因分类是下一阶段观测工作的重点。

## 验证和下一步

30 次全部 COMPLETED，输出 SHA-256 与输入一致，两端许可归零。生命周期回归另在父实现分别复现 EXPIRED 和 UNKNOWN_COMMIT，修复后完整 `-Pbenchmarks verify` 通过 367 项测试。当前诊断覆盖单机双端的信号与控制周期。

三维完整周期为策略实验提供了可运行基础。下一步将提交阶段观测与压力原因结合，使用独立对照检验控制动作的效果；[v2 设计](../../policy-v2.md)及其对照实验承接压缩维度简化，默认版本保持 v1。

## 复现

安装 JDK 17 与 Python 3.10+，设置 JAVA_HOME 后执行：

```powershell
.\mvnw.cmd -Pbenchmarks package -DskipTests
python benchmarks/control_diagnostics.py benchmarks/results/control-diagnostics-new
```

输出使用新建目录；脚本生成输入、依次启动独立 JVM，保存日志、输入摘要、源码摘要、所有结果及 summary.json。单次 300 秒超时或完整性/许可失败即停止并保留证据；三维完成门禁失败也返回非零。运行诊断时应保持其他负载静止。该命令使用独立输出目录和固定策略参数，原训练/评测矩阵继续保留。
