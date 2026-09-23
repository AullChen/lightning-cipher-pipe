# 反馈压力与完成试探诊断

本报告验证信号与完成计数，不评估算法相对基线的收益。2026-09-23，Windows / Microsoft OpenJDK 17.0.7；源、目标及受控代理在同一 JVM，`-Xms128m -Xmx768m`，每端字节预算 512 MiB。原始结果见 [results.json](results.json)。

## 压力信号

固定策略 B0、ZSTD 等级 1、256 KiB 块、窗口 2，输入 16 MiB。使用 Python `random.Random(42).randbytes(1048576)` 生成 1 MiB 块并重复 16 次。stable 无人为延迟；sink 在载荷写入后等待 80 ms；journal 在持有 receipt 日志锁的写入后等待 80 ms。每种场景独立 JVM 运行 3 次，全部结果保留。

| 场景 | ACK P95 范围 | queueShare | persistShare 范围 | BUSY（各次） |
| --- | --- | --- | --- | --- |
| stable | 20.97–42.99 ms | 0 | 0.166–0.216 | 2 / 1 / 0 |
| sink | 105.47–109.16 ms | 0 | 0.918–0.927 | 0 / 0 / 0 |
| journal | 184.47–185.20 ms | 0 | 0.964–0.965 | 0 / 0 / 0 |

持久提交占比和 ACK 时长能区分这三组条件；现有 queueShare 在本组中没有判别力。journal 的提交计时包括日志锁等待，不能解释为纯磁盘耗时。stable 出现的 BUSY 原样保留；BUSY 不一定随本组注入延迟增加。该证据支持继续讨论信号选择，不支持直接把 persistShare 替换为控制器压力权重。

## 完成试探

另以同一生成块重复 64 次形成 64 MiB 输入，FULL / sink、初始块 256 KiB、初始窗口 2、初始等级 1 运行一次。窗口试探 started=1、evaluated=1、retained=1；最终窗口 3。压缩与块大小试探均为 0，不能声称三维联合控制在线有效。

固定序列测试 `diagnosticsDistinguishEvaluationRollbackAndInterruptedTrialsAcrossDimensions` 分别覆盖窗口完整评估并保留、压缩完整评估后回退、块大小完整评估并保留，以及下一窗口试探被压力中止；中途单轮不会记为完整评估。此类序列证明计数和既定规则行为，不是在线收益证据。

10 次传输全部完成，输入/输出 SHA-256 一致，双方结束租约为 0。旧研究中的负面结果保持有效；本诊断不重新选择 B1、不修改 policyVersion 1 阈值。D01 应基于这些限制提出具体方案，再经确认进入 R09。

## 复现

执行 `mvnw.cmd -Pbenchmarks package -DskipTests`，使用 `benchmarks/target/classes;benchmarks/target/lib/*` classpath 运行 `io.github.aullchen.lcp.examples.BenchmarkRun INPUT NEW_OUTPUT STRATEGY SCENARIO train 262144 2 1 512`。前三组 STRATEGY=B0、SCENARIO=stable/sink/journal，长样本 STRATEGY=FULL、SCENARIO=sink。启动属性必须包含 `-Dsun.net.httpserver.maxReqTime=60 -Dsun.net.httpserver.maxRspTime=60 -Djdk.httpserver.maxConnections=32`。
