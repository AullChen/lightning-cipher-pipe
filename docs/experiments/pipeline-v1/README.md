# 反馈策略 v1：共同管线复评

本报告同时记录传输正确性与策略性能。所有组共用真实认证、FileSink 持久回执、Finish 重读及完整输出 SHA-256 校验。结果只适用于下述受控环境。

实验按 [共同管线协议](../../pipeline-protocol.md)执行，使用源码 `af099a0`，每个 JVM 均固定 `-XX:ActiveProcessorCount=4`。五策略共用完成补位管线与帧处理优化；[首次实验与完整结果](../feedback-v1/README.md)保留。两轮资源配置不同，策略效果按各自矩阵内的对照解读。

## 方法与数据

五策略为固定 B0、训练选参固定 B1、仅窗口 B2、窗口加块大小 WC、完整三参数 FULL。B0 为 4 MiB / 窗口 4 / ZSTD 3。训练使用独立种子与时序，六组候选在三场景各重复五次，共 90 次；评测有 11 个数据/场景组合，每策略重复五次，共 275 次。顺序按重复轮次交错。

B1 全局选定参数为 **8 MiB / 窗口 4 / ZSTD 1**；评测阶段统一使用该组冻结参数。训练评分见 [selection.json](selection.json)。

评测长输入 512 MiB，短合成输入 1 MiB；公开输入由四份未修改的 RFC 原文按编号连接。不同种子只影响伪随机部分；重复文本采用同一模式，语料范围由输入清单定义。数据摘要及出处见 [inputs.json](inputs.json)。环境参数以独立采集记录为准。详细配置见 [environment.json](environment.json)。

源、目标与 TLS 透明代理位于同一 JVM，CPU/RSS 均为三者合计。每次独立 JVM，输入预读校验在计时外；总完成时间包括 Open/TLS、传输、重试、退避及 Finish 重读。源码版本和有效初始窗口保存在每条原始记录中。硬件逻辑处理器数与 JVM 实际报告的可用处理器数分别记录和分析。此配置可容纳四份完整块许可，Open 协商将反馈窗口上限收紧为 4；初始即在上限，窗口试探只能在降窗后尝试恢复，最大物理窗口为 4。

网络阶跃、高 RTT 和断线由有界字节流代理模拟，慢 Sink 在真实写入与 force 之间增加延迟。控制轨迹、数据生成、内存口径和命令详见 [实验协议](../../../benchmarks/README.md)。实验采用单机受控网络模型。

## 完成时间

单位秒，列为五次中位数。括号标明成功数，统计与评价保留全部样本。P95（五次时为最大值）、最小/最大值和所有辅助指标见机器可读结果。

| 场景 / 数据 / 大小 | B0 | B1 | B2 | WC | FULL |
| --- | ---: | ---: | ---: | ---: | ---: |
| stable / text / long | 3.891 (5/5) | 3.691 (5/5) | 3.839 (5/5) | 3.931 (5/5) | 3.867 (5/5) |
| stable / mixed / long | 5.152 (5/5) | 4.852 (5/5) | 5.069 (5/5) | 5.033 (5/5) | 5.042 (5/5) |
| stable / entropy / long | 6.031 (5/5) | 5.860 (5/5) | 6.045 (5/5) | 6.027 (5/5) | 5.958 (5/5) |
| step / mixed / long | 8.897 (5/5) | 8.756 (5/5) | 8.838 (5/5) | 8.810 (5/5) | 8.812 (5/5) |
| rtt / text / long | 10.414 (5/5) | 6.563 (5/5) | 8.734 (5/5) | 12.821 (5/5) | 8.736 (5/5) |
| sink / mixed / long | 10.426 (5/5) | 7.627 (5/5) | 11.265 (5/5) | 10.388 (5/5) | 14.969 (5/5) |
| outage / mixed / long | 6.066 (5/5) | 5.795 (5/5) | 6.293 (5/5) | 6.320 (5/5) | 6.436 (5/5) |
| stable / text / short | 0.296 (5/5) | 0.290 (5/5) | 0.278 (5/5) | 0.289 (5/5) | 0.293 (5/5) |
| stable / mixed / short | 0.342 (5/5) | 0.322 (5/5) | 0.328 (5/5) | 0.330 (5/5) | 0.332 (5/5) |
| stable / entropy / short | 0.350 (5/5) | 0.353 (5/5) | 0.357 (5/5) | 0.351 (5/5) | 0.354 (5/5) |
| stable / public / short | 0.320 (5/5) | 0.325 (5/5) | 0.332 (5/5) | 0.323 (5/5) | 0.331 (5/5) |

