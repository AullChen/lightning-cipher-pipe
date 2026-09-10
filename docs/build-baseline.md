# 构建与依赖

项目使用 Java 17、Maven 3.9.9 和 Maven Wrapper 3.3.4。Wrapper 分发包固定 SHA-256，源码使用 UTF-8。

| 依赖 | 版本 | 用途 | 许可 |
| --- | --- | --- | --- |
| Jackson Core | 2.22.2 | 有界 JSON 流式解析 | Apache-2.0 |
| JUnit Jupiter | 5.14.4 | 自动化测试 | EPL-2.0 |

CBOR 使用协议所需的确定性子集编码器。`lcp-api` 仅依赖 JDK；`lcp-core` 不依赖数据库、Redis、Spring 或 Web 框架。

依赖版本由根 POM 固定。升级应同时检查上游安全公告、许可和兼容性，并运行协议向量及相关测试。固定版本和测试通过不代表不存在未知漏洞。

参考：[Maven Wrapper](https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html)、[Jackson 安全公告](https://github.com/FasterXML/jackson-core/security/advisories)、[JUnit 发布说明](https://docs.junit.org/5.14.4/release-notes)。
