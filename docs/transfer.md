# 固定策略传输

当前参考实现支持 FIXED、NONE/ZSTD、窗口 1–16。每个 Sink 根目录只允许一个非终态任务，HTTP 入场使用非等待式许可；忙碌时返回 BUSY，限制在途请求数量。

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

发送端按源顺序分块与构建 Merkle frontier，以有界批次并发发送，物理窗口不超过策略窗口、协商槽位和预算可容纳的完整块数。每块在读取前取得覆盖压缩、封装和发送的峰值许可，所有消费者退出后才释放；不建立随输入长度增长的载荷队列。

目标端原子预留索引与字节范围，互不重叠的 payload 可并发写入，receipt 日志串行追加和刷盘。在途相同描述符返回 BUSY；冲突索引或重叠范围拒绝写入。Finish/Cancel 在存在在途写入时返回 BUSY，会话关闭等待写入退出。

目标 receipt 只在 payload 和日志 force 后返回。相同描述符重放返回 ALREADY_APPLIED，不重复追加日志。活动任务收到认证的冲突块会进入 FAILED；完成态冲突不会修改结果或输出。

Finish 在 receipt 数量、偏移连续性与总长度吻合后进入 VERIFYING。引擎以 64 KiB 缓冲重读持久数据，逐块重算 SHA-256 与 Merkle 根，并检查最后一字节后的 EOF；仅一致时持久记录 COMPLETED。相同 Finish 重放返回既有结果，不再次验证；不同命令或清单返回 COMMAND_CONFLICT。重读失败不会产生完成结果。

帧处理和源端单块生命周期预留 `8 × maxFrameBytes + 2 × maxPlainBytes + 65536` 字节，覆盖显式载荷、帧与密码处理副本和验证缓冲；ZSTD 另计原生工作区，详见[压缩与启动示例](compression.md)。控制消息预留 256 KiB。FileSink 的索引和源端 receipt 历史分别受 `metadataBudget` 约束，启动时要求 `maxChunks × 52 ≤ metadataBudget`；源端以紧凑数组保存全部已分配描述符与确认标志，不保留历史载荷。发送器的重载构造器接受元数据预算，默认 128 MiB。这些是应用缓冲上限，不是 JVM RSS 承诺；TLS、线程栈和运行时仍有固定开销。

## 对账与重试

网络超时或响应丢失不能证明写入未发生。发送器停止新读取，等待本轮工作线程退出，然后查询状态及全部已分配索引的 receipt；每页最多 256 个索引，稀疏空页仍继续核对。历史 ACK 对应的索引、偏移、长度和摘要必须仍然存在且一致。缺失或变化时停止本次发送并报告 UNKNOWN_COMMIT，不重读源、不生成替代块，也不发布成功。

查到当前未确认块的 receipt 后直接确认；仅缺失的块重发。每块保留原压缩材料和预算，每次重新建立 HPKE 上下文，最多 4 次发送（含首次与 BUSY）。三次退避基数为 500 ms、1 s、2 s，乘以 0.5–1.5 的抖动；尊重 BUSY 的 Retry-After，等待上限 10 s 且不超过剩余 TTL。状态和每页查询也各自最多尝试 4 次。对账失败不会继续发送新块。

认证、格式、完整性、范围冲突和 UNKNOWN_COMMIT 不自动重试。发送耗尽后再次对账仍有缺块时，发送器尝试一次取消并报告未知结果；取消未确认时不得复用 transferId。完成态响应丢失后只接受与原 Finish 清单完全一致的持久 COMPLETED 结果；VERIFYING 可在有界查询内等待，不能当作成功。

服务端先完成持久验证再响应 Finish。客户端响应截止时间、服务端请求/响应上限应覆盖预期验证耗时。源控制记录、VERIFYING 后台恢复、进程退出后的完整恢复和取消竞争属于后续生命周期工作。
