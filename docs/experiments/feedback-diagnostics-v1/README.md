# 反馈压力与完成试探诊断

本报告验证压力信号与完整试探计数。2026-09-23，Windows / Microsoft OpenJDK 17.0.7；源、目标及受控代理在同一 JVM，`-Xms128m -Xmx768m`，每端字节预算 512 MiB。原始结果见 [results.json](results.json)。

## 压力信号

固定策略 B0、ZSTD 等级 1、256 KiB 块、窗口 2，输入 16 MiB。使用 Python `random.Random(42).randbytes(1048576)` 生成 1 MiB 块并重复 16 次。stable 无人为延迟；sink 在载荷写入后等待 80 ms；journal 在持有 receipt 日志锁的写入后等待 80 ms。每种场景独立 JVM 运行 3 次，全部结果保留。

| 场景 | ACK P95 范围 | queueShare | persistShare 范围 | BUSY（各次） |
| --- | --- | --- | --- | --- |
| stable | 20.97–42.99 ms | 0 | 0.166–0.216 | 2 / 1 / 0 |
| sink | 105.47–109.16 ms | 0 | 0.918–0.927 | 0 / 0 / 0 |
| journal | 184.47–185.20 ms | 0 | 0.964–0.965 | 0 / 0 / 0 |

持久提交占比和 ACK 时长能区分这三组条件；queueShare 在各组均为 0。journal 的提交计时包含日志锁等待；stable 的 BUSY 计数为 2 / 1 / 0。后续通过独立实验检验这些指标是否适合用于控制。

## 完成试探

另以同一生成块重复 64 次形成 64 MiB 输入，FULL / sink、初始块 256 KiB、初始窗口 2、初始等级 1 运行一次。窗口试探 started=1、evaluated=1、retained=1；最终窗口 3。压缩与块大小试探计数均为 0。

固定序列测试 `diagnosticsDistinguishEvaluationRollbackAndInterruptedTrialsAcrossDimensions` 分别覆盖窗口完整评估并保留、压缩完整评估后回退、块大小完整评估并保留，以及下一窗口试探被压力中止；完整评估在两轮结束后计数。固定序列用于验证计数与规则行为，在线收益由策略对照实验衡量。

10 次传输全部完成，输入/输出 SHA-256 一致，双方结束租约为 0。本诊断使用既定参数与 policyVersion 1 阈值，数据与既有策略报告共同归档。

## 后续改进

增加输入长度，观察各维完整评估；记录压力原因，通过独立对照检验信号选择对控制效果的影响。后续实验见[完整周期诊断](../control-diagnostics-v1/README.md)。

## 复现

执行 `mvnw.cmd -Pbenchmarks package -DskipTests`，使用 `benchmarks/target/classes;benchmarks/target/lib/*` classpath 运行 `io.github.aullchen.lcp.examples.BenchmarkRun INPUT NEW_OUTPUT STRATEGY SCENARIO train 262144 2 1 512`。前三组 STRATEGY=B0、SCENARIO=stable/sink/journal，长样本 STRATEGY=FULL、SCENARIO=sink。启动属性必须包含 `-Dsun.net.httpserver.maxReqTime=60 -Dsun.net.httpserver.maxRspTime=60 -Djdk.httpserver.maxConnections=32`。
