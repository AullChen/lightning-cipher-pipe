# 开发进度

验证环境：2026-09-09，Windows 11 amd64，Microsoft OpenJDK 17.0.7，Maven 3.9.9。
Git 作者继承全局 AullChen / 1437168038@qq.com，无仓库级用户覆盖。

| 计划 | 状态 | 提交 | 验证 | 下一步 |
| --- | --- | --- | --- | --- |
| 初始化 | DONE | 85cc111 | `.local-docs/` 已忽略 | 不计入功能提交 |
| C01 | DONE | 本提交，hash 在下次更新记录 | `mvnw.cmd -B -ntp clean verify` PASS（56.181s，首次依赖下载，无占位测试）；`mvnw.cmd -B -ntp -pl modules/core -am dependency:tree` PASS（42.276s） | C02 协议编码 |

V03 PASS：api 无外部依赖；core 运行依赖仅 api 与 jackson-core 2.20.1，JUnit 仅 test scope。T01 尚未完整完成，V01/V02 及其余验收 NOT_RUN。

Wrapper 分发包先核验 Maven Central SHA-512，再固定 SHA-256。沙箱账户与目录所有者不同，Git 操作用命令级 `-c safe.directory=F:/OpenSource/lightning-cipher-pipe`，不修改全局安全目录列表。构建固定使用 JDK 17；本机 PATH 原始 java 为 8，需设置 JAVA_HOME。
