---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'd3c9e049-7fb7-4662-a324-537f315240a5'
  PropagateID: 'd3c9e049-7fb7-4662-a324-537f315240a5'
  ReservedCode1: 'a28d8f86-2211-48cd-a4ac-d6d59c808eb7'
  ReservedCode2: 'a28d8f86-2211-48cd-a4ac-d6d59c808eb7'
---

# 热点数据缓存预热与缓存一致性保障系统 v3

> Spring Boot 3.2 + Redis 7 + MyBatis + MySQL 8 + Vue3 + ECharts
> —— 带自动热点识别、可视化监控、订单状态机与多级防并发问题的**工程级**缓存系统

v3 在 v2 基础上完成**四层工程化修复**：修复 3 个🔴阻断级缺陷、7 个🟠架构级缺陷、4 个🟡数据业务级缺陷、6 个🔵工程运维级缺陷，交付**可单测、可集成测试、可容器化部署**的完整工程。

---

## 一、v2 → v3 改进对照（面试核心）

| 层级 | v2 缺陷 | v3 修复 |
|------|---------|---------|
| 🔴 阻断级 | 启动 SQL 无 Flyway 版本化，改表结构靠手改 | Flyway 版本化迁移 `V1__init.sql` + `V2__seed.sql`，启动自动建表 |
| 🔴 阻断级 | 多实例击穿防护用 JVM synchronized（每实例一把锁） | Redisson 分布式锁（RLock + 看门狗自动续期），全集群一把锁 |
| 🔴 阻断级 | Redis 一挂商品查询直接 500 | SafeRedisTemplate 降级执行：Redis 弱依赖，挂了自动直查 DB |
| 🔴 阻断级 | 配置真实密码写死在仓库 | 全部环境变量外置（.env / local-secret.txt 被 .gitignore），仓库零密码 |
| 🟠 架构级 | KEYS 全库扫描阻塞 Redis 主线程 | SCAN 游标迭代（count=500 分批） |
| 🟠 架构级 | 热点榜单只增不减、无 TTL、无容量上限 | ZREMRANGEBYRANK 裁剪保头部 + 7 天整体 TTL 兜底 |
| 🟠 架构级 | 垃圾 ID 可被恶意顶进排行榜（穿透污染） | recordAccess 先查空值标记，确认不存在不进榜 |
| 🟠 架构级 | 待支付订单永久占用库存 | 超时自动取消（状态机式 UPDATE 防并发双回补）+ 定时扫描 |
| 🟡 数据级 | 订单无状态机校验，非法流转 | 8 态状态机 + 越权校验（403）+ 非法流转（409） |
| 🟡 数据级 | 防超卖仅靠 Java 层判断 | SQL 原子扣减 `WHERE stock>=qty` + 库存不足回滚 |
| 🟡 数据级 | 登录无限流 | 固定窗口限流（用户名+IP 双维度，60 秒 5 次，Redis 故障放行） |
| 🟡 数据级 | member 被序列化成带引号 `"999"` | 统一去引号解析，兼容 v2 遗留数据 |
| 🔵 工程级 | 零测试 | 33 单测（Mockito）+ 集成测试（Testcontainers 真实 Redis/MySQL，无 Docker 自动跳过） |
| 🔵 工程级 | 手动部署 | Dockerfile 多阶段 + docker-compose 一键编排 + healthcheck 依赖就绪 |
| 🔵 工程级 | 前端 CDN 外链 | 6 个资源本地化到 `frontend/vendor/`，离线可用 |
| 🔵 工程级 | 前端存储型 XSS（用户名直接渲染） | escapeHtml() 转义 + 权限按钮渲染 |

---

## 二、技术栈

| 层次 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2.5（JDK 17 编译 / 21 运行）|
| 缓存 | Redis 7（Lettuce 客户端）+ Redisson 3.27.2（分布式锁）|
| ORM | MyBatis 3.0.3 + MySQL 8.0 + Druid 连接池 |
| 迁移 | Flyway（数据库版本化）|
| 前端 | Vue3 + Element Plus + ECharts（本地化，无需构建）|
| 测试 | JUnit 5 + Mockito + Testcontainers |
| 部署 | Dockerfile + docker-compose（MySQL8 + Redis7 + 应用）|

