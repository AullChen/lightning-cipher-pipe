# 帧转换分配诊断

2026-09-23，Windows / Microsoft OpenJDK 17.0.7，固定 `-Xms64m -Xmx256m`。每行独立 JVM，预热 20 次、测量 40 次；使用 ThreadMXBean 统计当前线程分配，外部每 20 ms 采样进程工作集。原始数据见 [results.csv](results.csv)。

范围为帧读取/验证/表示转换，使用固定种子的高熵输入和实际 NONE/ZSTD 压缩长度。源端测量编码路径；目标 legacy 在同一版本中重建原来的 read→复制字段→encode→decode 路径，direct 使用 read→validate。线程分配测量段聚焦帧表示转换；进程工作集还覆盖预生成输入与压缩材料。密码、网络和持久化路径由传输实验另行评估。

| 端点 / 明文大小 | NONE 每帧分配：legacy → direct | ZSTD 每帧分配：legacy → direct |
| --- | --- | --- |
| 源 / 256 KiB | 263336 → 263336 B | 263352 → 263352 B |
| 源 / 1 MiB | 1049768 → 1049768 B | 1049800 → 1049800 B |
| 目标 / 256 KiB | 792136 → 264928 B | 792184 → 264944 B |
| 目标 / 1 MiB | 3151432 → 1051360 B | 3151544 → 1051406 B |

目标帧转换分配减少约 66.6%，源端该路径不变。采样峰值工作集为 50–84 MiB，各行数值保留在 CSV；启动、GC 和原生加载会影响结果，进程工作集按本次采样值报告。

预算公式保持原值。以 9 MiB 最大帧、8 MiB 最大明文、128 MiB 预算和请求窗口 4 计算，本诊断的 NONE/ZSTD 有效窗口均为 1。该值用于核对当前预算与窗口的关系。

复现：`mvnw.cmd -Pbenchmarks package -DskipTests` 后，以 `benchmarks/target/classes;benchmarks/target/lib/*` 为 Windows classpath 运行 `io.github.aullchen.lcp.examples.FrameAllocationRun source|target legacy|direct 0|1 262144|1048576`。输出列为 role、path、codec、plainBytes、frameBytes、allocatedBytesPerFrame、effectiveWindow；工作集需由外部进程采样补充。诊断结果与完整传输实验分别归档。

## 后续改进

以完整块生命周期的同时存活对象为依据评估预算精度，补充重复 RSS 采样，并将分配改善放入端到端传输实验中考察。
