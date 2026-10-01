# SDK 接入与双节点演示

本指南面向 Java 17 使用者。演示只需要 JDK 17、Maven Wrapper 下载所需依赖和可写的普通文件系统，无需数据库。参考适配器目前验证于 Windows 11 / NTFS；其他操作系统、网络文件系统及机器断电持久性尚无验收结论。性能研究结果见[共同管线复评](experiments/pipeline-v1/README.md)及保留的[首次实验](experiments/feedback-v1/README.md)：完整及简化反馈策略均未达到预定收益。

## 从源码构建

在源码根目录执行，确保 `JAVA_HOME` 指向 JDK 17，并让 `java` 使用同一 JDK：

```powershell
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
.\mvnw.cmd clean install
.\mvnw.cmd -pl examples dependency:copy-dependencies "-DincludeScope=test"
```

第一次需要网络下载 Maven 和依赖。Linux/macOS 对应 `./mvnw`，Java classpath 分隔符由 `;` 改为 `:`；这些平台命令尚未实测。`test` 范围的依赖只用于生成开发证书；正常 SDK 运行不需要测试类或证书构造依赖。

## 准备开发身份

示例配置使用 `source-a`、`target-a` 和 `demo` 路由。以下命令为 PowerShell，在源码根目录执行。每次演示生成新密码，两个终端需使用相同环境值；不要把值写进配置或日志。

```powershell
$env:LCP_KEYSTORE_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:LCP_TRUSTSTORE_PASSWORD = $env:LCP_KEYSTORE_PASSWORD
java -cp "examples/target/test-classes;examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.DevelopmentCredentials secrets
```

生成器位于测试源码中，不进入发布 JAR。它创建新的 `secrets` 目录，已有目录直接拒绝，不覆盖密钥。输出为两个 PKCS12 身份库、CA 信任库、两组 X25519 PKCS8 私钥及 SPKI 公钥；CA 私钥不落盘。TLS 叶证书在生成后一小时到期，仅用于 localhost 演示，包含正确的节点 URI SAN、主机名和用途。文件继承父目录权限，演示目录应仅对运行者开放。`.gitignore` 排除 `secrets`，但它不是访问控制。

证书过期后，在新的演示目录中重新生成全套身份并使用新的输出根与控制记录。已有传输绑定旧 TLS SPKI，不能通过替换证书继续旧任务。正式部署应由受信任的证书与密钥管理流程分别向两端提供身份，按[认证边界](security.md)配置路由授权，不使用此生成器。

## 启动和获取输出

在同一终端后台启动目标，避免另一个终端缺少密码环境变量：

```powershell
New-Item -ItemType Directory -Force run | Out-Null
$targetArgs = @('-Dsun.net.httpserver.maxReqTime=660', '-Dsun.net.httpserver.maxRspTime=660', '-Djdk.httpserver.maxConnections=16', '-cp', 'examples/target/classes;examples/target/dependency/*', 'io.github.aullchen.lcp.examples.TransferNode', 'target', 'examples/config/target.properties')
$target = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList $targetArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput run/target.log -RedirectStandardError run/target-error.log
Get-Content run/target.log
```

若尚未出现监听日志，稍后再次运行 `Get-Content run/target.log`。等到日志出现 `Listening port=9443` 后运行源。若目标退出，先检查 `run/target-error.log`，不要把源端超时当成目标未写入的证明。

```powershell
java -cp "examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.TransferNode source examples/config/source.properties
```

默认发送 8,388,609 字节的确定性生成流，使用 FIXED、ZSTD 3、4 MiB 块、窗口 1。源输出 `transferId`、实际窗口，最终输出 `COMPLETED handleId=... bytes=8388609`。`handleId` 是协议标识，不是路径。FileSink 的实际文件为 `output/<transferId>/payload.bin`；SDK 的任务 ID 来自 `OpenRequest.transferId()`。只有目标持久状态为 COMPLETED 才能交付输出，单次块 ACK 不代表整个文件完成。

再次运行同一源命令，会从 `run/source-control.cbor` 查询并返回原完成结果，不创建另一份输出。目标重启应使用原配置、身份和输出根。源重启保留原控制记录：未完成任务必须确认旧任务为 CANCELLED 或 FAILED 后，才以新 ID 从输入起点重发；不从旧偏移恢复。具体未知状态处理见[传输恢复](transfer.md)。

