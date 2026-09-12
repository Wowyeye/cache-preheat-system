package com.jyu.cache.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger UI 配置（v3.4 新增）
 *
 * 访问：/swagger-ui.html（界面）  /v3/api-docs（JSON 规范）
 *
 * 为什么值得加：
 *   1. 面试/答辩现场不用翻 README 找接口，打开 UI 就能点着调；
 *   2. 鉴权方式在文档里显式声明（Authorization 头 + 登录返回的 token 原值），
 *      避免"这个接口到底要不要 token"的反复确认；
 *   3. 接口即文档，改代码时忘了改文档的情况会明显减少。
 *
 * 注意：Swagger UI 与 /v3/api-docs 是**公开**的（拦截器只管 /api/**）。
 *      演示项目这样最方便；生产环境可用 springdoc.api-docs.enabled=false 关闭，
 *      或放在内网/网关鉴权之后。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cachePreheatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("热点数据缓存预热与缓存一致性保障系统 API")
                        .version("v3.4")
                        .description("""
                                Spring Boot 3.2 + Redis 7 + MySQL 8 + MyBatis 的缓存预热 / 一致性演示系统。

                                **鉴权**：先调「认证 → 登录」拿到 token，再在右上角 Authorize 里填 token 原值
                                （请求头 `Authorization`，不带 Bearer 前缀）。

                                **权限**：商品增删改、订单审核、缓存运维（预热/清缓存）、完整缓存统计均为 ADMIN；
                                商品查询、热度榜、缓存摘要对游客开放。

                                **可观测**：`/actuator/health`、`/actuator/prometheus`。
                                """))
                .components(new Components().addSecuritySchemes("token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("登录接口返回的 token 原值")))
                .tags(List.of(
                        new Tag().name("认证").description("注册 / 登录 / 登出 / 当前用户"),
                        new Tag().name("商品").description("缓存旁路读；增删改触发延迟双删；含缓存监控与预热"),
                        new Tag().name("订单").description("下单原子扣库存；8 态状态机流转"),
                        new Tag().name("订单管理").description("管理员：全部订单、退款 / 退货审核"),
                        new Tag().name("热点").description("ZSet 热度榜、自动预热状态与手动预热"),
                        new Tag().name("分类").description("商品分类列表")));
    }
}
