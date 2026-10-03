# 反馈策略 v1：共同管线复评

本报告区分实现正确性与性能收益。所有组共用真实认证、FileSink 持久回执、Finish 重读及完整输出 SHA-256 校验。结果只适用于下述受控环境。

实验按 [共同管线协议](../../pipeline-protocol.md)执行，使用源码 `af099a0`，每个 JVM 均固定 `-XX:ActiveProcessorCount=4`。五策略共用完成补位管线与帧处理优化；[原实验及负面结果](../feedback-v1/README.md)保留。两轮资源配置不同，不能用新旧绝对耗时推导管线或算法的因果收益。

## 方法与数据

五策略为固定 B0、训练选参固定 B1、仅窗口 B2、窗口加块大小 WC、完整三参数 FULL。B0 为 4 MiB / 窗口 4 / ZSTD 3。训练使用独立种子与时序，六组候选在三场景各重复五次，共 90 次；评测有 11 个数据/场景组合，每策略重复五次，共 275 次。顺序按重复轮次交错。

B1 全局选定参数为 **8 MiB / 窗口 4 / ZSTD 1**；不按评测场景重新选参。训练评分见 [selection.json](selection.json)。

评测长输入 512 MiB，短合成输入 1 MiB；公开输入由四份未修改的 RFC 原文按编号连接。不同种子只影响伪随机部分；重复文本沿用同一模式，不代表独立语料泛化。数据摘要及出处见 [inputs.json](inputs.json)。环境参数独立记录，不从结果推测硬件。详细配置见 [environment.json](environment.json)。

源、目标与 TLS 透明代理位于同一 JVM，CPU/RSS 均为三者合计。每次独立 JVM，输入预读校验在计时外；总完成时间包括 Open/TLS、传输、重试、退避及 Finish 重读。源码版本和有效初始窗口保存在每条原始记录中。硬件逻辑处理器数与 JVM 实际报告的可用处理器数分别记录，不能混为一谈。此配置可容纳四份完整块许可，Open 协商将反馈窗口上限收紧为 4；初始即在上限，窗口试探只能在降窗后尝试恢复，不能试增至 5。

网络阶跃、高 RTT 和断线由有界字节流代理模拟，慢 Sink 在真实写入与 force 之间增加延迟。控制轨迹、数据生成、内存口径和命令详见 [实验协议](../../../benchmarks/README.md)。这些不是独立物理主机上的 WAN 测试。

## 完成时间

单位秒，列为五次中位数。括号标明成功数；失败不删除，也不以成功子集宣称改进。P95（五次时为最大值）、最小/最大值和所有辅助指标见机器可读结果。

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

## 预选目标与不确定性

预选目标仅为阶跃和慢 Sink：相对 B1 完成时间中位数降低至少 10% 且配对区间下界大于 0；或重传帧字节中位数降低至少 20% 且时间增加不超过 5%。B1 重传中位数为零时，第二项不适用。下列区间为按重复轮次配对的 10000 次 bootstrap 95% 区间；每组仅五次，区间不代表其他机器或真实网络的保证。

- step 的 FULL 相对 B0 时间改善 0.9%。仍需结合失败与完整试探计数判断是否来自反馈。
- step：FULL 时间相对 B1 改善 -0.6%，95% 区间 [-2.3%, 1.0%]；重传字节中位数 B1=0、FULL=0。未满足可确认的收益目标。
- sink 的 FULL 相对 B0 时间改善 -43.6%。仍需结合失败与完整试探计数判断是否来自反馈。
- sink：FULL 时间相对 B1 改善 -96.3%，95% 区间 [-106.1%, -36.8%]；重传字节中位数 B1=0、FULL=2.10013e+06。未满足可确认的收益目标。

## 控制器行为与资源

FULL 的 13 次试探虽覆盖三个维度的启动，但完整两轮评估均为 0；WC 只完整评估并保留过一次块大小试探，B2 为 0。总回退计数包含压力中止，不等同于完整评估后回退；未评估且未中止的试探在传输结束时仍未完成。此轮不能证明稳态联合控制有效，也不事后延长样本补足周期。

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
- 全部评测进程最大 RSS 为 928.1 MiB。RSS 为进程高水位与 50 ms 采样的最大值；包含两端和代理，不能当作单端缓冲预算。
- Heap 为各内存池峰值之和；NMT native 为 JVM 退出快照，非峰值，也不覆盖所有第三方原生分配。TLS 字节不包含 TCP/IP 首部和内核重传；应用重传按提交给 HTTP 的完整帧计量，可能含未全部发出的请求，不等价于抓包重传字节。ACK P95 为最近 64 条有效样本，不是全程直方图。

- `quota-3`：FAILED，输入 1 MiB，maxChunks=3，RSS 138.1 MiB，TRANSFER_QUOTA。
- `quota-4`：COMPLETED，输入 1 MiB，maxChunks=4，RSS 147.6 MiB。
- `scale-long`：COMPLETED，输入 512 MiB，maxChunks=131072，RSS 621.8 MiB。
- `scale-short`：COMPLETED，输入 1 MiB，maxChunks=131072，RSS 147.4 MiB。

配额 3/4 的案例分别验证明确拒绝与精确上限完成；长短输入使用相同的 131072 槽位元数据上限。所有成功输出哈希一致且两端许可归零。两种规模的 RSS 观察与固定容量索引/队列为已测配置提供资源边界证据，不外推任意输入长度或断电持久性。

## 有界简化复测

按预先声明的方案将压缩等级固定为 3（WC），不改变 policyVersion 1 阈值；仅在阶跃和慢 Sink 各与冻结 B1 交错复测五次。此轮用于确认简化方案，不据此继续挑选或调参。确认批次与前批耗时存在漂移，只比较同批交错运行的 B1/WC，不作跨批绝对耗时比较。

- step：B1 8.734 秒，WC 8.824 秒，相对改善 -1.0%；失败 B1=0、WC=0。
- sink：B1 7.725 秒，WC 10.995 秒，相对改善 -42.3%；失败 B1=0、WC=0。

- step：WC 时间改善的配对 95% 区间 [-13.3%, 1.1%]；重传字节中位数 B1=0、WC=0，未达到预选目标。
- sink：WC 时间改善的配对 95% 区间 [-72.0%, -16.1%]；重传字节中位数 B1=0、WC=0，未达到预选目标。

## 结论与限制

本轮未确认完整反馈策略相对训练固定基线达到预定收益。已测安全传输、内容与资源不变式通过，研究收益目标尚未达成。

JVM 可用处理器数分布及启动参数见 environment.json 和原始记录；仅在本轮固定资源内比较策略，不把与旧报告之间的差异归因于控制器。

所有策略共用相同源码的传输管线。运行时间不足以完成试探的组不能证明稳态收益，应同时检查各维完整评估计数、实际窗口和预算受限时间。

保留全部 389 次训练、评测、确认和资源检查结果，其中 1 次非成功（含预期配额拒绝）。失败明细如下：

- `quota-3`：TRANSFER_QUOTA。

## 复现与证据

构建及运行命令见 [实验入口](../../../benchmarks/README.md)。同一输入清单、已冻结 B1 选择与轨迹定义可以复现方法；精确耗时受操作系统调度、JIT、缓存和其他负载影响。

- [所有分组统计](summary.json)
- [训练选择](selection.json)
- [数据校验与公开出处](inputs.json)
- [环境与原始档案校验值](environment.json)
- [完整原始 JSONL（gzip）](raw.jsonl.gz)

公开证据仅含合成/公开数据摘要、参数和测量值；不含私钥、明文载荷、个人路径或机器标识。
