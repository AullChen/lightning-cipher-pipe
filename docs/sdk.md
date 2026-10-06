# SDK 接入与双节点演示

本指南面向 Java 17 使用者。演示只需要 JDK 17、Maven Wrapper 下载所需依赖和可写的普通文件系统，无需数据库。参考环境为 Windows 11 / NTFS。研究结果集中在[实验索引](experiments/README.md)，平台与能力扩展见[后续计划](roadmap.md)。

## 从源码构建

在源码根目录执行，确保 `JAVA_HOME` 指向 JDK 17，并让 `java` 使用同一 JDK：

```powershell
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
.\mvnw.cmd clean install
.\mvnw.cmd -pl examples dependency:copy-dependencies "-DincludeScope=test"
```

第一次需要网络下载 Maven 和依赖。Linux/macOS 对应 `./mvnw`，Java classpath 分隔符由 `;` 改为 `:`；平台验证安排见[后续计划](roadmap.md)。`test` 范围的依赖只用于生成开发证书；正常 SDK 运行不需要测试类或证书构造依赖。

## 准备开发身份

示例配置使用 `source-a`、`target-a` 和 `demo` 路由。以下命令为 PowerShell，在源码根目录执行。每次演示生成新密码，两个终端需使用相同环境值；密码通过环境变量传递。

```powershell
$env:LCP_KEYSTORE_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:LCP_TRUSTSTORE_PASSWORD = $env:LCP_KEYSTORE_PASSWORD
java -cp "examples/target/test-classes;examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.DevelopmentCredentials secrets
```

生成器作为测试工具随源码提供。它创建新的 `secrets` 目录，已有目录直接拒绝，不覆盖密钥。输出为两个 PKCS12 身份库、CA 信任库、两组 X25519 PKCS8 私钥及 SPKI 公钥；CA 私钥不落盘。TLS 叶证书在生成后一小时到期，仅用于 localhost 演示，包含正确的节点 URI SAN、主机名和用途。文件继承父目录权限，演示目录应仅对运行者开放。`.gitignore` 排除 `secrets`，目录访问权限另由操作系统管理。

证书过期后，在新的演示目录中重新生成全套身份并使用新的输出根与控制记录。续写身份须同时满足原 TLS SPKI 绑定与证书有效期要求；演示身份到期后按上述步骤建立新任务。正式部署应由受信任的证书与密钥管理流程分别向两端提供身份，按[认证边界](security.md)配置路由授权，演示生成器用于本地联调。

## 启动和获取输出

在同一终端后台启动目标，避免另一个终端缺少密码环境变量：

```powershell
New-Item -ItemType Directory -Force run | Out-Null
$targetArgs = @('-Dsun.net.httpserver.maxReqTime=660', '-Dsun.net.httpserver.maxRspTime=660', '-Djdk.httpserver.maxConnections=16', '-cp', 'examples/target/classes;examples/target/dependency/*', 'io.github.aullchen.lcp.examples.TransferNode', 'target', 'examples/config/target.properties')
$target = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList $targetArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput run/target.log -RedirectStandardError run/target-error.log
Get-Content run/target.log
```

若监听日志仍在等待中，稍后再次运行 `Get-Content run/target.log`。等到日志出现 `Listening port=9443` 后运行源。若目标退出，先检查 `run/target-error.log`，目标提交事实通过恢复查询确认。

```powershell
java -cp "examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.TransferNode source examples/config/source.properties
```

默认发送 8,388,609 字节的确定性生成流，使用 FIXED、ZSTD 3、4 MiB 块、窗口 1。源输出 `transferId`、实际窗口，最终输出 `COMPLETED handleId=... bytes=8388609`。`handleId` 是协议标识。FileSink 的实际文件为 `output/<transferId>/payload.bin`；SDK 的任务 ID 来自 `OpenRequest.transferId()`。只有目标持久状态为 COMPLETED 才能交付输出，块 ACK 确认对应块的持久提交。

再次运行同一源命令，会从 `run/source-control.cbor` 查询并返回原完成结果，不创建另一份输出。目标重启应使用原配置、身份和输出根。源重启保留原控制记录：活动任务确认 CANCELLED 或 FAILED 后，以新 ID 从输入起点重发。具体未知状态处理见[传输恢复](transfer.md)。

