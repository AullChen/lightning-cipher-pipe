# 传输观测

`FixedTransferClient.metrics()` 返回当前或最近一次传输的只读快照。所有时长使用单机单调时钟，传输结束后固定总耗时。持久确认字节来自回执账本，重复 ACK 和分页对账不会重复累加；对账新增进度仅更新持久确认计数。

压缩时间只覆盖 `codec.compress` 调用，封装时间覆盖 HPKE 封装；ACK 时间从 HTTP 发送调用开始，到完整响应解析及回执校验完成。该指标覆盖应用请求与确认的完整路径。请求次数、重试及 BUSY 包含不产生有效样本的块请求。

目标端通过可选的 `SinkSession.reserve` 先校验并预留范围，然后调用预留对象的 `commit`。排队时间从预留完成到提交调用开始，持久时间覆盖完整提交调用，包括内部锁等待、写入和 force。采用直接提交接口的适配器将排队时间标为 `null`。重复提交的两个时长均为 `null`。

最多保存最近 64 条有效 ACK。重试、重复、缺失计时、负值或超过 ACK 时长的阶段计时不进入样本。P95 使用排序后的 `ceil(0.95*n)-1` 项，排队占比采用样本占比的中位数。每轮至少 16 个有效 ACK 且持续至少 2 秒；吞吐分母含重试和退避等待。窗口占满时间使用单调时钟积分；预算受限独立记录，EOF 后停止累计，即使末批仍在排空；总完成时间仍包含 Finish 重读验证。压缩忙碌率以一个固定压缩工作线程计算。FIXED 和反馈策略共用这些口径。

指标以匿名聚合形式记录，性能比较结合独立对照实验解读。

快照还记录 Chunk 请求的加密帧字节、其中属于重试请求的帧字节，以及决策次数、试探次数、回退次数和决策耗时。帧字节按提交给 HTTP 的完整帧计量；网络流量由实验代理另行统计实际转发的 TLS 字节。决策计数和耗时均采用固定大小聚合，不保存逐轮历史。

## 压力与试探诊断

`persistShare` 是同一批最多 64 条有效 ACK 的持久提交耗时占比中位数，与 `queueShare` 使用相同的有效性过滤。该指标综合反映提交内部锁等待、写入和刷盘成本。该观测只用于诊断，policyVersion 1 的决策输入和阈值保持不变。`effectiveWindow` 返回最近应用的物理窗口；累计预算受限时间仍单独记录。

`feedback` 按 window、compression、chunk 三维分别返回 started、evaluated、retained、rolledBack、interrupted。evaluated 只在完整两轮评估结束时递增，且等于 retained + rolledBack；压力提前撤销记录为 interrupted，与完整评估分别计数。started − evaluated − interrupted 表示尚在进行的试探。所有计数为固定大小聚合，快照使用不可变视图；原有 trials/rollbacks 保留兼容口径。

受控正常/慢提交/日志锁竞争样本与真实长传输的范围见[反馈诊断报告](experiments/feedback-diagnostics-v1/README.md)。联合控制的性能比较见[实验索引](experiments/README.md)。
