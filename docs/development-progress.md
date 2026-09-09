# 开发进度

验证环境：2026-09-09，Windows 11 amd64，Microsoft OpenJDK 17.0.7，Maven 3.9.9。
Git 作者由全局配置继承：AullChen / 1437168038@qq.com；无仓库级用户覆盖。

| 计划 | 状态 | 提交 | 验证 | 下一步 |
| --- | --- | --- | --- | --- |
| 初始化 | DONE | 85cc111 | `.local-docs/` 已忽略且未跟踪 | 不计入功能提交 |
| C01 | DONE | 4dad449 | Wrapper clean verify PASS；api/core dependency:tree PASS | 构建基线完成 |
| C02 | DONE | 本提交（`git log -1`，hash 在下次正常更新记录） | 81 tests，0 failures/errors/skipped；独立向量、编码与有界 frame PASS | C03 输入源、按序分块、Merkle |

## 实际验证

命令从项目根运行，JAVA_HOME 指向 JDK 17：

- `.\mvnw.cmd -B -ntp clean verify`：PASS，最终依赖版本下 11.764s；FrameCodecTest 16 项、MetadataCodecTest 65 项。
- `.\mvnw.cmd -B -ntp test`：最终向量换行固定后的快速回归 PASS，81 项。
- `.\mvnw.cmd -B -ntp -pl modules/api -am test`：PASS，2.314s；api 没有为凑数量新增测试，公共值对象随协议测试覆盖。
- `.\mvnw.cmd -B -ntp -pl modules/core -am dependency:tree`：PASS，19.728s（镜像缓存首次核验）；api 无外部依赖，core 运行依赖仅 api 与 jackson-core 2.22.2，JUnit 5.14.4 仅 test scope。
- `jdeps --print-module-deps modules/api/target/lcp-api-0.1.0-SNAPSHOT.jar`：仅 `java.base`。
- `python protocol/vectors/reference.py`：连续两次生成的所有向量文件 SHA-256 一致；输出固定 LF。
- JAVA_HOME 指向本机 JDK 8 执行 Wrapper `validate`：预期失败，Enforcer 明确拒绝 `[17,18)` 范围外 JDK。
- 系统 `mvn` 与 Wrapper 分别执行 `org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-settings`：均为阿里云 HTTPS 镜像 / 原 D 盘缓存，无旧 nexus profile。
- `git check-ignore .local-docs/开发计划.md` 命中；`git ls-files .local-docs` 为空；作者来源为用户 `.gitconfig`。

C01 首次构建 56.181s、依赖树 42.276s，包含依赖下载，且当时没有源码/占位测试。C02 的依赖更新原因及本机镜像配置见 [构建基线](build-baseline.md)。

## 验收边界

- V01 PASS：规范编码、非法类型/长度/最短整数、尾随项、前缀 u32 大值、AAD 超限、短读、分配大正文之前拒绝；独立合法向量精确一致。
- V03 PASS：实际依赖树和 api 独立构建通过，无数据库/Redis/Spring/Web 框架。
- V06 仅编码部分 PASS：OpenRequest/Accepted/OpenResponse/binding 和 Finish 编码；重放、过期、冲突、持久保存 NOT_RUN，V06 整体未完成。
- V02、V04–V05、V07–V23 NOT_RUN；T01/T02 整体未完成。没有将合成帧当 HPKE 验证，没有网络服务、持久 ACK、恢复或研究收益声明。

公共对象仅加入 C02 编码需要的结构，Sink 生命周期对象与适配器留给后续提交。JSON 当前覆盖 Open 及嵌套结构；控制状态/回执端点投影在实际端点实现时加入。Open 的当前时间、授权、协商 compressBound/预算检查属于后续接纳逻辑；编码器不会创建或更改传输事实。

本次未改变 `.local-docs` 设计材料。尚未确定项目开源许可证，不擅自添加许可授权。下一次从 C03 开始，不提前开展 C04 网络/密码实现。
