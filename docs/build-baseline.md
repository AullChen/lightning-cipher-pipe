# 构建基线

- Java 17 / Maven 3.9.9，官方 Maven Wrapper 3.3.4 only-script。
- 元数据采用专用严格 CBOR 编码器；JSON 使用 Jackson Core 2.20.1 流式 API，不引入 databind 或 Web 框架。
- JUnit 5.13.4 仅测试依赖；api 无任何外部依赖。
- HPKE / ZSTD 在 C04 / C07 实际接入时验证 API 并锁定版本，今天不预建空模块或引入无用依赖。
- Wrapper: https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html
- Jackson artifact/license: https://central.sonatype.com/artifact/com.fasterxml.jackson.core/jackson-core/2.20.1 （Apache-2.0）
- JUnit: https://docs.junit.org/5.13.4/release-notes/ （EPL-2.0）

版本固定不等于无漏洞保证；升级前需检查上游安全公告及依赖树，本次不宣称已完成 CVE 全量扫描。项目许可尚待所有者确定；不擅自授予开源许可。
