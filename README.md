# LightningCipherPipe

轻量化、数据库无关的 Java 17 安全数据交换中间件。通过认证握手、独立加密分块、持久回执和完成重读校验，将有限字节流可靠地传送到目标端。

适用于需要自定义输入源、目标存储和明确资源预算的 Java 应用。项目提供 File/Generator 输入源、文件存储适配器及可运行的双节点示例。

## 特性

- **认证与加密**：TLS 1.3 mTLS、HPKE Auth、节点身份和路由授权绑定。
- **完整性与持久化**：块摘要、Merkle 根、持久 receipt；只有目标重读输出并验证通过才返回 `COMPLETED`。
- **有界资源**：按完成顺序补充在途槽位，窗口、缓冲字节和索引容量均有上限。
- **恢复与取消**：丢失 ACK 后分页对账、有界重试、目标重启恢复及明确的输入所有权。
- **压缩与调度**：NONE/ZSTD；固定参数 FIXED 和实验性 FEEDBACK 策略。

FEEDBACK 默认 v1 试探窗口、块大小与压缩等级；显式可选的 v2 固定压缩等级，只试探窗口与块大小。现有实验**未证实相对训练固定基线的预定性能收益**。不要将其视为自动获得更高吞吐的保证，详见[共同管线复评](docs/experiments/pipeline-v1/README.md)及 [v2 对照诊断](docs/experiments/policy-v2/README.md)。

当前研究聚焦“接收端能力变化时，窗口反馈能否降低选参敏感性并控制耗时”。[窗口对照研究](docs/experiments/window-adaptation-v1/README.md)以三个固定窗口、两个自适应初值完成 15 次公平对照，并提供一张机制图：初值敏感性降低，但自适应比每轮最佳固定配置慢 7.0%–41.7%，未达到预定耗时目标。

## 快速开始

### 环境要求

- **JDK 17**，`JAVA_HOME` 指向该 JDK。构建门禁当前仅接受 Java 17。
- 使用仓库内的 Maven Wrapper，无需另行安装 Maven；首次构建需要网络下载 Maven 和依赖。
- 示例需要可写的可信文件系统，无需数据库或外部服务。

在源码根目录执行：

```sh
./mvnw clean install
```

Windows PowerShell：

```powershell
.\mvnw.cmd clean install
```

这会构建模块、运行测试并将 `0.1.0-SNAPSHOT` 安装到 Maven 本地仓库。当前按源码构建使用，不假定该版本已发布到 Maven Central。

### 验证双节点传输

以下测试会自行准备短期身份、启动两个 JVM，并验证传输、目标重启恢复及输出内容：

```powershell
.\mvnw.cmd -pl examples -am test "-Dtest=TransferNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Unix shell 将 `.\mvnw.cmd` 换为 `./mvnw`。手动运行双节点、传送自己的文件及生成开发身份，请按 [SDK 指南](docs/sdk.md)操作；配置模板位于 [examples/config](examples/config/)。示例证书仅适用于短期 localhost 演示。

### 接入应用

完成源码安装后，添加所需模块依赖，例如：

```xml
<dependency>
  <groupId>io.github.aullchen</groupId>
  <artifactId>lcp-transport-http</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

使用 ZSTD 时另添加同版本的 `lcp-compression-zstd`。`lcp-api` 提供 Source/Sink 契约；完整装配示例见 [TransferNode.java](examples/src/main/java/io/github/aullchen/lcp/examples/TransferNode.java)，资源所有权和恢复要求见 [SDK 接入说明](docs/sdk.md#java-sdk-装配)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `modules/api` | 仅依赖 JDK 的值对象、Source/Sink SPI |
| `modules/core` | 编码、分块、预算、完整性校验与反馈控制 |
| `modules/security` | HPKE、密钥与身份绑定 |
| `modules/transport-http` | 认证 HTTP 传输、并发、对账与重试 |
| `modules/compression-zstd` | 有界 ZSTD 编解码 |
| `examples` | 文件适配器、双节点入口、配置与集成测试 |
| `protocol/vectors` | 独立编码与密码协议向量 |
| `benchmarks` | 可选实验工具与复现说明 |
| `docs` | 接入、协议行为、验收范围及公开实验档案 |

## 验证与实验

```powershell
# 快速测试
.\mvnw.cmd test
# 完整构建及基准工具的快速回归
.\mvnw.cmd -Pbenchmarks clean verify
```

`benchmarks` profile 不自动运行性能矩阵。独立实验需要 Python 3.10+，命令和数据口径见 [benchmarks/README.md](benchmarks/README.md)。已发表的原始摘要和负面结果保存在 `docs/experiments`，不以日常构建覆盖。

## 支持范围

- 当前提供单任务、有界窗口和文件参考适配器；不包含数据库适配、业务发布事务、多任务调度或多机接管。
- 参考环境已验证 Windows 11 / NTFS。Linux/macOS、网络文件系统和机器断电持久性尚无验收结论。
- 存活源可对账续传；源进程重启后，未完成任务须确认旧任务终态，再以新 ID 从头发送，不承诺从旧偏移续传。
- 取消或关闭超时不代表在途 I/O 已退出；持续存储访问拒绝仍可能失败。具体处理见[恢复与生命周期](docs/transfer.md)和[文件持久化](docs/storage.md)。

完整验收范围及剩余限制见[里程碑](docs/milestones.md)。

## 文档

| 主题 | 入口 |
| --- | --- |
| 构建与依赖 | [构建基线](docs/build-baseline.md) |
| 配置、接入与演示 | [SDK 指南](docs/sdk.md) |
| 身份、授权与密钥 | [认证边界](docs/security.md) |
| 传输与恢复 | [传输接口](docs/transfer.md)、[文件持久化](docs/storage.md) |
| 调度与观测 | [反馈策略](docs/feedback.md)、[计量口径](docs/metrics.md) |
| 实现及实验状态 | [实现状态](docs/development-progress.md)、[复评报告](docs/experiments/pipeline-v1/README.md) |

## 贡献

欢迎通过 Issue 描述可复现问题，或通过 Pull Request 提交改进。请说明预期行为、复现步骤及验证结果；行为修复应带上最小回归，提交前运行相关测试和完整构建。请勿提交私钥、密码、传输载荷或生成产物；涉及身份认证问题的报告也应移除这些敏感信息。

## 许可证

项目原创代码采用 [MIT License](LICENSE)。随附 Maven Wrapper 及第三方依赖保留各自的许可证声明。
