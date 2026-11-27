# Boot 4.2 采纳，ES 9 客户端等待服务端 9.x

## 上下文

2026-11-27 在基线 `main` @ `2220dfa` 上执行了「Boot 4.2 / ES 9 兼容性 spike」
（契约 `plans/boot42-es9-spike-accept.md`）。两个 Dependabot PR 的旧结论已不能直接引用：
`#108`（Boot 4.1.2 → 4.2.0）唯一失败是 `static-gate` 读 README 的版本行；
`#81`（`elasticsearch-java` 8.14.3 → 9.5.6）则在编译期报出 22 个 main 错误与 16 个 test 错误。

四格实验（实验分支 `codex/boot42-es9-lab`，永不合并）：

| 格 | 依赖 | 编译 | 测试 | 活体 |
| --- | --- | --- | --- | --- |
| A | Boot 4.1.2 + ES 8.14.3 | OK | 642（基线既有） | health/chat/admin 全通 |
| B | Boot 4.2.0 + ES 8.14.3 | OK | 642/642 | health/chat/admin 全通 |
| C | Boot 4.1.2 + ES 9.5.6 | 修 22+16 个错误后 OK | 642/642 | health/chat/admin 全通 |
| D | Boot 4.2.0 + ES 9.5.6 | OK | 642/642 | health/chat/admin 全通 |

详细矩阵、错误清单、依赖树与官方来源见 `docs/ops/boot42-es9-spike.md`。

## 决策

1. **Spring Boot 4.2.0：ADOPT。** 代码零改动、642/642 全绿；`static-gate` 的
   `FAIL 静态：受支持 Boot 版本（G1）` 只是 README 版本行与 `pom.xml` 不同步，
   补 README 两处（badge 与依赖支持窗口）即 `PASS=69 FAIL=0`。生产依赖本切片不动，
   由后续 `boot42-upgrade` 切片执行（`pom.xml` + README，零类改动）。
2. **`elasticsearch-java` 9.5.6：DEFER。** 机械迁移可做且已在本机证明（4 个 main 类、
   3 个测试类，C/D 均 642/642），但官方兼容矩阵只声明 9.x 客户端对 9.x 服务端；
   8.14 服务端 + 9.x 客户端属于「向后兼容、不保证」，且本 spike 没有 ES 8.14/9.x
   真服务端联调。**触发线：生产或 staging ES 服务端升到 9.x（目标 9.5.x）后再迁客户端。**
3. **C 格暴露的 BOM 偏斜必须在实现切片修正。** Boot 4.1.2 的 BOM 把
   `elasticsearch-rest5-client` 管理到 9.4.8，而显式覆盖的 `elasticsearch-java` 是 9.5.6；
   D 格（Boot 4.2.0）两者同为 9.5.6。实现时应同时钉死两者，或直接以 Boot 4.2 BOM 为准。
4. **合并切片只含文档。** `git diff 2220dfa..HEAD -- pom.xml src/` 必须为空；实验分支
   `codex/boot42-es9-lab` 只推送、不合并、不开 PR。
5. **Dependabot 处置只出建议。** `#108` 建议改基后随 `boot42-upgrade` 合并；
   `#81` 建议保留并标记为「等待 ES 服务端 9.x」，不关闭。两者都需用户授权后才能操作。

## 否决项

- **把 Boot 4.2 判为 BLOCKED。** 失败点是版本门闩的文档同步，不是代码不兼容；
  把门闩改弱或跳过测试换绿属于硬性禁令，不在选项内。
- **现在就合并 ES 9 客户端。** 既无 8.14 服务端的官方支持声明，也没有真 ES 联调；
  memory/mock 的 health/chat/admin 不经过 ES 检索路径，不能证明兼容。
- **保留 HC4 `RestClient` 走 ES 9。** ES 9 client 不再传递 `org.elasticsearch.client.RestClient`
  与 Apache HttpClient 4；唯一受支持的传输是 `Rest5Client` + HttpClient 5。
- **只升 `elasticsearch-java` 不升 transport。** C 格的 9.5.6/9.4.8 偏斜虽能跑测试，
  但属于未登记的组合，不作为采纳依据。

## 后果

- Boot 4.2 的落地成本约 **2 个文件、0 个类、0.5 人日**；合并前必须重跑
  SCA/SBOM 与 `scripts/production_gate.sh --fast`，确认没有新增 HIGH/CRITICAL。
- ES 9 的迁移成本约 **7 个文件（1 pom + 4 main + 3 test）、4 个 main 类、3 个测试类、
  3–5 人日**（含 ES 9 staging 的索引/别名/检索/健康联调）。
- 本机读数只代表 memory/mock：启动 12.6–13.6s、常驻 RSS 304–324MB，
  Boot 4.2 与 ES 9 的差异均在单次运行噪声内，不能外推为容量结论。
- `management.health.elasticsearch.enabled` 在 Boot 4.2 属性表中仍存在；本项目已置 `false`，
  自有 `esHealthIndicator`（组件键 `es`）才是有效探针。ES 9 切换后该键预计成为空操作，
  可在后续清理切片中移除，不影响当前运行。
