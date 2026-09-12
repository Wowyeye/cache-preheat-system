# ============================================================
# v3 多阶段构建
# 阶段1 builder：JDK 21 编译打包（跳过测试，测试在 CI 阶段跑）
# 阶段2 runtime：JRE 21 运行镜像（体积减半，攻击面更小）
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# 国内网络下 repo.maven.apache.org 常常拉不动，所以把"换源"做成构建参数（默认空 = 官方源，保持可移植）：
#   MVNW_REPOURL     : Maven Wrapper 分发包仓库前缀（wrapper 官方支持，会拼在 /org/apache/maven/ 之前）
#   MAVEN_MIRROR_URL : 依赖仓库镜像（写入 builder 阶段的 ~/.m2/settings.xml）
# 用法：docker compose build --build-arg MVNW_REPOURL=... --build-arg MAVEN_MIRROR_URL=...
# 或直接写进 .env（compose 会自动读取），见 .env.example
ARG MVNW_REPOURL=""
ARG MAVEN_MIRROR_URL=""
ENV MVNW_REPOURL=${MVNW_REPOURL}

# 先只拷 pom：依赖层不变时命中缓存，改代码不重新下载依赖
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN set -e; \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
        mkdir -p /root/.m2; \
        printf '%s\n' "<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>$MAVEN_MIRROR_URL</url></mirror></mirrors></settings>" > /root/.m2/settings.xml; \
    fi; \
    printf '[build] MVNW_REPOURL=%s  MAVEN_MIRROR_URL=%s\n' "${MVNW_REPOURL:-<official>}" "${MAVEN_MIRROR_URL:-<official>}"; \
    chmod +x mvnw && ./mvnw -q dependency:go-offline -DskipTests

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
