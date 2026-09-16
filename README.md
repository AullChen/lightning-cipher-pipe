# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。已实现固定策略、NONE/ZSTD 编码的认证传输闭环：持久接纳 Open、加密块传输、持久 receipt，以及 Finish 后的输出重读验证。资源预算下的分块、并发与压缩联合反馈调度是后续研究目标，尚无性能收益结论。

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
| `lcp-core` | 严格 CBOR/JSON、有界帧、按序分块、字节许可、Merkle 与输出重读 |
| `lcp-security` | 固定 HPKE Auth、授权目录、实际 TLS SPKI 绑定 |
| `lcp-transport-http` | TLS 1.3 mTLS、有界请求、固定策略发送器及传输端点 |
| `lcp-compression-zstd` | 可选的有界独立 ZSTD 块 |
| `lcp-examples` | File/Generator Source、FileSink、双节点启动入口与集成测试 |

自动化测试覆盖独立[协议向量](protocol/vectors/README.md)、持久化故障、跨进程锁及空流、1 字节、跨块的真实 TLS 传输。帧认证成功不等于传输完成：目标必须重读持久输出，通过摘要、Merkle 根和长度校验后才能发布 COMPLETED。

当前支持单任务、1–16 的有界窗口和 NONE/ZSTD。已支持 ACK 丢失后的分页对账与有界重试；源控制记录、进程退出后的完整恢复流程和反馈调度尚未完成。

参见[压缩与启动示例](docs/compression.md)、[固定策略传输](docs/transfer.md)、[文件持久化](docs/storage.md)、[实现状态](docs/development-progress.md)、[构建与依赖](docs/build-baseline.md)及[认证边界](docs/security.md)。
