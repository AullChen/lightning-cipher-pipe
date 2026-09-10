# lcp-stream/1 编码向量（C02）

`reference.py` 是独立 Python 3 标准库参考实现；不引用 Java 编码器。执行 `python protocol/vectors/reference.py` 可重新生成固定向量，日常 Maven 测试直接读取已提交文件，不要求 Python。

- `golden.properties`：Policy、Limits、OpenRequest、Accepted、OpenResponse、binding、HPKE info、Chunk/Finish AAD、Cancel、空流/单字节 `00`/三块不等长 Finish 的 CBOR 十六进制及 SHA-256。
- `open.json` 与 `open-reordered.json`：不同键顺序/空白，必须得到相同 Open 规范字节。
- 两个完整 frame 仅验证前缀和布局；enc/ciphertext 是明确的合成字节，**不是 HPKE 密码向量，也不能成功解密**。RFC 9180 正式密码向量留给 C04。
- 三块数据为 `00`、`0102`、`030405`，offset 为 0/1/3，最终长度为 6。参考脚本用递归 RFC 6962 分树计算根；C02 仅验证 Finish 承载与 hash，Java Merkle frontier 另以 `merkle.properties` 的 0/1/3/5/7/8/13/65 块独立参考根验证。

人工结构核验：Limits 5 项、Policy 11 项、Open 15 项、Accepted 8 项、Response 3 项、Chunk AAD 8 项、Finish AAD 4 项、Finish 7 项；domain 计入数组项数。帧头 `4+1+1+4+2+4=16` 字节，UUID 为网络序16字节，摘要32字节。

测试内独立负向输入及预期：

| 输入 | 预期 |
| --- | --- |
| `1800`、`190018`、`1a00000100`、`1b0000000000010000` | INVALID_MESSAGE：非最短整数 |
| `1b8000000000000000`、`20` | INVALID_MESSAGE：超出 U / 负数 |
| `9fff`、`a0`、`f6`、`c000`、`f90000` | INVALID_MESSAGE：无限数组 / Map / null / tag / float |
| 错 domain/数组项数/UUID 长度、逐字节截断、合法项后追加 `00` | INVALID_MESSAGE |
| JSON 重复或未知或缺失键、null、错误类型、非规范 UUID/base64url/十进制 | INVALID_MESSAGE |
| 控制 JSON >64KiB、AAD >4096、Finish 明文 >1024、frame 超协商上限 | LIMIT_EXCEEDED |
| u32 长度 `ffffffff`、长度不一致、超输出配额 | 在读取大正文之前拒绝 |

跨字段身份认证、Open 协商/期限/重放、持久化和实际 HPKE 不属于编码器的成功声明。