---

## 三、项目结构

```
cache-preheat-system-v3/
├── pom.xml                          # 依赖（Redisson/Flyway/Testcontainers）
├── mvnw / mvnw.cmd                  # Maven Wrapper（JDK21 运行）
├── Dockerfile                       # 多阶段构建 + 非 root + healthcheck
├── docker-compose.yml               # MySQL8 + Redis7 + 应用一键编排
├── .env.example                     # 环境变量样例（真实值复制为 .env，不入库）
├── .gitignore                       # 忽略 local-secret.txt/.env/logs/target
├── src/main/java/com/jyu/cache/
│   ├── CacheApplication.java        # 启动类
│   ├── config/                      # RedisConfig/拦截器/CORS/CacheProperties/SchedulerConfig
│   ├── common/                      # Result/DistributedLock/RateLimiter/SafeRedisTemplate/UserContext
│   ├── controller/                  # Product/Order/Category/HotSpot/Auth 五组 API
│   ├── service/                     # ProductServiceImpl/OrderService/HotSpotService/CacheStatsService
│   ├── mapper/                      # ProductMapper/OrderMapper/UserMapper/CategoryMapper
│   ├── entity/                      # Product/Order/OrderItem/SysUser/Category
│   ├── scheduler/                   # OrderTimeoutScheduler（超时取消）
│   └── runner/                      # CachePreheatRunner（启动预热）
├── src/main/resources/
│   ├── application.yml              # 全环境变量占位（无真实密码）
│   ├── mapper/*.xml                 # SQL（原子扣减/状态机更新）
│   └── db/migration/                # Flyway：V1__init.sql + V2__seed.sql
├── src/test/java/                   # 33 单测 + 集成测试（无 Docker 自动跳过）
└── frontend/                        # 8 页面 + vendor 本地化资源
```

---

## 四、快速启动

### 方式一：Docker Compose 一键部署（推荐演示）

```bash
# 1. 准备环境变量（真实密码写 .env，不入库）
cp .env.example .env
# 编辑 .env 填入 MYSQL_ROOT_PASSWORD

# 2. 一键启动（MySQL8 + Redis7 + 应用，Flyway 自动建表+种子数据）
docker compose up -d --build

# 3. 访问
#    前端页面：  http://localhost:8083/dashboard.html   （应用同源托管）
#    健康检查：  http://localhost:8083/actuator/health
```

> 说明：MySQL 宿主端口 13306、Redis 宿主端口 16379（避开本机服务）；应用 8083。

### 方式二：本地开发（Maven）

```powershell
# 1. 准备 JDK21（项目编译目标 17）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "C:\Program Files\Java\jdk-21\bin;$env:Path"

# 2. 配置环境变量（密码不写文件）
$env:MYSQL_PASSWORD = "你的MySQL密码"
$env:REDIS_PASSWORD = "你的Redis密码"   # 无密码可留空
$env:REDIS_DATABASE = "1"               # 避开 v2 使用的 database 0

# 3. 建库（MySQL 8 创建空库即可，Flyway 自动建表）
#    mysql -u root -p -e "CREATE DATABASE cache_db_v3 CHARACTER SET utf8mb4"

# 4. 启动
.\mvnw.cmd spring-boot:run
```

前端直接浏览器打开 `frontend/dashboard.html`（同源逻辑：部署时走 8083；file:// 打开时兜底 API_BASE=8083，CORS 默认 \* 放行）。

---

## 五、核心机制

### 1. Cache Aside 读策略（防击穿）
```
查商品：Redis 命中 -> 返回
        未命中 -> Redisson 分布式锁(3s) -> 双重检查 -> 查DB -> 写缓存(TTL随机) -> 返回
        拿锁失败/Redis故障 -> 降级直查 DB（弱依赖）
```

### 2. 延迟双删写策略（缓存一致性）
```
更新商品: 更新DB → 删缓存(第1次) → 延迟1.5s → 删缓存(第2次)
```
解决并发读写导致脏缓存；库存扣减/回补同样触发。

### 3. 三大经典防护
| 防护 | 机制 |
|------|------|
| 穿透 | 不存在的 ID 写空值标记（TTL 60s）|
| 击穿 | Redisson 分布式锁单线程回源 |
| 雪崩 | TTL + 0~300s 随机值 |

