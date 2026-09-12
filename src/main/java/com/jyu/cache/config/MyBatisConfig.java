package com.jyu.cache.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置（v3.3 从启动类挪出来）
 *
 * 【为什么要挪】`@MapperScan` 原本挂在 `CacheApplication` 上，而切片测试
 * （`@WebMvcTest`）会以启动类为上下文根、处理它上面的注解 —— 于是切片测试里
 * 也会去创建 MyBatis Mapper，但切片不加载 MyBatis 自动配置（没有 SqlSessionFactory），
 * 直接报 "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required"。
 *
 * 挪到独立的 `@Configuration` 之后：
 *   - 完整的 `@SpringBootTest`（如 DbLayerIT）照常扫描到它；
 *   - `@WebMvcTest` 这类切片不会加载它，Web 层契约测试得以只依赖 Web 组件。
 */
@Configuration
@MapperScan("com.jyu.cache.mapper")
public class MyBatisConfig {
}
