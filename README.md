# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。已实现 FIXED/FEEDBACK 策略、NONE/ZSTD 编码的认证传输闭环：持久接纳 Open、加密块传输、持久 receipt，以及 Finish 后的输出重读验证。反馈策略在资源预算内试探分块大小、并发窗口与压缩等级，支持回退与冷却；尚无性能收益结论。

## 构建

使用 JDK 17，通过 Maven Wrapper 构建：

```sh
./mvnw clean verify
./mvnw -pl modules/core -am dependency:tree
```

Windows 对应使用 `mvnw.cmd`。首次运行需要下载固定版本的 Maven 与依赖。快速测试入口为 `test`，完整快速构建为 `clean verify`。

## 当前实现

| 模块 | 功能 |
| --- | --- |
| `lcp-api` | JDK-only 值对象、Source/Sink SPI、持久状态与结果 |
| `lcp-core` | 严格 CBOR/JSON、有界帧、按序分块、字节许可、Merkle、输出重读、有界指标与反馈控制 |
| `lcp-security` | 固定 HPKE Auth、授权目录、实际 TLS SPKI 绑定 |
| `lcp-transport-http` | TLS 1.3 mTLS、有界请求、双策略发送器及传输端点 |
| `lcp-compression-zstd` | 可选的有界独立 ZSTD 块 |
| `lcp-examples` | File/Generator Source、FileSink、双节点启动入口与集成测试 |

自动化测试覆盖独立[协议向量](protocol/vectors/README.md)、持久化故障、跨进程锁及空流、1 字节、跨块的真实 TLS 传输。帧认证成功不等于传输完成：目标必须重读持久输出，通过摘要、Merkle 根和长度校验后才能发布 COMPLETED。

当前支持单任务、1–16 的有界窗口和 NONE/ZSTD。已支持 ACK 丢失后的分页对账与有界重试；已支持源控制记录、目标重启、VERIFYING 恢复和取消竞争处理。已支持有界指标与反馈调度；性能实验尚未完成。

参见[压缩与启动示例](docs/compression.md)、[传输接口](docs/transfer.md)、[反馈策略](docs/feedback.md)、[传输观测](docs/metrics.md)、[文件持久化](docs/storage.md)、[实现状态](docs/development-progress.md)、[构建与依赖](docs/build-baseline.md)及[认证边界](docs/security.md)。
