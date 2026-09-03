package com.jyu.cache;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 热点数据缓存预热与缓存一致性保障系统 - 启动类
 *
 * 功能概述：
 * 1. 缓存预热：项目启动时自动将热点商品（按浏览量排序前N条）加载到 Redis
 * 2. Cache Aside 旁路缓存：查询时先查缓存，未命中再查数据库，查到后写入缓存
 * 3. 缓存一致性：更新数据时采用「延迟双删」策略保证缓存与数据库一致
 * 4. 缓存穿透防护：查询不到的数据缓存空值，防止恶意请求穿透到数据库
 * 5. 缓存击穿防护：热点 key 过期时使用互斥锁，防止大量请求同时打到数据库
 * 6. 缓存雪崩防护：缓存过期时间加随机值，防止大量 key 同时失效
 */

@SpringBootApplication
@MapperScan("com.jyu.cache.mapper")
public class CacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheApplication.class, args);
    }
}
