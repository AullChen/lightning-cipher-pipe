# 认证与传输边界

## 认证组件

`lcp-security` 固定使用 RFC 9180 Auth 模式：X25519 / HKDF-SHA256 / ChaCha20-Poly1305（32/1/3）。每条 Chunk 或 Finish 创建独立 HPKE 上下文和随机封装；使用统一固定套件。

`HpkeKey` 加载 PKCS8 私钥并导出 SPKI 公钥摘要。`PeerDirectory` 按节点、keyId 和 route 显式授权；请求摘要用于核对预先授权的公钥。密钥撤销同时终止其对旧传输的封装和解封授权。

`BoundTransfer` 校验 Open 的身份、选择范围和期限，并冻结双方 TLS SPKI 与规范握手摘要。写入必须保留原 TLS SPKI 和有效 HPKE 授权；同节点在当前路由授权有效时可使用新证书查询旧状态，续写使用原绑定的写入身份。

## TLS 与 HTTP

`TlsContexts` 使用显式信任库执行 PKIX 验证，要求匹配预期节点的唯一 `urn:lcp:node:<nodeId>` URI SAN，并检查 clientAuth / serverAuth 用途及有效期。客户端另外执行 HTTPS 主机名校验。`AuthenticatedHttpServer` 强制 TLS 1.3 与客户端证书；身份和 SPKI 来自实际 TLS 会话，不接受身份 Header。

JDK HTTP 参考适配器使用固定线程数、有界队列和拒绝策略。控制响应限制在调用方指定大小以内（最多 64KiB），客户端截止时间覆盖整个响应体。服务端使用 JDK 的连接、请求和响应限制，以下属性必须在 **JVM 启动时**配置为正数：

- `jdk.httpserver.maxConnections`：连接数上限。
- `sun.net.httpserver.maxReqTime`：请求阶段上限，单位秒。
- `sun.net.httpserver.maxRspTime`：响应阶段上限，单位秒。

这些属性由 JDK 在服务初始化时读取并缓存，配置在 JVM 启动阶段完成。该参考适配器面向独立进程；嵌入共享 JVM 时必须协调全局限制。服务端超时关闭连接，业务处理和磁盘写入的退出由生命周期协调器确认。客户端超时后脱离应用持有的请求帧，只向 HTTP 传输层交付分段副本；源端仍须通过对账判断持久结果，目标写入状态由持久状态查询确认。

本实现使用 Java 17 JSSE；应用数据在 TLS 握手完成后发送。HTTP 已装配固定 NONE/ZSTD 的 Open/Chunk/Finish、状态、receipt 和 Cancel 端点。正文分配前取得字节许可，持有到认证、持久提交或失败退出。协议端点与缓冲边界见[固定策略传输](transfer.md)。

## 验证覆盖

自动化测试使用 RFC 固定套件向量、项目规范 AAD/frame，以及临时生成的证书进行双向 TLS 握手。覆盖错误 CA、无客户端证书、节点或主机名不符、SAN 缺失/重复、证书过期/用途错误、路由拒绝、撤销密钥、SPKI 轮换、正文与响应超时、有界队列及响应体上限。

自动化测试的证书密钥位于测试进程或隔离临时目录中；开发演示生成器将短期密钥写入指定的新目录，详见 [SDK 指南](sdk.md)。仓库中的 RFC 私钥字节是公开标准向量，用于标准一致性测试，部署身份由独立密钥管理流程生成。

连接认证、持久 ACK 与完成结果分别具有明确语义：目标在持久写入后确认 receipt，在输出重读和最终承诺一致后发布 COMPLETED。当前已支持持久接纳、分页对账、有界重试、目标校验恢复及源控制记录恢复；业务导入由宿主衔接，扩展方向见[后续计划](roadmap.md)。

参考：[RFC 9180](https://www.rfc-editor.org/rfc/rfc9180)、[Bouncy Castle HPKE API](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/hpke/HPKE.html)、[JDK HTTP 服务端配置](https://github.com/openjdk/jdk17u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerConfig.java)。
