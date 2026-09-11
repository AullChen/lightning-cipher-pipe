# 固定策略传输

当前参考实现支持 FIXED、NONE、窗口 1。每个 Sink 根目录只允许一个非终态任务，HTTP 入场使用非等待式许可；忙碌时返回 BUSY，避免网络工作线程排队等待磁盘锁。

## API 与装配

目标将 `FileSink`、本方 `HpkeKey`、`PeerDirectory`、路由/节点、`Limits`、`ByteBudget`、`Clock` 和验证截止时间传给 `TransferHttpHandler`，再将 handler 注册到 `AuthenticatedHttpServer`。TLS 上下文使用 `TlsContexts` 和显式身份/信任库创建，详见[认证边界](security.md)。

源端使用 `AuthenticatedHttpClient` 和 `FixedTransferClient`。`start(URI, OpenRequest, TransferSource)` 返回 `CompletionStage<VerifiedResult>`；也提供阻塞式 `transfer`。URI 必须是 HTTPS 的 `/lcp-stream/v1/transfers`。发送器读取未知长度输入，依次分块、摘要、HPKE 封装并核对目标 receipt，直到 EOF 后生成 Finish。

被接受的调用持有输入源，成功或失败都会关闭一次。异步提交遭执行器拒绝时，输入仍由调用方持有。传输结束后关闭发送器；HTTP 客户端、服务端和 Sink 的生命周期由调用方管理。当前异步入口不提供任务取消或自动续传。

## 端点

| 方法与路径（相对 `/lcp-stream/v1/transfers`） | 行为 |
| --- | --- |
| `POST` 集合路径（无末尾斜杠） | 新 Open 持久接纳后 201；相同请求重放返回原响应 200 |
| `PUT /{id}/chunks/{index}` | 认证和范围检查后提交；返回持久 receipt |
| `GET /{id}/receipts?from=0&limit=256` | 按索引范围分页，稀疏空页不代表结束 |
| `POST /{id}/finish` | 冻结写入、保存清单、重读验证并返回完成状态 |
| `GET /{id}` | 查询持久状态、计数和可空结果 |
| `POST /{id}/cancel` | 保存取消命令；幂等重放不会删除输出 |

控制正文为 `application/json`，上限 64 KiB。Chunk/Finish 使用 `application/lcp-frame`。请求必须有精确 Content-Length；拒绝 chunked、Content-Encoding 和 multipart。帧先验证固定前缀与小 AAD，再分配密文缓冲。JSON 中 unsigned 数值使用十进制字符串；可空字段明确输出 null。

## 持久完成与资源边界

目标 receipt 只在 payload 和日志 force 后返回。相同描述符重放返回 ALREADY_APPLIED，不重复追加日志。活动任务收到认证的冲突块会进入 FAILED；完成态冲突不会修改结果或输出。

Finish 在 receipt 数量、偏移连续性与总长度吻合后进入 VERIFYING。引擎以 64 KiB 缓冲重读持久数据，逐块重算 SHA-256 与 Merkle 根，并检查最后一字节后的 EOF；仅一致时持久记录 COMPLETED。相同 Finish 重放返回既有结果，不再次验证；不同命令或清单返回 COMMAND_CONFLICT。重读失败不会产生完成结果。

帧处理和源端单块生命周期预留 `8 × maxFrameBytes + 2 × maxPlainBytes + 65536` 字节，覆盖显式载荷、帧与密码处理副本和验证缓冲；控制消息预留 256 KiB。FileSink 的索引预算单独配置。这些是应用缓冲上限，不是 JVM RSS 承诺；TLS、线程栈和运行时仍有固定开销。

服务端先完成持久验证再响应 Finish。客户端响应截止时间、服务端请求/响应上限应覆盖预期验证耗时。超时不能证明写入未发生，当前发送器直接报告失败，不自动重试。持久状态和 receipt 查询可供后续对账；自动重试、源控制记录、VERIFYING 后台恢复、进程退出与取消竞争属于后续生命周期工作。
