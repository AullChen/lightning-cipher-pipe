# 独立 ZSTD 块与启动示例

`lcp-compression-zstd` 固定使用 zstd-jni 1.5.7-16。将 `ZstdCompression` 传给发送器与目标 handler 即启用 ZSTD；不注入时仍可独立使用内置 NONE。Open 按源端 offers 顺序选择共同支持的编码，整个传输保持该编码。ZSTD 等级为 1–5，无字典、跨块历史或原生线程池。

每块只压缩一次，`PreparedChunk` 保留不可变压缩字节；后续封装使用相同描述符与压缩内容。Finish 不压缩。双方按 compressBound 加 4160 字节完整帧开销检查协商结果，并在读取前预留整块生命周期空间。ZSTD 在已有显式缓冲峰值外另预留 32 MiB 工作区，固定 windowLog=23、hashLog=20、chainLog=20、workers=0。此预算不代表 JVM RSS。

接收端在 HPKE 验证后解压。只接受一个标准 ZSTD frame，检查内容长度、实际输出、最多 8 MiB 的窗口和输出，拒绝拼接帧、skippable frame、非零字典 ID 和尾随字节。没有声明内容长度的合法帧仍受认证 AAD 的输出上限约束；不以固定压缩比拒绝高重复输入。

原生调用前后检查中断和截止时间，原生调用期间不承诺 Java 中断能立即终止工作。输入、输出、窗口、等级及并发槽位共同限制一次调用的工作量；调用退出前不能释放其所有权或预算。

实现依据：[Zstandard 帧格式](https://github.com/facebook/zstd/blob/v1.5.7/doc/zstd_compression_format.md)、[zstd-jni](https://github.com/luben/zstd-jni/tree/v1.5.7-16)。

## 两个进程启动

先准备各节点的 PKCS12 TLS 身份库、信任库、X25519 PKCS8 私钥及对端 SPKI 公钥。身份库中的证书须具备匹配节点的 URI SAN、对应的 clientAuth/serverAuth 用途，服务端 DNS SAN 必须匹配连接主机名。使用各自的 `LCP_KEYSTORE_PASSWORD` 与 `LCP_TRUSTSTORE_PASSWORD` 环境变量提供密码；配置文件不包含密码。

参考配置为 `examples/config/source.properties` 和 `target.properties`。路径相对启动目录，未知键直接拒绝。支持 FileSource 或 GeneratorSource，二者使用同一异步发送入口。当前启动器为 FIXED、窗口 1，输出实际窗口和 transferId，完成时输出 handleId 与字节数。

构建运行类路径：

```sh
./mvnw install
./mvnw -pl examples dependency:copy-dependencies -DincludeScope=runtime
```

以下为 Windows 启动命令；其他平台将 classpath 分隔符 `;` 换为 `:`，Wrapper 使用 `./mvnw`。先启动目标，再启动源：

```text
java -Dsun.net.httpserver.maxReqTime=660 -Dsun.net.httpserver.maxRspTime=660 -Djdk.httpserver.maxConnections=16 -cp "examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.TransferNode target examples/config/target.properties
java -cp "examples/target/classes;examples/target/dependency/*" io.github.aullchen.lcp.examples.TransferNode source examples/config/source.properties
```

输出保存在配置的 Sink 根目录下，以 transferId 隔离。测试使用临时生成的证书与密钥，在两个真实 JVM 中启动相同入口并比对输出；示例不内置可部署的私钥或 trust-all 模式。
