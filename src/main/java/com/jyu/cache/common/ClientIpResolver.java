package com.jyu.cache.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析（v3.3 新增，替换原来"无条件信任 X-Forwarded-For"的写法）
 *
 * 【原来错在哪】旧实现直接取 XFF 的第一段作为客户端 IP：
 *   String xff = request.getHeader("X-Forwarded-For"); if (xff != null) return xff.split(",")[0];
 * 而 XFF 是**请求头**，任何客户端都能随便写。于是"按 IP 维度限流"形同虚设——
 * 攻击者每次请求换一个 X-Forwarded-For，就等于每次都是新 IP（实测可复现）。
 *
 * 【正确做法】只有"直连方（socket 地址）"本身是可信代理时，才采信 XFF；
 * 采信时也不取最左段（那是客户端可伪造的），而是**从右往左找第一个不在可信代理里的地址**——
 * 那才是链路上第一个真实客户端。
 *
 * 默认不信任任何代理（trusted-proxies 留空）：直接用 socket 地址，最安全。
 * 部署在 Nginx/网关后面时，把网关地址配进 APP_TRUSTED_PROXIES（支持 IPv4 与 CIDR）。
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = Cidr.parseList(trustedProxies);
        if (this.trustedProxies.isEmpty()) {
            log.info("[安全] 未配置可信代理（app.security.trusted-proxies 为空）："
                    + "X-Forwarded-For 一律不采信，客户端 IP 取 socket 地址——伪造该头无法绕过 IP 维度限流");
        } else {
            log.info("[安全] 可信代理 {} 个：{}（仅当直连方命中时才解析 X-Forwarded-For）",
                    this.trustedProxies.size(), this.trustedProxies);
        }
    }

    /** 解析客户端 IP：默认返回 socket 地址；直连方是可信代理时才解析 XFF */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = normalize(hops[i]);
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;   // 链路上第一个"不可信"的地址 = 真实客户端
            }
        }
        // 整条链都是可信代理：退回 socket 地址
        return remoteAddr;
    }

    private boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String normalized = normalize(ip);
        for (Cidr cidr : trustedProxies) {
            if (cidr.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉首尾空格、IPv6 方括号，以及 IPv4:port 形式里的端口 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        // 只对 "a.b.c.d:port" 这种 IPv4 形式去端口；IPv6 自身含冒号，不能动
        int firstColon = value.indexOf(':');
        if (firstColon > 0 && value.indexOf(':', firstColon + 1) < 0 && value.substring(0, firstColon).contains(".")) {
            value = value.substring(0, firstColon);
        }
        return value;
    }

    /** 极简 CIDR / 单 IP 匹配（IPv4 走二进制比较，IPv6 走字符串精确匹配） */
    static final class Cidr {

        private final String raw;
        private final long network;
        private final long mask;
        private final boolean ipv6;

        private Cidr(String raw, long network, long mask, boolean ipv6) {
            this.raw = raw;
            this.network = network;
            this.mask = mask;
            this.ipv6 = ipv6;
        }

        static List<Cidr> parseList(String spec) {
            List<Cidr> list = new ArrayList<>();
            if (spec == null || spec.isBlank()) {
                return list;
            }
            for (String part : spec.split(",")) {
                String item = part.trim();
                if (item.isEmpty()) {
                    continue;
                }
                Cidr cidr = parse(item);
                if (cidr != null) {
                    list.add(cidr);
                } else {
                    log.warn("[安全] 忽略无法解析的可信代理配置项：{}", item);
                }
            }
            return list;
        }

        static Cidr parse(String item) {
            if (item.contains(":")) {
                // IPv6：不做 CIDR，按精确匹配处理
                return new Cidr(item.toLowerCase(), 0L, 0L, true);
            }
            String ipPart = item;
            int prefix = 32;
            int slash = item.indexOf('/');
            if (slash > 0) {
                ipPart = item.substring(0, slash);
                try {
                    prefix = Integer.parseInt(item.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (prefix < 0 || prefix > 32) {
                    return null;
                }
            }
            Long ip = toLong(ipPart);
            if (ip == null) {
                return null;
            }
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return new Cidr(item, ip & mask, mask, false);
        }

        boolean matches(String ip) {
            if (ip == null || ip.isEmpty()) {
                return false;
            }
            if (ipv6) {
                return ip.toLowerCase().equals(raw);
            }
            Long value = toLong(ip);
            return value != null && (value & mask) == network;
        }

        /** 仅支持点分十进制 IPv4；其它（含 IPv6）返回 null */
        private static Long toLong(String ip) {
            if (ip == null || ip.indexOf(':') >= 0) {
                return null;
            }
            String[] parts = ip.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            long value = 0L;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return null;
                }
                int octet;
                try {
                    octet = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (octet < 0 || octet > 255) {
                    return null;
                }
                value = (value << 8) | octet;
            }
            return value;
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}