有效明文吞吐如下，单位 MiB/s，包含 Finish 的总完成时间作为分母。其他辅助指标的中位数在 summary.json，逐次值在原始档案。

| 场景 / 数据 / 大小 | B1 吞吐 | FULL 吞吐 |
| --- | ---: | ---: |
| stable / text / long | 138.71 | 132.40 |
| stable / mixed / long | 105.53 | 101.55 |
| stable / entropy / long | 87.37 | 85.94 |
| step / mixed / long | 58.48 | 58.10 |
| rtt / text / long | 78.01 | 58.61 |
| sink / mixed / long | 67.13 | 34.20 |
| outage / mixed / long | 88.35 | 79.56 |
| stable / text / short | 3.45 | 3.42 |
| stable / mixed / short | 3.10 | 3.01 |
| stable / entropy / short | 2.83 | 2.83 |
| stable / public / short | 3.03 | 2.99 |

## 预选目标与统计区间

预选目标仅为阶跃和慢 Sink：相对 B1 完成时间中位数降低至少 10% 且配对区间下界大于 0；或重传帧字节中位数降低至少 20% 且时间增加不超过 5%。第二项适用于 B1 重传中位数大于零的组。下列区间为按重复轮次配对的 10000 次 bootstrap 95% 区间；每组仅五次，区间描述本机受控矩阵的重复测量差异。

- step 的 FULL 相对 B0 时间改善 0.9%。仍需结合失败与完整试探计数判断是否来自反馈。
- step：FULL 时间相对 B1 改善 -0.6%，95% 区间 [-2.3%, 1.0%]；重传字节中位数 B1=0、FULL=0。后续优化目标为达到上述预设收益阈值。
- sink 的 FULL 相对 B0 时间改善 -43.6%。仍需结合失败与完整试探计数判断是否来自反馈。
- sink：FULL 时间相对 B1 改善 -96.3%，95% 区间 [-106.1%, -36.8%]；重传字节中位数 B1=0、FULL=2.10013e+06。后续优化目标为达到上述预设收益阈值。

## 控制器行为与资源

FULL 的 13 次试探覆盖三个维度的启动，完整两轮评估计数为 0；WC 完整评估并保留一次块大小试探，B2 完整评估计数为 0。总回退包含压力中止，两类计数分别列出。传输结束时仍在进行的试探按原始状态保存，输入长度按冻结协议执行。

正式评测的最终有效窗口：B2 为 2–4，WC 为 1–4，FULL 为 1–4；所有评测的预算阻塞累计值均为 0，queueShare 均为 0。persistShare 仍仅供诊断，逐次值和分组中位数随档案公开。

