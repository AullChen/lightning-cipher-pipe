# 实验索引

实验均经过真实认证、加密和文件持久化路径。各报告附有运行配置、结果和原始数据。

| 研究 | 核心问题 | 证据 |
| --- | --- | --- |
| [容量变化与窗口适应](window-adaptation-v1/README.md) | 窗口反馈如何影响初值敏感性与响应速度？ | 15 次对照、统一时间轴、全部轨迹 |
| [二维策略比较](policy-v2/README.md) | 固定压缩等级后，控制成本如何变化？ | sink/journal，18 次对照 |
| [完整控制周期](control-diagnostics-v1/README.md) | 各调节维度是否完成试探与评估？ | 30 次诊断与分维计数 |
| [压力信号诊断](feedback-diagnostics-v1/README.md) | 排队与持久化观测能解释哪些行为？ | 压力矩阵 |
| [共同管线比较](pipeline-v1/README.md) | 同一发送管线下，各策略表现如何？ | 固定训练基线与独立评测 |
| [五策略比较](feedback-v1/README.md) | 窗口、块大小、压缩的组合效果如何？ | 训练、评测与简化对照 |
| [帧分配](frame-allocation-v1/README.md) | 完整块处理的分配与峰值是多少？ | 分配诊断 |
| [索引规模](index-scale-v1/README.md) | 大索引恢复是否满足预算和正确性？ | 65,536 / 1,000,000 槽位检查 |

建议从容量变化研究开始，再结合[反馈规则](../feedback.md)理解机制。复现命令集中在[实验工具指南](../../benchmarks/README.md)，后续问题见[研究计划](../roadmap.md)。
