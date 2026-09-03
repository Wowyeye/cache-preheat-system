package com.jyu.cache.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * WSL Redis 主机地址自动探测器
 *
 * 背景：WSL2 每次重启后 IP 会漂移，写死在 application.yml 里的 Redis 地址会失效。
 * 本类在 Spring 环境准备阶段（早于 Bean 初始化）执行：
 *   1. 读取配置的 spring.redis.host，尝试 TCP 连接
 *   2. 可达 -> 什么都不做，跳过探测
 *   3. 不可达 -> 调用 wsl hostname -I 获取 WSL2 当前 IP，覆盖 spring.redis.host
 *
 * 注册方式：META-INF/spring.factories 中声明为 EnvironmentPostProcessor
 */
public class WslRedisHostDetector implements EnvironmentPostProcessor {

    private static final String HOST_KEY = "spring.redis.host";
    private static final String PORT_KEY = "spring.redis.port";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String host = environment.getProperty(HOST_KEY, "127.0.0.1");
        int port = Integer.parseInt(environment.getProperty(PORT_KEY, "6379"));

        // 第一步：配置的地址可达，直接跳过
        if (isReachable(host, port)) {
            System.out.println("[WSL探测] 配置的 Redis 地址 " + host + " 可达，跳过自动探测");
            return;
        }

        System.out.println("[WSL探测] 配置的 Redis 地址 " + host + ":" + port + " 不可达，尝试探测 WSL2 IP...");

        // 第二步：探测 WSL2 的 IP
        String wslIp = detectWslIp();
        if (wslIp == null) {
            System.out.println("[WSL探测] 未找到 WSL2 实例，保持原配置");
            return;
        }

        // 第三步：WSL IP 可达则覆盖配置（addFirst 保证优先级最高）
        if (isReachable(wslIp, port)) {
            Map<String, Object> override = new HashMap<>(2);
            override.put(HOST_KEY, wslIp);
            environment.getPropertySources().addFirst(
                    new MapPropertySource("wslRedisHost", override));
            System.out.println("[WSL探测] 已将 Redis 地址覆盖为 WSL2 IP: " + wslIp);
        } else {
            System.out.println("[WSL探测] WSL2 IP " + wslIp + " 的 " + port + " 端口不可达，保持原配置");
        }
    }

    /** TCP 探测：3 秒超时 */
    private boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行 wsl hostname -I 获取 WSL2 的 IP（取第一个） */
    private String detectWslIp() {
        try {
            Process process = new ProcessBuilder("wsl", "hostname", "-I")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim().split("\\s+")[0];
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.out.println("[WSL探测] 执行 wsl 命令失败: " + e.getMessage());
        }
        return null;
    }
}
