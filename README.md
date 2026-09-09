# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。研究目标是资源预算下的分块、并发与压缩联合反馈调度；安全传输、持久 ACK、恢复与性能收益尚未实现或验证。

## 构建

安装 JDK 17，设置 `JAVA_HOME` 并将其 `bin` 放在 PATH 前面。首次构建需要访问 Maven Central。

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -pl modules/core -am dependency:tree
```

Linux/macOS 对应使用 `./mvnw`（尚未实测）。快速测试入口为 `test`，完整快速构建为 `clean verify`。目前仅创建实际需要的 `modules/api`（JDK-only 公共值对象）和 `modules/core`（协议编码）；其他模块在实现时增加。没有占位测试或网络端点。

本地设计材料 `.local-docs/` 不纳入 Git。实施证据见 [开发进度](docs/development-progress.md)，依赖选择见 [构建基线](docs/build-baseline.md)。