停止后台演示目标可使用 `Stop-Process -Id $target.Id`；这属于进程终止，不承诺优雅排空。SDK 宿主应按下面的关闭约定管理资源。完成输出仍保留；重启后由持久记录恢复事实。

## 配置与输入

配置原件为 [source.properties](../examples/config/source.properties) 和 [target.properties](../examples/config/target.properties)。路径相对进程启动目录，数值是字节或毫秒整数，未知键拒绝。密码仅来自 `LCP_KEYSTORE_PASSWORD` 和 `LCP_TRUSTSTORE_PASSWORD`。

| 配置 | 含义与约束 |
| --- | --- |
| `nodeId`、`peerNodeId`、`routeId` | 两端节点互相对应，路由相同；证书 URI SAN 与节点匹配 |
| `tlsKeyStore`、`tlsTrustStore` | PKCS12 身份与信任库 |
| `hpkePrivateKey`、`hpkeKeyId` | 本方 X25519 私钥及授权标识 |
| `peerHpkePublicKey`、`peerHpkeKeyId` | 对端已授权 SPKI 公钥及标识，不从请求自动注册 |
| `peerUrl` / `listenHost`、`listenPort` | 源 HTTPS 地址 / 目标监听；示例固定 localhost:9443 |
| `controlRecord` / `outputRoot` | 源控制文件 / 目标专用 Sink 根；不得共用其他业务目录 |
| `inputFile` 或 `generatorBytes` | 源端必须且只能选择一种；生成流另有 `generatorSeed` |
| `scheduling`、`chunkBytes`、`inFlightChunks` | FIXED/FEEDBACK、256 KiB–8 MiB、窗口 1–16 |
| `policyVersion` | 仅源配置，默认 1；显式选择 2 启用可选两维反馈规则，两端须支持该版本，不自动降级 |
| `initialWindow` | 仅 FEEDBACK 接受，默认窗口上限；详见[反馈策略](feedback.md) |
| `compression`、`zstdLevel` | NONE/ZSTD；ZSTD 1–5，NONE 的实际等级固定为 1 |
| `maxFrameBytes`、`maxPlainBytes`、`maxChunks`、`maxTransferBytes` | 两端认证协商的资源上限，不能超过对方接受范围 |
| `bufferBudget`、`metadataBudget` | 应用缓冲与元数据预算；每块索引至少 52 字节，不是 RSS 上限 |
| `verificationTimeoutMillis`、`shutdownGraceMillis` | 仅目标配置，默认 600000 / 60000 毫秒 |

发送文件时，用 `inputFile=./input.bin` 替换 `generatorBytes`，并删除无用的 `generatorSeed`。保留源文件直至完成；源重启可能从头读取，输入不得在尝试之间被业务悄悄替换。新业务传输使用新的 `controlRecord` 路径，而不是删除旧记录冒充首次运行。目标同一根目录最多一个非终态任务。

## Java SDK 装配

项目尚未声明公共制品仓库发布。先通过 `install` 安装源码构件，宿主按需要依赖 `io.github.aullchen:lcp-transport-http:0.1.0-SNAPSHOT`、可选 `lcp-compression-zstd`，使用参考 FileSource/FileSink 时再依赖 `lcp-examples`。`lcp-api` 仅依赖 JDK；不需要数据库或 DI 框架。

完整可编译的装配示例是 [TransferNode.java](../examples/src/main/java/io/github/aullchen/lcp/examples/TransferNode.java)。源端依次创建 `HpkeKey`、显式 `PeerDirectory`、`TlsContexts`、`AuthenticatedHttpClient`、`ByteBudget` 与 `FixedTransferClient`；目标创建 `FileSink`、`TransferHttpHandler`、`AuthenticatedHttpServer`。该发送器名称沿用 FIXED，但支持两种策略。不要绕过身份绑定，也不要自行拼装未认证的 Open 响应。

已装配的发送器用法如下；`request` 是包含新 UUID、双方节点/密钥摘要、策略、上限、随机 challenge 和有效期的 `Metadata.OpenRequest`，`endpoint` 是完整的 HTTPS `/lcp-stream/v1/transfers` URI。各字段构造见完整示例。

