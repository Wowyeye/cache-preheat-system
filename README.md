# 热点数据缓存预热与缓存一致性保障系统

> Spring Boot + Redis + MyBatis + Vue3

## 一、项目简介

本系统模拟电商场景下的热点商品查询，围绕 Redis 缓存实现了六大核心策略：
缓存预热、Cache Aside 旁路缓存、延迟双删、缓存穿透防护、缓存击穿防护、缓存雪崩防护。

## 二、技术栈

| 层次     | 技术                         |
|----------|------------------------------|
| 后端框架 | Spring Boot 2.7.18           |
| 缓存     | Redis（Lettuce 客户端）       |
| ORM      | MyBatis 2.3.2                |
| 数据库   | MySQL 8.0 + Druid 连接池      |
| 前端     | Vue3 + Element Plus（CDN方式）|
| 构建工具 | Maven                        |
| JDK      | Java 17                      |

## 三、项目结构

```
cache-preheat-system/
├── pom.xml                          # Maven 依赖配置
├── src/main/
│   ├── java/com/jyu/cache/
│   │   ├── CacheApplication.java         # 启动类
│   │   ├── config/
│   │   │   ├── RedisConfig.java          # Redis 序列化配置
│   │   │   └── CacheProperties.java      # 缓存参数配置
│   │   ├── entity/
│   │   │   ├── Product.java              # 商品实体
│   │   │   └── Category.java             # 分类实体
│   │   ├── mapper/
│   │   │   ├── ProductMapper.java        # 商品Mapper接口
│   │   │   └── CategoryMapper.java       # 分类Mapper接口
│   │   ├── service/
│   │   │   ├── ProductService.java        # 商品Service接口
│   │   │   ├── ProductServiceImpl.java   # 商品Service实现（核心）
│   │   │   ├── CategoryService.java      # 分类Service接口
│   │   │   └── CategoryServiceImpl.java  # 分类Service实现
│   │   ├── controller/
│   │   │   ├── ProductController.java    # 商品API + 缓存监控API
│   │   │   └── CategoryController.java   # 分类API
│   │   ├── runner/
│   │   │   └── CachePreheatRunner.java   # 启动时缓存预热
│   │   └── common/
│   │       └── Result.java               # 统一响应格式
│   └── resources/
│       ├── application.yml              # 配置文件
│       ├── mapper/
│       │   ├── ProductMapper.xml         # 商品SQL
│       │   └── CategoryMapper.xml        # 分类SQL
│       └── sql/
│           └── init.sql                  # 数据库数据库初始化脚本
└── frontend/
    └── index.html                       # Vue3 前端面板
```

## 四、运行步骤

### 1. 准备环境
- JDK 17
- MySQL 8.0
- Redis（Windows 下可用 WSL2 或 Memurai）

### 2. 初始化数据库
```bash
mysql -u root -p < src/main/resources/sql/init.sql
```

### 3. 修改配置
编辑 `src/main/resources/application.yml`，修改数据库密码和 Redis 地址：
```yaml
spring:
  datasource:
    password: 你的MySQL密码
  redis:
    host: 127.0.0.1
    password: 你的Redis密码
```

### 4. 启动后端
```bash
mvn spring-boot:run
```
启动后自动执行缓存预热，控制台会输出预热日志。

### 5. 打开前端
直接用浏览器打开 `frontend/index.html` 即可（后端已配置 CORS，支持跨域访问）。

## 五、六大缓存策略详解

### 1. 缓存预热
- 时机：项目启动时自动执行（CachePreheatRunner）
- 逻辑：从数据库查询浏览量最高的10条商品，写入 Redis
- 目的：避免用户首次访问热点商品时缓存未命中

### 2. Cache Aside 旁路缓存（读策略）
```
查询商品:
  1. 先查 Redis
  2. 命中 -> 返回
  3. 未命中 -> 查数据库 -> 写入 Redis -> 返回
```

### 3. 延迟双删（写策略 / 缓存一致性）
```
更新商品:
  1. 更新数据库
  2. 删除缓存（第1次）
  3. 延迟1.5秒
  4. 再次删除缓存（第2次）
```
目的：覆盖"第一次删除后读请求把旧数据写回缓存"的极端情况。

### 4. 缓存穿透防护
- 问题：查询不存在的数据，每次都穿透到数据库
- 方案：数据库查不到时缓存空值标记（TTL 60秒）

### 5. 缓存击穿防护
- 问题：热点key过期瞬间，大量请求同时打到数据库
- 方案：使用 synchronized 互斥锁，只允许一个线程查数据库

### 6. 缓存雪崩防护
- 问题：大量key同时过期
- 方案：缓存TTL加随机值（0~300秒），分散过期

## 六、API 接口列表

| 方法   | 路径                        | 说明               |
|--------|-----------------------------|---------------------|
| GET    | /api/product/{id}           | 查询商品（走缓存）  |
| GET    | /api/product/list            | 查询所有商品        |
| POST   | /api/product                 | 新增商品            |
| PUT    | /api/product                 | 更新商品（延迟双删）|
| DELETE | /api/product/{id}            | 删除商品（延迟双删）|
| GET    | /api/product/cache/stats     | 缓存统计            |
| DELETE | /api/product/cache/all       | 清除所有缓存        |
| DELETE | /api/product/cache/{id}       | 清除指定缓存        |
| POST   | /api/product/preheat          | 手动缓存预热        |
| GET    | /api/category/list            | 查询所有分类        |

## 七、测试验证

1. **验证缓存预热**：启动项目后查看控制台日志，确认10条热点商品已加载
2. **验证Cache Aside**：首次查询商品（查看日志显示"缓存未命中"），再次查询（显示"缓存命中"）
3. **验证延迟双删**：编辑商品后查看日志，会看到"第一次删除缓存"和"延迟双删：第二次删除缓存"
4. **验证穿透防护**：查询一个不存在的商品ID（如999），再查一次，第二次不会查数据库
5. **查看命中率**：前端面板实时显示缓存命中率和统计信息