- B2：55 次评测中 1 次启动试探，共 1 次试探、0 次回退；控制器累计耗时 26.820 ms。
- WC：55 次评测中 10 次启动试探，共 10 次试探、1 次回退；控制器累计耗时 26.223 ms。
- FULL：55 次评测中 13 次启动试探，共 13 次试探、3 次回退；控制器累计耗时 22.221 ms。
- B2/window: Started=1, Evaluated=0, Retained=0, RolledBack=0, Interrupted=0。
- B2/compression: Started=0, Evaluated=0, Retained=0, RolledBack=0, Interrupted=0。
- B2/chunk: Started=0, Evaluated=0, Retained=0, RolledBack=0, Interrupted=0。
- WC/window: Started=2, Evaluated=0, Retained=0, RolledBack=0, Interrupted=1。
- WC/compression: Started=0, Evaluated=0, Retained=0, RolledBack=0, Interrupted=0。
- WC/chunk: Started=8, Evaluated=1, Retained=1, RolledBack=0, Interrupted=0。
- FULL/window: Started=5, Evaluated=0, Retained=0, RolledBack=0, Interrupted=2。
- FULL/compression: Started=3, Evaluated=0, Retained=0, RolledBack=0, Interrupted=0。
- FULL/chunk: Started=5, Evaluated=0, Retained=0, RolledBack=0, Interrupted=1。
- 全部评测进程最大 RSS 为 928.1 MiB。RSS 为进程高水位与 50 ms 采样的最大值；包含两端和代理，按双端与代理的进程总量解读。
- Heap 为各内存池峰值之和；NMT native 为 JVM 退出时由 NMT 统计的原生内存快照。TLS 字节计量代理转发的加密流；应用重传计量提交给 HTTP 的完整帧。ACK P95 使用最近 64 条有效样本。各指标按其采集层级分别解读。

- `quota-3`：FAILED，输入 1 MiB，maxChunks=3，RSS 138.1 MiB，TRANSFER_QUOTA。
- `quota-4`：COMPLETED，输入 1 MiB，maxChunks=4，RSS 147.6 MiB。
- `scale-long`：COMPLETED，输入 512 MiB，maxChunks=131072，RSS 621.8 MiB。
- `scale-short`：COMPLETED，输入 1 MiB，maxChunks=131072，RSS 147.4 MiB。

配额 3/4 的案例分别验证明确拒绝与精确上限完成；长短输入使用相同的 131072 槽位元数据上限。所有成功输出哈希一致且两端许可归零。两种规模的 RSS 观察与固定容量索引/队列共同给出已测配置的资源证据；规模扩展和断电实验见后续计划。

## 有界简化复测

按预先声明的方案将压缩等级固定为 3（WC），保持 policyVersion 1 阈值；仅在阶跃和慢 Sink 各与冻结 B1 交错复测五次。本组检验固定参数的简化方案。为控制批次漂移，比较使用同批交错运行的 B1/WC。

- step：B1 8.734 秒，WC 8.824 秒，相对改善 -1.0%；失败 B1=0、WC=0。
- sink：B1 7.725 秒，WC 10.995 秒，相对改善 -42.3%；失败 B1=0、WC=0。

- step：WC 时间改善的配对 95% 区间 [-13.3%, 1.1%]；重传字节中位数 B1=0、WC=0；后续优化以预选目标为基准。
- sink：WC 时间改善的配对 95% 区间 [-72.0%, -16.1%]；重传字节中位数 B1=0、WC=0；后续优化以预选目标为基准。

## 研究总结与后续改进

安全传输、内容一致性与资源不变式均通过验证。完整矩阵为策略比较建立了基线；下一阶段以阶跃和慢 Sink 的预设收益阈值为目标，优化控制周期与恢复效率。

JVM 可用处理器数分布及启动参数见 environment.json 和原始记录；策略比较使用本轮固定资源条件，其他批次作为独立证据保存。

所有策略共用相同源码的传输管线。后续使用独立长输入诊断覆盖完整控制周期，并联合各维评估计数、实际窗口和预算受限时间分析稳态行为。

保留全部 389 次训练、评测、确认和资源检查结果，其中 388 次完成，另有 1 次预期配额拒绝。配额验证如下：

- `quota-3`：TRANSFER_QUOTA。

## 复现与证据

构建及运行命令见 [实验入口](../../../benchmarks/README.md)。同一输入清单、已冻结 B1 选择与轨迹定义可以复现方法；精确耗时受操作系统调度、JIT、缓存和其他负载影响。

- [所有分组统计](summary.json)
- [训练选择](selection.json)
- [数据校验与公开出处](inputs.json)
- [环境与原始档案校验值](environment.json)
- [完整原始 JSONL（gzip）](raw.jsonl.gz)

公开证据采用合成/公开数据摘要、参数和测量值，按脱敏格式归档。