```java
var source = new FileSource(inputPath);
var completion = sender.start(endpoint, request, source);
var result = completion.toCompletableFuture().get();
// 只有此处成功返回 VerifiedResult 才可向业务报告完成。
System.out.println(result.handleId() + " " + result.totalPlainBytes());
```

`start` 提交成功后接管 Source，正常结束、失败或取消均只关闭一次；排队时取消不读取 Source。Future 取消仅停止本地发送，不代表目标已持久化 CANCELLED；仍须通过恢复接口确认远端状态。异步提交被执行器拒绝时，调用方仍负责关闭 Source；宿主应捕获该同步失败并关闭输入。宿主持久恢复还须使用带 OpenObserver 的重载，在首次读取前保存接受事实；仅调用上面片段不提供跨进程恢复。示例的 `SourceControl` 在 Open 前保存意图，在接纳回调保存事实，重启通过 `recover` 查询，详见[生命周期接口](transfer.md)。

自定义 `TransferSource` 实现 `read(ByteBuffer)` 和 `close()`：短读/零读取不是 EOF，只有 -1 表示结束；不能保留调用方缓冲，关闭必须在实现所声明的期限内解除阻塞读取。`GeneratorSource` 支持已知长度的生成数据，发送器也能处理未知长度的有限 Source。自定义 Sink 必须履行持久 receipt、幂等、范围与恢复契约，不能以内存成功代替持久 ACK。

宿主在传输结束后关闭 sender，再关闭 HTTP 客户端；目标停止接收请求后关闭 handler 和 Sink。`close(Duration)`/关闭宽限超时不能证明消费者已退出，仍运行的 I/O 保有许可和根锁。保留运行时，待工作退出后再次关闭；不能立即复用或清理这些资源。原生压缩调用只在入口/出口检查中断。

## 故障处理与清理

| 现象 | 处理 |
| --- | --- |
| TLS 节点、主机名、用途或 CA 不符 | 检查证书及显式授权；不使用 trust-all 或关闭主机名验证 |
| BUSY / 请求超时 | 发送器有界退避并对账；超时不代表未提交，不手工改写块 |
| UNKNOWN_COMMIT / 状态查询失败 | 保留控制记录、目标输出和身份；恢复可查询性后核对持久事实，不自动当作成功或重用 ID |
| RECOVERY_REQUIRED / CRC 损坏 | 停止写入并保留证据；没有自动修复或强制恢复开关 |
| 配额、预算或索引上限不足 | 根据输入和块大小调整下一次新任务的两端配置；不修改已接纳任务契约 |
| 根目录或源控制记录被锁定 | 检查是否仍有持有者及在途 I/O；不删除锁文件绕过互斥 |
| 取消完成 | CANCELLED 仍保留载荷，取消不是删除接口 |

清理前停止并确认相关进程及 I/O 已退出，核对具体 transferId 的持久终态，复制并验证要保留的完成输出。随后由运维按保留策略归档或删除该任务的**整个 UUID 目录**，不能只删 `payload.bin` 或 `receipts.log`。清理完成后，仍引用该任务的源控制记录不再能恢复；应一起归档，并为新任务使用新控制路径。未知/活动状态不得按完成任务清理。项目没有自动保留期、管理删除 API 或业务导入事务。

## 支持矩阵

| 能力 | 当前范围 |
| --- | --- |
| 运行时 / 文件系统 | Java 17；实测 Windows 11 / NTFS；不支持其他进程修改 Sink 根 |
| 安全 | TLS 1.3 mTLS、固定 HPKE Auth 套件、节点/路由/密钥显式授权 |
| 数据与策略 | 有限 File/Generator/自定义 Source；NONE/ZSTD；FIXED/FEEDBACK |
| 并发 | 单任务有界在途槽位，按完成顺序回收补位；窗口 1–16，受协商上限与字节预算约束 |
| 恢复 | 存活源分页对账重试；源重启确认旧终态后从头新传；目标 VERIFYING 重启恢复 |
| 尚未交付 | 数据库适配、业务导入、UI、多任务调度、发布自动化 |
| 性能结论 | 五策略评测和简化复测完成，未证实预定研究收益 |

测试入口、逐项证据与未覆盖范围见[实现状态](development-progress.md)。
