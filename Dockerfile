# ============================================================
# v3 多阶段构建
# 阶段1 builder：JDK 21 编译打包（跳过测试，测试在 CI 阶段跑）
# 阶段2 runtime：JRE 21 运行镜像（体积减半，攻击面更小）
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# 先只拷 pom：依赖层不变时命中缓存，改代码不重新下载依赖
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline -DskipTests

# 再拷源码打包
COPY src src
# 前端静态资源打进 Spring Boot static 目录（与后端同端口，API_BASE 同源直连）
COPY frontend /build/frontend
RUN mkdir -p src/main/resources/static \
 && cp -r /build/frontend/* src/main/resources/static/ \
 && ./mvnw -q clean package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 时区（MySQL 连接串用 Asia/Shanghai）与字体等基础工具
ENV TZ=Asia/Shanghai JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# 非 root 运行（安全基线）
RUN useradd -r -u 1001 appuser \
 && mkdir -p /app/logs \
 && chown -R appuser:appuser /app
USER appuser

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8083

# 健康检查交给 compose 的 healthcheck（用 wget 打 readiness 探针）
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8083/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