停止后台演示目标可使用 `Stop-Process -Id $target.Id`；此命令直接终止进程。SDK 宿主应按下面的关闭约定管理资源。完成输出仍保留；重启后由持久记录恢复事实。

## 配置与输入

配置原件为 [source.properties](../examples/config/source.properties) 和 [target.properties](../examples/config/target.properties)。路径相对进程启动目录，数值是字节或毫秒整数，未知键拒绝。密码仅来自 `LCP_KEYSTORE_PASSWORD` 和 `LCP_TRUSTSTORE_PASSWORD`。

| 配置 | 含义与约束 |
| --- | --- |
| `nodeId`、`peerNodeId`、`routeId` | 两端节点互相对应，路由相同；证书 URI SAN 与节点匹配 |
| `tlsKeyStore`、`tlsTrustStore` | PKCS12 身份与信任库 |
| `hpkePrivateKey`、`hpkeKeyId` | 本方 X25519 私钥及授权标识 |
| `peerHpkePublicKey`、`peerHpkeKeyId` | 对端已授权 SPKI 公钥及标识，不从请求自动注册 |
| `peerUrl` / `listenHost`、`listenPort` | 源 HTTPS 地址 / 目标监听；示例固定 localhost:9443 |
| `controlRecord` / `outputRoot` | 源控制文件 / 目标专用 Sink 根；与其他业务目录独立 |
| `inputFile` 或 `generatorBytes` | 源端必须且只能选择一种；生成流另有 `generatorSeed` |
| `scheduling`、`chunkBytes`、`inFlightChunks` | FIXED/FEEDBACK、256 KiB–8 MiB、窗口 1–16 |
| `policyVersion` | 仅源配置，默认 1；显式选择 2 启用可选两维反馈规则，两端须支持并确认同一版本 |
| `initialWindow` | 仅 FEEDBACK 接受，默认窗口上限；详见[反馈策略](feedback.md) |
| `compression`、`zstdLevel` | NONE/ZSTD；ZSTD 1–5，NONE 的实际等级固定为 1 |
| `maxFrameBytes`、`maxPlainBytes`、`maxChunks`、`maxTransferBytes` | 两端认证协商的资源上限，以双方接受范围的交集为准 |
| `bufferBudget`、`metadataBudget` | 应用缓冲与元数据预算；每块索引至少 52 字节，RSS 另行观测 |
| `verificationTimeoutMillis`、`shutdownGraceMillis` | 仅目标配置，默认 600000 / 60000 毫秒 |

发送文件时，用 `inputFile=./input.bin` 替换 `generatorBytes`，并删除无用的 `generatorSeed`。保留源文件直至完成；源重启可能从头读取，各次尝试保持输入内容一致。新业务传输使用新的 `controlRecord` 路径，旧记录按归档策略保留。目标同一根目录最多一个非终态任务。

## Java SDK 装配

通过 `install` 将源码构件安装到本地 Maven 仓库，宿主按需要依赖 `io.github.aullchen:lcp-transport-http:0.1.0-SNAPSHOT`、可选 `lcp-compression-zstd`，使用参考 FileSource/FileSink 时再依赖 `lcp-examples`。`lcp-api` 仅依赖 JDK；不需要数据库或 DI 框架。

完整可编译的装配示例是 [TransferNode.java](../examples/src/main/java/io/github/aullchen/lcp/examples/TransferNode.java)。源端依次创建 `HpkeKey`、显式 `PeerDirectory`、`TlsContexts`、`AuthenticatedHttpClient`、`ByteBudget` 与 `FixedTransferClient`；目标创建 `FileSink`、`TransferHttpHandler`、`AuthenticatedHttpServer`。该发送器名称沿用 FIXED，但支持两种策略。Open 响应沿标准认证与身份绑定路径处理。

已装配的发送器用法如下；`request` 是包含新 UUID、双方节点/密钥摘要、策略、上限、随机 challenge 和有效期的 `Metadata.OpenRequest`，`endpoint` 是完整的 HTTPS `/lcp-stream/v1/transfers` URI。各字段构造见完整示例。

