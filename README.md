# 热点数据缓存预热与缓存一致性保障系统 v3

![CI](https://github.com/Wowyeye/cache-preheat-system/actions/workflows/ci.yml/badge.svg)

> Spring Boot 3.2 + Redis 7 + MyBatis + MySQL 8 + Vue3 + ECharts
> —— 带自动热点识别、可视化监控、订单状态机与多级防并发问题的**工程级**缓存系统

v3 相对 v2 完成四层工程化改造（15 项：3 🔴 阻断级 / 4 🟠 架构级 / 4 🟡 数据业务级 / 4 🔵 工程运维级）；
v3.1 又在**运行中的实例**上逐条实测复核，修掉了 15 项"声明与实现不符 / 真实并发缺陷"。

> **本 README 的写作原则**：写进来的每一条都标了它是"实测验证"还是"仅代码实现"。
> 未能实测的（如 docker compose 一键启动）如实标注为未验证，不再用"通过"掩盖。

---

## 一、v2 → v3 → v3.1 改进对照（面试核心）

### 1.1 v3：四层工程化修复（15 项）

| 层级 | v2 缺陷 | v3 修复 |
|------|---------|---------|
| 🔴 阻断级 | 启动 SQL 无版本化，改表结构靠手改 | Flyway 版本化迁移 `V1__init.sql` + `V2__seed.sql`，启动自动建表 |
| 🔴 阻断级 | 多实例击穿防护用 JVM synchronized（每实例一把锁） | Redisson 分布式锁（RLock + 看门狗自动续期），全集群一把锁 |
| 🔴 阻断级 | Redis 一挂商品查询直接 500 | SafeRedisTemplate 降级执行：Redis 弱依赖，挂了自动直查 DB |
| 🔴 阻断级 | 配置真实密码写死在仓库 | 全部环境变量外置（`.env` 已 gitignore），仓库与 git 历史零密码（已核查） |
| 🟠 架构级 | KEYS 全库扫描阻塞 Redis 主线程 | SCAN 游标迭代（count=500 分批） |
| 🟠 架构级 | 热点榜单只增不减、无 TTL、无容量上限 | ZREMRANGEBYRANK 裁剪保头部 + 7 天整体 TTL 兜底 |
| 🟠 架构级 | 垃圾 ID 可被顶进排行榜（穿透污染） | v3 起：只对"确认存在"的商品记热度（v3.1 修正为正确实现，见 §1.2） |
| 🟠 架构级 | 待支付订单永久占用库存 | 超时自动取消（状态机式 UPDATE 防并发双回补）+ 定时扫描 |
| 🟡 数据级 | 订单无状态机校验，非法流转 | 8 态状态机 + 越权校验（403）+ 非法流转（409） |
| 🟡 数据级 | 防超卖仅靠 Java 层判断 | SQL 原子扣减 `WHERE stock>=qty` + 库存不足回滚 |
| 🟡 数据级 | 登录无限流 | 固定窗口限流（用户名+IP 双维度，60 秒 5 次失败，Redis 故障放行） |
| 🟡 数据级 | member 被序列化成带引号 `"999"` | 读取侧统一去引号解析，兼容 v2 遗留数据 |
| 🔵 工程级 | 零测试 | 66 单测（Mockito）+ Redis 层集成测试（Testcontainers，无 Docker 自动 skip） |
| 🔵 工程级 | 手动部署 | Dockerfile 多阶段 + docker-compose 编排 + healthcheck 依赖就绪 |
| 🔵 工程级 | 前端 CDN 外链 | 6 个资源本地化到 `frontend/vendor/`，离线可用（实测 0 处外链） |

### 1.2 v3.1：实测复核后修掉的 15 项（本轮）

| # | 问题（实测/代码确证） | v3.1 修复 |
|---|----------------------|-----------|
| 1 | **自动预热统计从不落库**：`recordStats()` 只被手动预热调用，`autoPreheat()` 不调用 → 监控页"自动预热轮次"恒为 0、时间恒"未执行"（实测 Redis 中 `cache:hotspot:rank:stats` 键不存在，而日志每 60s 都在打"自动预热完成"） | `autoPreheat()` 补记 stats |
| 2 | **限流阈值实际只有 3 次**：`overLimit()` 检查里也 INCR，失败再 INCR → 每次失败计数 +2，"60 秒 5 次"实测第 4 次就 429 | 检查改为只读 GET；`recordLoginFailure` 才计数；并加 TTL 自愈（EXPIRE 失败不再造成永久 429） |
| 3 | **热点榜防污染只防"重复访问同一个不存在的 ID"**：新 ID 首次访问照样进榜（实测 999999 以 score 1.0 出现在 `/api/hotspot/rank`），攻击者每次换新 ID 就能灌榜 | 改为"只在确认商品存在后记热度"（缓存命中或回源命中）；顺带省掉热路径一次多余 GET |
| 4 | 榜单出现非数字脏成员时 `Long.parseLong` 抛异常 → 整个 `/api/hotspot/rank` 500、定时预热整轮作废 | 脏成员跳过并从榜单移除（自愈），记 warn |
| 5 | **并发双回补（TOCTOU）**：`cancel/approveRefund/approveReturn` 走"先 checkStatus 再无 koşullu updateStatus"，两个请求都能通过校验 → 两次 `increaseStock` → 库存虚增 | 全部改条件 UPDATE（`updateStatusIf`），影响 0 行抛 409；**并从 Mapper 接口/XML 中删除无条件 `updateStatus`**，防止被重新用上 |
| 6 | **超时取消的"逐单隔离"不成立**：整批一个事务，catch 后不会回滚单笔 → "订单已取消、库存只回补一半"会随批量提交 | 改为每单独立事务（TransactionTemplate + REQUIRES_NEW），单笔失败只回滚自己 |
| 7 | **延迟双删时序错位**：第一次删除发生在事务提交之前，第二次是固定 1.5s 与提交时刻无关 → 并发读可在窗口内读旧值回写，脏缓存活到 TTL | 失效动作后置到 `afterCommit`；第二次删除改为延迟调度（不再用工作线程 sleep，消除队列满 CallerRuns 阻塞业务线程）；首删失败也计入指标 |
| 8 | `DELETE /api/product/{不存在的id}` 返回 200"删除成功"；`PUT` 同理 | 受影响行数为 0 → 404 |
| 9 | POST 打到只读接口、`?page=abc` 类型错误都被兜底成 **500** | 补 405 / 400 专门处理（方法不支持、参数类型错、缺参、请求体不可读） |
| 10 | 未登录访问管理接口返回 **403**（与写请求的 401 不一致） | `UserContext.requireAdmin()`：未登录 401、非管理员 403 |
| 11 | **Redis 类型白名单形同虚设**：`allowIfBaseType(Object.class)` 与子类型白名单是 OR 关系，缓存值基类型恰是 Object → 实测 `java.io.File` 可正常反序列化 | 去掉该规则、补齐 `java.math.`（BigDecimal），白名单真正生效；新增 `RedisSerializationTest` 固化（放行真实类型 + 拒绝白名单外类型） |
| 12 | `view_count` 永不更新（`incrementViewCount` 无调用点），而启动预热按 `view_count DESC` 排序 → 预热永远是同一批种子商品 | 回源命中（真的读到 DB）时累加浏览量 |
| 13 | 无 `.dockerignore`：`.env`（真实密码）、`target`、`logs`、`.git` 全进构建上下文 | 新增 `.dockerignore` |
| 14 | 集成测试"跑过"是假象：无 Docker 时报告 `Tests run: 0, Skipped: 0`（连 skip 都不计，等于静默消失）；且它不加载 Spring、不含 MySQL/Flyway，验的还是测试自建的序列化配置 | 改为 `@Testcontainers(disabledWithoutDocker = true)`（无 Docker 时**如实 skip**）+ 直接使用生产 `RedisConfig` 的序列化器；类名改为 `RedisLayerIT` 并写明覆盖边界 |
| 15 | README 6 处声明与实测相反（同源托管、自动预热轮次、5 次限流、防污染、member 引号、白名单） | 全部按实测改写；本文档新增"实测验证"标注 |

### 1.3 v3.2：接上 Docker 后补掉的两条"未验证" + 一处环境兼容

| # | 事项 | 处理 |
|---|------|------|
| 1 | `docker compose up -d --build` 从未跑通（旧记录：本机无 docker CLI、镜像拉取断流） | 本机装 Docker Desktop 4.90 后**实测跑通**：三容器齐起 + 容器内 Flyway 从零建库 + 应用 health=UP（详见 §9.1-B） |
| 2 | "前端同源托管 8083"只写在文档里，从未验证过（本地 jar 一直是 404） | 在 Docker 部署上**实测 8 个页面 + 6 个 vendor 资源全部 200**；同时保留"本地 jar 不含前端"的说明 |
| 3 | 集成测试从未真正执行（无 Docker 时报告 `Tests run: 0`） | Testcontainers **实测真跑**：Ryuk + `redis:7-alpine` 起容器，`RedisLayerIT` 4/4 通过 |
| 4 | 镜像内 Maven 构建在国内必卡（`repo.maven.apache.org` 拉不动） | `Dockerfile` 增加 `MVNW_REPOURL` / `MAVEN_MIRROR_URL` 两个构建参数（默认官方源），compose 从 `.env` 读取；实测走阿里云构建成功 |
| 5 | Testcontainers 1.19.7 与 Docker Engine 29.x 不兼容（`/info` 返回 400 → "Could not find a valid Docker environment"） | 升到 **1.21.4**；并移除未使用的 `com.redis:testcontainers-redis`（避免与核心包版本混用）；README 记录 Windows 下需指定 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` |
| 6 | **Redis 故障期无熔断**：实测 `docker compose stop redis` 后单次商品查询要 **28.8 秒**才降级返回（一次请求串行撞 5 次 Redis 调用，各自等超时） | 新增 `RedisCircuitBreaker`：连续失败达阈值即熔断，期间 `SafeRedisTemplate` / `DistributedLock` / `TokenService` / `RateLimiter` 全部**不发起** Redis 请求直接走降级；同时把 Redisson 的 `timeout/connectTimeout/retryAttempts` 收紧。实测：故障期**首请求 0.56s、后续 24ms**（修复前 28.8s），恢复后探测成功自动关闭熔断 |
| 7 | **"Redis 无密码可留空"其实是坏的**：`spring.data.redis.password` 解析成空串时，Redisson 会真发一条 `AUTH `，被未启用密码的 Redis 以 `ERR AUTH` 拒绝 → 应用启动失败（DbLayerIT 实测复现） | `RedisConfig` 显式装配 `RedissonClient`：**空串/空白一律按"无密码"处理**，并在这里统一设置 Redisson 超时/重试；DbLayerIT 用的就是一个无密码 Redis 容器，等于给这条路径加了回归测试 |
| 8 | 数据库层零集成测试：Mapper/XML/Flyway/事务边界全靠手工实测 | 新增 `DbLayerIT`（Testcontainers **真实 MySQL 8 + Redis 7** + 完整 Spring 上下文 + Flyway）：7 项覆盖迁移版本、原子扣减、条件状态更新、下单/取消全链路、**并发双取消只回补一次**、Cache Aside、穿透空标记 |

### 1.4 v3.3：限流与鉴权边界加固

| # | 问题（实测可复现） | 处理 |
|---|-------------------|------|
| 1 | **伪造 `X-Forwarded-For` 即可绕过 IP 维度限流**：旧实现无条件取 XFF 第一段当客户端 IP，而 XFF 是请求头，谁都能写——每次请求换个值就等于换个 IP | 新增 `ClientIpResolver`：默认**完全不信任** XFF（取 socket 地址）；只有直连方命中 `app.security.trusted-proxies` 时才解析，且取 XFF 链上**最右侧的不可信地址**。实测：6 次不同用户名 + 6 个不同伪造 XFF → 第 6 次被 IP 维度 429 拦住（日志 `[限流] IP 172.18.0.1 ...`），窗口过期后自动恢复 |
| 2 | `/api/product/cache/stats` 无鉴权，游客可读内部运维计数（缓存 key 数、降级次数、双删失败次数） | 拆成两个接口：`/cache/summary`（公开，只给命中率与耗时等演示指标）+ `/cache/stats`（ADMIN，完整内部计数）；前端监控大盘/耗时对比页按角色自动选接口，游客看只读视图 |
| 3 | `/actuator/metrics`、`/actuator/info` 匿名可读（信息泄露面） | Actuator 只暴露 `health`（compose/K8s 探针用），`show-details: never`；实测 metrics/info 均 404 |
| 4 | 前端把管理员按钮暴露给所有人（点了才报 401/403） | 监控大盘的运维区、热点页"立即预热"、耗时对比的"运行实验"按角色隐藏/禁用，并给出原因提示 |
| 5 | Spring Boot 3.2.5 已过 OSS 支持期，且随附 spring-web 6.1.6 / tomcat-embed 10.1.20 带有已修 CVE | 升到 **3.2.12**（3.2 线最后一版）：Spring Framework **6.1.15**、tomcat-embed-core **10.1.33**；同小版本升级，90 单测 + 11 集成测试全绿，容器内实测启动正常 |
| 6 | **没有 CI**：测试、构建、部署全靠手工跑，改完没人替你守 | 新增 `.github/workflows/ci.yml`：① `mvn verify`（GitHub runner 自带 Docker，Testcontainers 集成测试**真跑不跳过**）；② docker compose 一键部署冒烟——等健康后断言前端页面 200、`/cache/summary` 200、`/cache/stats` 401、`/actuator/metrics` 404 |
| 7 | **Web 层契约只用手工 curl 验过一次**（上一轮修的 401/403/404/405/400/429 重构就可能回退） | 新增 `WebLayerContractTest`（MockMvc，**15 项**）把权限矩阵与错误码钉成断言；顺带把 `@MapperScan` 从启动类挪到 `config/MyBatisConfig`——挂在启动类上会让 `@WebMvcTest` 切片也去创建 MyBatis Mapper 而缺 `SqlSessionFactory` |
| 8 | **定时任务没有多实例语义**：`@Scheduled` 在每个实例都触发，预热被重复执行、`autoPreheatRounds` 被重复累加 | `DistributedLock.tryExecuteOnce` 租约抢占：预热 **fail-closed**（下轮再来）、订单超时扫描 **fail-open**（宁可多扫不能漏扫）。**实测两个实例并行 140 秒只 +2 轮**（修复前 +5），日志可见 `已由其他实例执行，跳过` |

> 关于第 8 条的关键教训：最初实现是"任务跑完就 unlock"，实测两个实例的 60 秒定时器**相位不同**——
> A 跑完立刻放锁，B 十秒后触发又抢到这把空锁，一轮被跑了两次（130 秒 +5 轮）。
> 改成"**持有租约到本轮结束、不主动释放**"（ShedLock 的思路）后才真正互斥。

### 1.5 v3.4：可观测性与一致性再升级

| # | 事项 | 处理 |
|---|------|------|
| 1 | 接口文档靠翻 README，联调/答辩成本高 | 接入 **springdoc OpenAPI**：`/swagger-ui.html` 可点着调，`/v3/api-docs` 是规范 JSON；30 个接口全部带中文 summary，ADMIN 接口标注 `@SecurityRequirement`；实测 **28 个路径 / 6 个 Tag** |
| 2 | 指标只有自研 Redis 统计（非标准协议，Prometheus/Grafana 抓不到，也没有分位数） | 接入 **Micrometer → `/actuator/prometheus`**：`cache_access_total{result=hit\|miss\|null_hit\|degrade\|lock_fallback}`、`cache_path_seconds{path=cache\|db}`（自带 p50/p95/p99）、`cache_circuit_open`、`cache_delete_failures{phase}`、`cache_evict_retry*`；与 JVM/HTTP/连接池指标并列，可直接拼 Grafana 看板 |
| 3 | 下单无频率限制（唯一有"写放大"的入口：扣库存 + 写订单表） | 复用 `RateLimiter` 增加用户维度限流：**60 秒 10 单**，Redis 故障放行。实测同一用户连下 12 单 → `200×10 + 429×2` |
| 4 | **失效失败只记日志 + 计数**，脏数据只能等 TTL（1800s）收敛——一致性上最大的一块缺口 | 新增 `EvictRetryQueue`：失败即入队，指数退避重试（2s→4s→8s→16s→32s→60s，最多 6 次），**熔断期间不消耗重试次数**，成功/耗尽/丢弃都有指标；`/api/product/cache/stats` 增加 `evictRetryPending/Success/Exhausted` |

> 一致性这条的**边界**要说清楚：
> ① 重试队列是**进程内**的（失效失败往往正是 Redis 不可用，此时往 Redis 里塞任务同样会失败；写路径发生在哪个实例，就由哪个实例记着）。
>    实例被 `kill -9` 会丢队列，此时仍有 TTL 兜底；跨实例共享队列需要外部存储，属于下一步（配合 binlog/CDC）。
> ② 另一个实测发现：**Redis 宕机时鉴权是 fail-closed，所有需要登录的接口都不可用**（读接口仍能降级），
>    所以"失效失败"最现实的触发场景是 Redis **抖动/瞬时故障**，而不是长时间宕机。
>    这正是不该把登录态存在 Redis 的理由 —— 生产上更倾向无状态 JWT（见 §9.2）。

---

## 二、技术栈

| 层次 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2.12（JDK 17 编译 / 21 运行；内嵌 Tomcat 10.1.33、Spring Framework 6.1.15）|
| 缓存 | Redis 7（Redisson 3.27.2 提供连接工厂）+ Redisson 分布式锁 |
| ORM | MyBatis 3.0.3 + MySQL 8.0 + Druid 连接池 |
| 迁移 | Flyway（数据库版本化）|
| 接口文档 | springdoc OpenAPI 2.3.0（Swagger UI：`/swagger-ui.html`）|
| 指标 | Micrometer + Prometheus registry（`/actuator/prometheus`）|
| 前端 | Vue3 + Element Plus + ECharts（本地化，无需构建）|
| 测试 | JUnit 5 + Mockito + Awaitility + Testcontainers |
| CI | GitHub Actions（`mvn verify` + compose 部署冒烟）|
| 部署 | Dockerfile（多阶段 / 非 root） + docker-compose |

---

## 三、项目结构

```
cache-preheat-system-v3/
├── pom.xml                          # 依赖（Redisson/Flyway/Testcontainers）
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── Dockerfile                       # 多阶段 + 非 root + healthcheck（构建期把前端复制进 static）
├── docker-compose.yml               # MySQL8 + Redis7 + 应用
├── .dockerignore                    # v3.1 新增：.env/target/logs/.git 不进构建上下文
├── .env.example                     # 环境变量样例（真实值写 .env，已 gitignore）
├── .gitignore                       # 忽略 .env/.temp/logs/target
├── src/main/java/com/jyu/cache/
│   ├── config/                      # RedisConfig(白名单)/拦截器/CORS/CacheProperties/调度器
│   ├── common/                      # Result/DistributedLock/RateLimiter/SafeRedisTemplate/UserContext
│   ├── controller/                  # Product/Order/OrderAdmin/Category/HotSpot/Auth
│   ├── service/                     # ProductServiceImpl/OrderService/HotSpotService/DelayDeleteService/CacheStatsService
│   ├── mapper/ entity/              # 4 个 Mapper + 5 个实体
│   ├── scheduler/                   # OrderTimeoutScheduler（超时取消）
│   └── runner/                      # CachePreheatRunner（启动预热）
├── src/main/resources/
│   ├── application.yml              # 全环境变量占位（无真实密码）
│   ├── mapper/*.xml                 # SQL（原子扣减 / 条件式状态机更新）
│   └── db/migration/                # Flyway：V1__init.sql + V2__seed.sql
├── src/test/java/                   # 66 单测 + RedisLayerIT
└── frontend/                        # 8 页面 + vendor 本地化资源 + js/css
```

---

## 四、快速启动

### 方式一：Docker Compose（推荐演示；⚠️ 见 §九 已知限制）

```bash
cp .env.example .env        # 填入 MYSQL_ROOT_PASSWORD 等
docker compose up -d --build
# 前端页面：http://localhost:8083/dashboard.html   ← 由镜像内的 static 目录托管
# 健康检查：http://localhost:8083/actuator/health
```

宿主端口：MySQL 13306、Redis 16379、应用 8083（避开本机服务）。

**国内网络先配 Maven 换源**（否则镜像构建会卡在 `wget: Failed to fetch ...apache-maven-3.9.6-bin`）：

```bash
# 写进 .env（compose 自动读取；.env 已 gitignore 不入库）
MVNW_REPOURL=https://maven.aliyun.com/repository/public
MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
```
这两个变量走 `Dockerfile` 的 build args（默认空 = 官方源，保持可移植），
`MVNW_REPOURL` 是 Maven Wrapper 官方支持的分发包仓库前缀，`MAVEN_MIRROR_URL` 会写成容器内 `~/.m2/settings.xml` 的 mirror。

> ✅ 本机实测（Windows 11 + Docker Desktop 4.90 / Engine 29.7.2）：`docker compose up -d --build` 三容器全部起来、
> MySQL/Redis 到 `healthy`、容器内 Flyway 从零建库（`Successfully applied 2 migrations`）、
> 应用 `health=UP`、**8 个前端页面与 6 个 vendor 资源全部 200**（这条只有 Docker 部署才成立）。

### 方式二：本地开发（Maven）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:MYSQL_PASSWORD = "你的MySQL密码"
$env:REDIS_PASSWORD = "你的Redis密码"   # 本机 Redis 无密码就留空（v3.2 已修：空串会被正确当成"无密码"，
                                        #   旧版会把空串当密码发 AUTH 导致启动失败）
$env:REDIS_DATABASE = "1"               # 避开 v2 使用的 database 0
.\mvnw.cmd spring-boot:run
```

**前端怎么访问（重要，与旧文档不同）**：
- 本地用 Maven 打包/运行时，jar 里**不含**前端资源（`src/main/resources/static` 只在 Docker 构建阶段由 `Dockerfile` 生成），
  所以 `http://127.0.0.1:8083/dashboard.html` 会 **404**（已实测）。
- 本地请直接用浏览器打开 `frontend/dashboard.html`：页面在 `file://` 协议下会自动把 `API_BASE` 兜底到 `http://127.0.0.1:8083`，CORS 默认 `*` 放行。
- 只有 Docker 部署时才是"应用同源托管前端"（同源相对路径，无端口硬编码）。

**打包注意**：应用正在从 `target/*.jar` 运行时，`mvnw clean package` 会因 Windows 文件占用失败
（`spring-boot:repackage` 无法重命名被占用的 jar）。**先停应用再打包**，或打包到别的目录。

---

## 五、核心机制

### 1. Cache Aside 读策略（防击穿）
```
查商品：缓存命中(instanceof Product) -> 记热度 -> 返回
        命中空值标记 -> 返回 null（不记热度）
        缓存脏值(非 Product) -> 删掉当未命中处理（不让强转异常变 500）
        未命中 -> Redisson 分布式锁(3s) -> 双重检查 -> 查DB -> 写缓存(TTL随机) -> 记热度+浏览量
        拿锁失败/Redis故障 -> 降级直查 DB（弱依赖，不 500）
```

### 2. 延迟双删写策略（缓存一致性）
```
更新商品 / 库存扣减 / 回补：写DB -> 事务提交后 -> 删缓存(第1次) -> 延迟1.5s -> 删缓存(第2次)
```
- **第一次删除在 `afterCommit` 执行**（v3.1）：提交前删缓存会让并发读把旧值写回，第二次删除又和提交时刻无关。
- 第二次删除由 `ScheduledExecutorService` 延迟调度，等待期不占工作线程，也不会反压业务线程。
- 两次删除的失败都计数（`/api/product/cache/stats` 的 `firstDeleteFailures`/`secondDeleteFailures`），
  失败不重试，最终一致性由缓存 TTL 兜底。

### 3. 三大经典防护
| 防护 | 机制 |
|------|------|
| 穿透 | 不存在的 ID 写空值标记（TTL 60s）|
| 击穿 | Redisson 分布式锁 + 锁内双重检查 |
| 雪崩 | TTL + 0~300s 随机抖动 |

### 4. 自动热点识别
```
商品查询（仅当确认商品存在）-> ZINCRBY 热度+1
每60秒 -> ZREVRANGE 取 TopN -> 查库 -> 写缓存 -> ZREMRANGEBYRANK 裁剪 + 刷新 TTL + 记录本轮统计
防污染：不存在的 ID 从源头不进榜（只对确认存在的商品记热度）；删除商品同步 ZREM；
        榜单出现脏成员则跳过并移除，不影响整轮预热
```

### 5. 订单状态机（8 态）
```
PENDING_PAYMENT --支付--> PAID --确认收货--> COMPLETED
     |--取消--> CANCELLED（回补库存）    |--退款--> REFUNDING -> REFUNDED（回补）
     |（超时自动取消，回补）              |--退货--> RETURNING -> RETURNED（回补）
```
- **所有流转都是条件 UPDATE**（`WHERE status = 期望状态`），拿不到流转权（影响 0 行）→ 409，
  并发下绝不会双回补（v3.1 修复：旧实现只有超时路径是条件更新，用户侧是"先查后无条件改"）。
- 超时扫描每单一个独立事务，单笔失败只回滚自己。

### 6. 防超卖
`UPDATE product SET stock = stock - n WHERE id = ? AND stock >= n`，0 行受影响抛 409。
（实测：库存 5000 的商品下单 999999 件 → HTTP 409，库存不变）

### 7. 限流（登录）
固定窗口 60 秒 **5 次失败**（只读检查 + 失败记账，v3.1 修正后阈值语义与文档一致），
用户名 + IP 双维度，命中即 429；Redis 故障放行；计数键缺 TTL 时自愈补设。

**客户端 IP 怎么来的（v3.3）**：默认取 socket 地址，**不信任** `X-Forwarded-For`——
否则伪造该头即可让每次请求都变成"新 IP"，IP 维度形同虚设（实测可复现）。
部署在 Nginx/网关后面时，把网关地址填进 `APP_TRUSTED_PROXIES`（支持 `10.0.0.0/8` 这样的 CIDR），
此时才会解析 XFF，并取链上**最右侧的不可信地址**作为真实客户端。

### 8. Redis 本地熔断（v3.2）
```
连续失败达阈值（默认 1）-> 熔断 5s（cache.breaker-*/CACHE_BREAKER_* 可配）
熔断期间：读路径不发起任何 Redis 请求，直接查 DB；写路径的缓存失效记账跳过
冷却后：半开，只放行 1 个探测请求 —— 成功则关闭熔断，失败则立刻重新打开
```
- 覆盖所有 Redis 触点：`SafeRedisTemplate`（读/写/SCAN/删除）、`DistributedLock`（Redisson 也要等连接重试，是原来 28.8s 里的大头）、`TokenService`（登录态 fail-closed 但要快速失败）、`RateLimiter`（fail-open）。
- 实测效果：Redis 挂掉后**首请求 0.56s、后续 ~24ms**（无熔断时每次请求都要 28.8s）；恢复后自动关闭熔断。
- 取舍：阈值默认 1（宁可激进切 DB，也不让用户等超时），因为降级路径永远是"直查 DB"这个正确结果；抖动敏感场景可调大到 3。

---

## 六、测试矩阵（含真实覆盖边界）

| 层级 | 命令 | 覆盖 | 状态 |
|------|------|------|------|
| 单元测试 | `.\mvnw.cmd test` | **126 项**：状态机/条件更新防双回补/防超卖/逐单事务超时取消/分布式锁/定时任务租约互斥/限流（登录+下单）/热点防污染与裁剪/序列化白名单/延迟双删 afterCommit/SafeRedis 降级与 SCAN/熔断状态机/可信代理与 XFF 防伪造/**失效重试队列 6 项**/**Micrometer 指标 4 项**/Web 层契约 18 项 | ✅ 实测全绿 |
| Redis 层集成测试 | `.\mvnw.cmd verify` | Testcontainers 真实 `redis:7-alpine` 容器：生产序列化配置往返、空哨兵值、SCAN、ZSet 热度 | ✅ **实测真跑通过**（4/4，无 Docker 时如实 skip） |
| 数据库层集成测试 | `.\mvnw.cmd verify` | `DbLayerIT`：Testcontainers **真实 MySQL 8 + Redis 7** + 完整 Spring 上下文 + Flyway：迁移版本/原子扣减/条件状态更新/下单取消全链路/**并发双取消只回补一次**/Cache Aside/穿透标记 | ✅ **实测真跑通过**（7/7，无 Docker 时如实 skip） |
| 持续集成 | push / PR 自动触发 | `.github/workflows/ci.yml`：`mvn verify` + compose 部署冒烟 | ✅ 已接入（见仓库 Actions 徽章） |
| 构建 | `.\mvnw.cmd clean package` | 可执行 fat jar | ✅ 实测（58 MB / `BOOT-INF/lib` 89 项）；**需先停掉正在运行的实例**（Windows 文件占用） |

> **环境提示 A**：若 `mvn test` 出现 `MockitoInitializationException: Could not self-attach to current VM`，
> 是 JDK 21 + 受限环境不允许 agent 自附加，加 `-DargLine=-Djdk.attach.allowAttachSelf=true` 即可。
>
> **环境提示 B（Windows + Docker Desktop 跑 IT 必需）**：
> ① Testcontainers 1.19.7 与 Docker Engine 29.x 的 API 协商会失败（`/info` 直接 400，报"Could not find a valid Docker environment"），本项目已升到 **1.21.4**；
> ② Testcontainers 默认找 `npipe://./pipe/docker_engine`，而 Docker Desktop 4.9x 的引擎管道叫 `dockerDesktopLinuxEngine`，需要显式指定：
> ```powershell
> $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
> mvn verify "-DargLine=-Djdk.attach.allowAttachSelf=true"
> ```

---

## 七、面试要点速览

1. **为什么用 Redisson 而不是自己写 SETNX 锁？** 看门狗自动续期防死锁、可重入、集群支持。
2. **延迟双删为什么延迟 1.5s？** 等读线程把旧缓存写完再删；**更关键的是第一次删除必须等事务提交后**。
3. **SCAN vs KEYS？** KEYS 全库 O(N) 阻塞主线程；SCAN 游标增量分批。
4. **空值缓存为什么 TTL 更短（60s）？** 防穿透，同时防"误标记"长期生效。
5. **并发下怎么保证库存不被双回补？** 状态流转一律条件 UPDATE（影响行数=0 即放弃回补），而不是"先查状态再改"。
6. **热点榜怎么防垃圾 ID 灌榜？** 只有在确认商品存在（缓存命中/回源命中）时才记热度，垃圾 ID 从源头进不来。
7. **Redis 挂了系统还能用吗？** 能——SafeRedisTemplate 把 Redis 降为弱依赖，读路径降级直查 DB（单元测试覆盖；停 Redis 可现场演示）。
8. **Redis 反序列化的安全边界？** 关闭 LaissezFaire 多态放行 → 子类型前缀白名单；注意 `allowIfBaseType(Object.class)` 会让白名单失效（本项目的实测教训）。

---

## 八、演示流程（5 分钟版）

1. `docker compose up -d --build`（国内先按 §四 配好 Maven 换源与镜像源），等三容器 healthy
2. 浏览器打开 **`http://localhost:8083/login.html`**（Docker 部署下页面由应用同源托管），用 `admin/admin123` 登录
   - 若走本地模式（方式二），页面请直接打开 `frontend/login.html`
3. 商城页反复访问同一商品 → 热点排行页分数上升；监控大盘"自动预热轮次"**每 60s +1**
4. 监控大盘：命中率、缓存 vs 数据库耗时对比
5. 防超卖：库存不足时下单 → 409
6. 订单超时：下单不支付，超时（`.env` 里把 `ORDER_TIMEOUT_MINUTES` 设为 1）自动取消并回补库存
7. 断 Redis：`docker compose stop redis` → 商品查询仍可用（降级直查 DB，`degradeCount` 上升），`start` 后自动预热

---

## 九、已知限制与实测说明

### 9.1 实测记录

**A. 本地模式**（Windows + 原生 MySQL 8（3306）+ 原生 Redis 7（6379，带密码）+ JDK 21）

| 项 | 状态 | 证据 |
|----|------|------|
| 33 → 126 单测 | ✅ 通过 | `mvn test` BUILD SUCCESS，Failures/Errors/Skipped 全 0 |
| fat jar 构建 | ✅ 通过 | 58,453,755 B，`BOOT-INF/lib` 89 项 |
| 登录 / 权限矩阵 | ✅ 通过 | `admin/admin123` 登录 200；普通用户访问管理接口 403；未登录管理接口 401；无效 token 401 |
| Cache Aside 命中 | ✅ 通过 | 二次查询走缓存；统计 hit/miss、缓存 4.86ms vs DB 14.03ms |
| 穿透防护 | ✅ 通过 | 查不存在 ID：首次 miss+1，二次 hit+1（命中空值标记，未打库） |
| 防超卖 | ✅ 通过 | 库存 5000 下单 999999 件 → 409，库存不变 |
| 限流 | ✅ 通过（修正后） | 修正前实测第 4 次 429（阈值被算成 3）；修正后实测 5 次 401 后第 6 次 429；窗口过期后自动恢复 |
| **限流边界（v3.3）** | ✅ 通过 | 6 次不同用户名 + 6 个不同伪造 `X-Forwarded-For`（同一真实 IP）→ 第 6 次被 IP 维度 429 拦住，日志 `[限流] IP 172.18.0.1 ...`；证明伪造头不再能绕过 |
| **鉴权边界（v3.3）** | ✅ 通过 | 游客读 `/api/product/cache/stats` → 401；`/cache/summary` → 200 且不含内部计数；管理员读 stats → 200 含全部计数；`/actuator/metrics`、`/actuator/info` → 404 |
| 订单超时自动取消 | ✅ 通过（全链路） | 阈值设 1 分钟：下单 → 库存 5000→4999 → 调度器 60s 内取消 → 状态 `CANCELLED` + 库存回 5000 |
| 热点榜防污染 | ✅ 通过 | 不存在的 888777 查询后**不在榜**；真实商品访问正常加分（1→3） |
| 自动预热统计 | ✅ 通过 | `autoPreheatRounds` 随 60s 轮次递增（修正前恒为 0） |
| view_count | ✅ 通过 | 回源命中后 75210 → 75211 |
| 前端资源本地化 | ✅ 通过 | 8 个页面内 `https?://` 命中 0，6 个 vendor 文件齐全 |
| Redis 宕机降级 | ✅ 可用性 + 延迟都通过 | 实测 `docker compose stop redis`：商品详情**返回 200**（降级直查 DB），故障期**首请求 0.56s、后续 ~24ms**（v3.2 加熔断前是单请求 28.8s）；恢复后 23ms，日志可见"探测成功，Redis 恢复正常，关闭熔断" |

**B. Docker 模式**（Windows 11 + Docker Desktop 4.90 / Engine 29.7.2 / compose v5.5.1）

| 项 | 状态 | 证据 |
|----|------|------|
| `docker compose up -d --build` 一键启动 | ✅ **通过** | 三容器齐起；`cache-mysql`/`cache-redis` = healthy；`cache-app` health=UP |
| 容器内 Flyway 从零建库 | ✅ 通过 | `Migrating schema cache_db_v3 to version "1 - init"` → `"2 - seed"` → `Successfully applied 2 migrations` |
| **前端同源托管 8083** | ✅ **通过** | 8 个页面 200（dashboard/login/mall/products/my-orders/admin-orders/hotspot/compare）+ vendor 资源 200（本地 jar 时这些是 404） |
| 容器内 API 冒烟 | ✅ 通过 | 登录 200、商品查询命中启动预热（hit=1）、启动预热 10 条、`autoPreheatRounds` 递增 |
| Testcontainers 集成测试 | ✅ **通过** | Ryuk + `redis:7-alpine` 真实启动，`RedisLayerIT` 4/4 通过（0 跳过） |
| **数据库层集成测试** | ✅ **通过** | `DbLayerIT`：真实 `mysql:8.0` + `redis:7-alpine` 容器 + 完整 Spring 上下文，**7/7 通过**（含 Flyway 迁移版本、原子扣减、条件状态更新、并发双取消只回补一次） |
| 镜像构建换源 | ✅ 通过 | `MVNW_REPOURL` + `MAVEN_MIRROR_URL` 指向阿里云后，镜像内 Maven 构建成功 |
| **CI 首次运行** | ✅ 通过 | GitHub Actions run #1（push 到 main 触发）：`测试` Job 全绿（Testcontainers 在 runner 上**真跑**，不跳过）+ `docker compose 一键部署冒烟` Job 全绿（健康就绪 + 前端 200 + `/cache/summary` 200 + `/cache/stats` 401 + `/actuator/metrics` 404 断言全部通过） |
| **Swagger UI（v3.4）** | ✅ 通过 | `/swagger-ui.html` 200、`/v3/api-docs` 200，解析出 **28 个路径 / 6 个 Tag**，30 个接口带中文 summary |
| **Prometheus 指标（v3.4）** | ✅ 通过 | `/actuator/prometheus` 200，含 `cache_access_total`、`cache_path_seconds_count`、`cache_circuit_open`、`cache_delete_failures`、`cache_evict_retry_pending`；`/actuator/metrics` 仍为 404 |
| **下单限流（v3.4）** | ✅ 通过 | 同一用户连续下单 12 次 → `200 × 10` + `429 × 2`（60 秒 10 单） |
| 失效重试队列（v3.4） | ✅ 单元测试覆盖（6 项） | 成功出队 / 失败退避 / 熔断期不消耗次数 / 超限放弃 / 同 key 去重 / 队列满丢弃；线上指标 `cache_evict_retry*` 已就位（实测 pending=0）。**端到端触发受限**：Redis 宕机时鉴权 fail-closed，写请求进不来，故现实触发场景是 Redis 抖动（见 §9.2） |

### 9.2 已知限制（未修，属取舍或待办）

| 项 | 说明 | 建议 |
|----|------|------|
| 本地 jar 不含前端 | 同源托管只在 Docker 镜像内成立；本地 8083 访问页面 404（已实测对比） | 若要在本地也托管，把 `frontend/*` 复制进 `src/main/resources/static` 后再打包 |
| Docker 构建依赖网络 | 基础镜像 `mysql:8.0`/`temurin` 在国内可能拉不动（本项目实测用过 `docker.1ms.run` 镜像源拉取后 `docker tag` 回官方名）；Maven 换源见 §四 | 稳定网络或预先拉好镜像 + 配 `registry-mirrors` |
| X-Forwarded-For 与代理部署 | v3.3 起默认**不信任** XFF（取 socket 地址）；只有在 Nginx/网关后面才需要配置 `APP_TRUSTED_PROXIES` | 部署在反向代理后时把网关地址（支持 CIDR）填进该变量，否则所有请求会被算成同一个 IP |
| 缓存统计与 Actuator | v3.3 起：`/cache/stats` 仅管理员、`/cache/summary` 公开且只含演示指标；Actuator 只暴露 `health` + `prometheus` | 如需更多运维端点，建议接入 Spring Security 后再开放；`/actuator/prometheus` 生产上应只在内网暴露 |
| Swagger UI 公开 | `/swagger-ui.html` 与 `/v3/api-docs` 未鉴权（拦截器只管 `/api/**`），演示项目方便，生产等于把接口清单公开 | 生产可设 `springdoc.api-docs.enabled=false`，或把它们放到网关鉴权之后 |
| 下架商品仍可被读路径命中 | 读缓存/回源不校验 `status`，`status=0` 的商品仍能查到并回写缓存（仅启动预热按 `status=1` 过滤） | 若要下架即不可见，需在读路径加 status 判断并同步清理缓存 |
| 一致性：已加"失效重试 + 退避"，仍有边界 | v3.4 新增进程内重试队列（最多 6 次、退避到 60s、熔断期不计次）；**剩下**：队列不跨实例/被 kill -9 会丢、无 key 版本号、无 binlog/MQ 补偿，最终仍靠 TTL（1800s）兜底 | 下一步：key 加版本号（写时递增、读到旧版本即失效）→ 再接 binlog 订阅（Canal/Debezium）做跨实例失效广播 |
| 登录态存在 Redis → 宕机即全站需登录接口不可用 | 鉴权是 fail-closed（安全优先）：Redis 宕机时 `TokenService.verify` 返回 null → 401。实测也印证了这一点，而**读接口仍能降级直查 DB** | 生产建议改无状态 JWT（自校验签名）+ 短 TTL + 刷新令牌；这样 Redis 故障只影响缓存，不影响认证 |
| 种子弱口令 | `admin/admin123`、`user1|user2/123456` 随仓库发布 | 公开仓库前移除种子账号或强制首登改密 |
| 依赖版本 | 已升到 Spring Boot **3.2.12**（3.2 线最后一版，含 spring-web/tomcat CVE 修复）；但 3.2.x 整条线已停止 OSS 支持 | 若要继续跟进：3.3/3.4 属于小版本迁移（MyBatis-Starter、Redisson、Flyway 需同步验证）；4.x 是更大的迁移（Spring Framework 7 / 模块化），建议单独开分支做 |
| Redis 故障期首请求仍有 ~0.5s | 熔断把"每个请求都等超时"变成"只等一次"：首次失败仍要等一次连接/命令超时（已把 Redisson 调成 `timeout/connectTimeout=2s`、`retryAttempts=1`、`retryInterval=500ms`） | 若还要更低：把 `redis.timeout` 调到 500ms 级，或让熔断对"连接被拒"这类错误单独更快触发 |
| 打包与运行互斥 | 应用从 jar 运行时 `mvnw clean package` 会失败（Windows 文件占用） | 先停应用再打包；或用容器内构建 |
| 定时任务租约的取舍 | 抢占后持有租约到本轮结束（≈调度间隔 50s/60s）。实例在任务中途崩溃时，**最多会跳过一轮**（下下轮恢复），换来的是"同一轮绝不重复执行" | 需要"绝不漏跑"的任务（如对账）应改成持久化任务表 + 重试，而不是靠租约锁 |
| 部分列表接口未分页 | `GET /api/product/list`、`GET /api/admin/order/list`、`GET /api/category/list` 仍是全量返回（当前数据量小） | 数据量上来后统一改成与 `/product/page` 一致的分页接口 |
| 写接口限流只盖了登录与下单 | v3.4 已给下单加用户维度限流；其它写接口（商品增删改、订单审核）暂无 | 按需复用 `RateLimiter`（用户 + 接口维度） |

> **一句话总结**：v3 把 v2 的"架构级缺陷"补成了工程实现，v3.1 把"实现与声明"之间的差距补平了，
> v3.2 把"只有文档、没有实测证据"的两条（compose 一键部署、Testcontainers 集成测试）真正跑通，
> v3.3 收紧了限流/鉴权边界，v3.4 补上了接口文档、标准指标与失效重试。
> 当前状态是**可运行、可测试、可一键部署、可观测，且每条声明都能指出证据来源**；
> 只剩两块真正的深水区：一致性（key 版本号 → binlog 订阅）与无状态认证（JWT 替代 Redis 登录态），路径已写在 §9.2。
