# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。研究目标是资源预算下的分块、并发与压缩联合反馈调度；安全传输、持久 ACK、恢复与性能收益尚未实现或验证。

## 构建

安装 JDK 17，设置 `JAVA_HOME` 并将其 `bin` 放在 PATH 前面。首次构建需要访问 Maven Central。

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -pl modules/core -am dependency:tree
```

Linux/macOS 对应使用 `./mvnw`（尚未实测）。快速测试入口为 `test`，完整快速构建为 `clean verify`。目前仅创建实际需要的 `modules/api`（JDK-only 公共值对象）和 `modules/core`（协议编码）；其他模块在实现时增加。没有占位测试或网络端点。

功能与验证范围见 [实现状态](docs/development-progress.md)，依赖说明见 [构建与依赖](docs/build-baseline.md)。

## 当前实现：C02

- 不可变 Open/Accepted/Policy/Limits、Chunk/Finish AAD、FinishManifest、Receipt、Cancel 元数据。
- 严格规范 CBOR、Open HTTP JSON 投影、Open/Finish/binding 摘要与 HPKE info 字节编码。
- 有界 frame 前缀、内存/短读流解析和合成帧编码；先校验前缀与小 AAD，再读取大正文。
- 独立 [协议向量](protocol/vectors/README.md)，完整快速构建包含 81 项测试。

帧解析结果仍未认证。后续 HTTP 适配器必须先做 mTLS/路由认证、预算预留并提供有截止时间的有限输入流；HPKE 验证后才能接纳写入。当前没有网络端点、密码实现、文件传输、持久 ACK 或恢复能力。下一步是 C03 按序分块、输入源与 Merkle。
