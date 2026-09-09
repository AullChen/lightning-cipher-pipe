# 构建基线

- Java 17 / Maven 3.9.9；官方 Maven Wrapper 3.3.4 only-script，分发包先验证官方 SHA-512，再固定 SHA-256。
- 元数据使用专用严格 CBOR 编码器；JSON 使用 Jackson Core **2.22.2** 流式 API，无 databind / Web 框架。
- JUnit **5.14.4** 仅测试依赖；api 无任何外部依赖。
- HPKE / ZSTD 在 C04 / C07 实际接入时验证 API 并锁定版本，当前不引入无用模块或依赖。
- Windows 11 / Microsoft OpenJDK 17.0.7 验证通过；其他平台未实测。

C01 初选 Jackson 2.20.1 / JUnit 5.13.4；C02 首次使用时复核安全公告，更新为以上版本。Jackson 上游公告 GHSA-r7wm-3cxj-wff9 列出的修复版本包括 2.21.4，现用 2.22.2；本实现另在解析前限制 JSON 总字节数，并限制字段、数字、字符串和嵌套。JUnit 使用仍受支持的 5.14 分支。未宣称进行过完整 CVE 数据库扫描或依赖零漏洞。

依据：

- [Wrapper 配置](https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html)
- [Jackson 安全公告](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-r7wm-3cxj-wff9)
- [Jackson 版本和 Apache-2.0 许可](https://central.sonatype.com/artifact/com.fasterxml.jackson.core/jackson-core/2.22.2)
- [JUnit 支持策略](https://github.com/junit-team/junit-framework/security) / [5.14.4 发布说明](https://docs.junit.org/5.14.4/release-notes)（EPL-2.0）

## 本机 Maven

按所有者要求，已清除系统 Maven 的旧实习私服 profile / server 认证配置；原始配置仅在本机备份。用户级 `~/.m2/settings.xml` 保留 `D:/Maven/repository` 缓存，使用 `https://maven.aliyun.com/repository/public`，`mirrorOf=central`。系统 Maven 与 Wrapper 的 effective-settings 均确认生效。公共镜像不需要账户密码；没有覆盖所有第三方仓库，也不关闭默认 HTTP blocker。

项目不携带个人镜像设置。国内开发者可按[阿里云说明](https://developer.aliyun.com/mirror/maven)在用户级设置镜像，国外开发者可使用默认 Maven Central。镜像同步异常时可在用户设置暂时禁用该 mirror；Maven 不把多个 mirror 自动当作故障转移列表，见[官方镜像指南](https://maven.apache.org/guides/mini/guide-mirror-settings.html)。

项目自身许可尚待所有者确定；当前不擅自授予开源许可或添加 LICENSE。