### 4. 自动热点识别（v2 创新，v3 加固）
```
访问 → ZINCRBY 热度+1
每60秒定时 → ZREVRANGE 取 TopN → 查库 → 写缓存 → ZREMRANGEBYRANK 裁剪 + 刷新 TTL
防污染：命中空值标记的 ID 不进榜；删除商品同步 ZREM
```

### 5. 订单状态机（8 态）
```
PENDING_PAYMENT --支付--> PAID --确认收货--> COMPLETED
     |--取消--> CANCELLED（回补库存）      |--退款--> REFUNDING -> REFUNDED（回补）
     |（超时自动取消，回补）                |--退货--> RETURNING -> RETURNED（回补）
```
- 状态流转靠 `updateStatusIf`（WHERE 原状态）原子抢占，防并发双回补
- 超时扫描 60s 一轮，逐单隔离异常不阻断整批

### 6. 防超卖（原子扣减）
`UPDATE product SET stock = stock - n WHERE id = ? AND stock >= n`，0 行受影响抛 409。

### 7. 限流（登录）
固定窗口 60s/5 次，用户名 + IP 双维度计数；Redis 故障放行（不阻断登录主流程）。

---

## 六、测试矩阵

| 层级 | 命令 | 覆盖 |
|------|------|------|
| 单元测试 | `.\mvnw.cmd test` | 33 项：状态机/防超卖/超时取消/分布式锁/限流/热点防污染 |
| 集成测试 | `.\mvnw.cmd verify` | Testcontainers 真实 Redis：Cache Aside/空值标记/延迟双删/ZSet 热点（无 Docker 自动跳过） |
| 构建 | `.\mvnw.cmd clean package` | 可执行 jar + 前后端同源打包 |

> Windows 本机无 Docker 时集成测试 assumption 跳过，`mvn verify` 仍全绿。

---

## 七、面试要点速览

1. **为什么用 Redisson 而不是自己写 SETNX 锁？** 看门狗自动续期防死锁、可重入、高可用集群支持。
2. **延迟双删为什么延迟 1.5s？** 等读线程把旧缓存写完再删，消除读写竞争窗口。
3. **SCAN vs KEYS？** KEYS 全库 O(N) 阻塞主线程；SCAN 游标增量分批，不阻塞。
4. **空值缓存为什么 TTL 更短（60s）？** 防穿透同时也防"误标记"长期生效。
5. **超时取消如何防并发双回补？** `updateStatusIf` 只影响"待支付"状态的行，谁先改成功谁回补。
6. **Redis 挂了系统还能用吗？** 能——SafeRedisTemplate 降级直查 DB，只损失缓存性能。
7. **多实例部署缓存一致性问题？** 每实例本地锁失效，Redisson 分布式锁 + 延迟双删兜底。

---

## 八、演示流程（面试 5 分钟版）

1. `docker compose up -d --build` 一键起三容器
2. 登录（`admin/admin123`）→ 商品管理页模拟访问热点商品
3. 观察热点排行分数变化 + 自动预热轮次 +1
4. 打开监控大盘：命中率环形图、缓存 vs 数据库耗时对比实验
5. 演示防超卖：库存 5 的商品并发下单 6 笔 → 第 6 笔 409
6. 演示订单超时：下单后不支付，30 分钟（可配 ORDER_TIMEOUT_MINUTES=1）自动取消回补库存
7. 断 Redis 容器 `docker stop cache-redis` → 商品查询仍正常（降级直查 DB），恢复后自动预热

---

## 九、v1 → v2 → v3 演进

| 维度 | v1 | v2 | v3 |
|------|----|----|----|
| Spring Boot | 2.7（javax）| 3.2（jakarta）| 3.2.5 |
| 锁 | 本地 synchronized | 本地 synchronized | Redisson 分布式锁 |
| Redis 操作 | 直接 template | 直接 template | SafeRedisTemplate 降级 |
| 建表 | 手动 SQL | 手动 SQL | Flyway 版本化 |
| 测试 | 无 | 无 | 33 单测 + Testcontainers |
| 部署 | 手动 | 手动 | Docker Compose + healthcheck |
| 密码 | 硬编码 | 环境变量 | 环境变量 + .env 不提交 |