# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。研究目标是资源预算下的分块、并发与压缩联合反馈调度。当前已实现编码、输入流和认证组件；完整传输、持久 ACK、恢复与性能收益尚待后续阶段验证。

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
| `lcp-api` | JDK-only 值对象、Chunk、TransferSource |
| `lcp-core` | 严格 CBOR/JSON、有界帧、按序分块、字节许可、Merkle frontier |
| `lcp-security` | 固定 HPKE Auth、授权目录、Open 身份与 SPKI 绑定 |
| `lcp-transport-http` | TLS 1.3 mTLS、节点/主机名校验、有界线程与控制响应、超时 |
| `lcp-examples` | FileSource 和确定性 GeneratorSource |

全工程包含 154 项自动化测试和独立[协议向量](protocol/vectors/README.md)。当前尚未装配 Open/Chunk/Finish 传输端点、持久输出或恢复，不应将帧认证成功视为传输完成。

功能与验证范围见[实现状态](docs/development-progress.md)，依赖说明见[构建与依赖](docs/build-baseline.md)，认证与运行边界见[安全组件](docs/security.md)。