```java
var source = new FileSource(inputPath);
var completion = sender.start(endpoint, request, source);
var result = completion.toCompletableFuture().get();
// 只有此处成功返回 VerifiedResult 才可向业务报告完成。
System.out.println(result.handleId() + " " + result.totalPlainBytes());
```

`start` 提交成功后接管 Source，正常结束、失败或取消均只关闭一次；排队任务取消时直接关闭 Source。Future 取消停止本地发送，远端 CANCELLED 状态通过恢复接口确认。异步提交被执行器拒绝时，调用方仍负责关闭 Source；宿主应捕获该同步失败并关闭输入。宿主持久恢复还须使用带 OpenObserver 的重载，在首次读取前保存接受事实；完整的跨进程恢复装配见下述示例。示例的 `SourceControl` 在 Open 前保存意图，在接纳回调保存事实，重启通过 `recover` 查询，详见[生命周期接口](transfer.md)。

自定义 `TransferSource` 实现 `read(ByteBuffer)` 和 `close()`：短读和零读取继续消费，-1 表示结束；Source 对调用方缓冲区的访问限于当前 read 调用期间，关闭必须在实现所声明的期限内解除阻塞读取。`GeneratorSource` 支持已知长度的生成数据，发送器也能处理未知长度的有限 Source。自定义 Sink 必须履行持久 receipt、幂等、范围与恢复契约，持久 ACK 在适配器完成持久化后返回。

宿主在传输结束后关闭 sender，再关闭 HTTP 客户端；目标停止接收请求后关闭 handler 和 Sink。`close(Duration)`/关闭宽限超时后继续跟踪消费者退出状态，仍运行的 I/O 保有许可和根锁。保留运行时，待工作退出后再次关闭；资源复用与清理以实际退出为前提。原生压缩调用只在入口/出口检查中断。

## 故障处理与清理

| 现象 | 处理 |
| --- | --- |
| TLS 节点、主机名、用途或 CA 不符 | 检查证书及显式授权；保持完整信任链与主机名校验 |
| BUSY / 请求超时 | 发送器有界退避并对账；通过持久回执确认提交，保留原块描述 |
| UNKNOWN_COMMIT / 状态查询失败 | 保留控制记录、目标输出和身份；恢复可查询性后核对持久事实，成功与新任务启动以权威终态为准 |
| RECOVERY_REQUIRED / CRC 损坏 | 停止写入并保留证据；恢复处理以完整证据核查为前提 |
| 配额、预算或索引上限不足 | 根据输入和块大小调整下一次新任务的两端配置；当前任务继续遵循已接纳契约 |
| 根目录或源控制记录被锁定 | 检查是否仍有持有者及在途 I/O；待持有者释放后再使用资源 |
| 取消完成 | CANCELLED 仍保留载荷，输出清理由归档流程负责 |

清理前停止并确认相关进程及 I/O 已退出，核对具体 transferId 的持久终态，复制并验证要保留的完成输出。随后由运维按保留策略归档或删除该任务的**整个 UUID 目录**，载荷与日志作为整体处理。清理时将引用该任务的源控制记录一并归档，并为新任务使用新控制路径。活动或待核实状态先完成状态确认。保留期、管理接口与业务导入的扩展见[后续计划](roadmap.md)。

## 支持矩阵

| 能力 | 当前范围 |
| --- | --- |
| 运行时 / 文件系统 | Java 17；实测 Windows 11 / NTFS；Sink 根由当前进程独占 |
| 安全 | TLS 1.3 mTLS、固定 HPKE Auth 套件、节点/路由/密钥显式授权 |
| 数据与策略 | 有限 File/Generator/自定义 Source；NONE/ZSTD；FIXED/FEEDBACK |
| 并发 | 单任务有界在途槽位，按完成顺序回收补位；窗口 1–16，受协商上限与字节预算约束 |
| 恢复 | 存活源分页对账重试；源重启确认旧终态后从头新传；目标 VERIFYING 重启恢复 |
| 扩展路线 | 适配器验证、恢复优化与业务接入见[后续计划](roadmap.md) |
| 研究证据 | 五策略评测、策略简化与容量变化实验，完整结果见[实验索引](experiments/README.md) |

测试命令见[项目首页](../README.md#验证与复现)，研究结果见[实验索引](experiments/README.md)。
