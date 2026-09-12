package com.jyu.cache.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 * ClientIpResolver 单元测试（v3.3 新增）
 *
 * 回归重点：**默认配置下伪造 X-Forwarded-For 不能改变客户端 IP**。
 * 旧实现无条件取 XFF 第一段，等于让攻击者自由选择自己的"IP"，按 IP 维度的限流被绕过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户端 IP 解析（可信代理 / XFF 防伪造）单元测试")
class ClientIpResolverTest {

    private static final String XFF = "X-Forwarded-For";

    @Mock private HttpServletRequest request;

    private ClientIpResolver resolver(String trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }

    private void stubRequest(String remoteAddr, String forwardedFor) {
        lenient().when(request.getRemoteAddr()).thenReturn(remoteAddr);
        lenient().when(request.getHeader(XFF)).thenReturn(forwardedFor);
    }

    @Test
    @DisplayName("默认（未配置可信代理）：伪造 X-Forwarded-For 无效，取 socket 地址")
    void ignoresForwardedHeaderByDefault() {
        stubRequest("203.0.113.9", "1.1.1.1");

        assertEquals("203.0.113.9", resolver("").resolve(request));
    }

    @Test
    @DisplayName("直连方是可信代理：采信 X-Forwarded-For（单跳）")
    void trustsForwardedHeaderFromTrustedProxy() {
        stubRequest("10.0.0.5", "203.0.113.9");

        assertEquals("203.0.113.9", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("多跳：取最右侧的不可信地址（客户端伪造的左段被忽略）")
    void takesRightmostUntrustedHop() {
        // 攻击者在最左侧塞了 6.6.6.6；真实链路的客户端是 203.0.113.9
        stubRequest("10.0.0.5", "6.6.6.6, 203.0.113.9");

        assertEquals("203.0.113.9", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("直连方不在可信列表：即使带 XFF 也不采信")
    void ignoresForwardedHeaderFromUntrustedPeer() {
        stubRequest("203.0.113.9", "1.1.1.1");

        assertEquals("203.0.113.9", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("没有 X-Forwarded-For：用 socket 地址")
    void fallsBackWhenHeaderMissing() {
        stubRequest("10.0.0.5", null);

        assertEquals("10.0.0.5", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("整条 XFF 链都是可信代理：回退 socket 地址")
    void fallsBackWhenAllHopsTrusted() {
        stubRequest("10.0.0.5", "10.0.0.6, 10.0.0.7");

        assertEquals("10.0.0.5", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("可信列表支持 CIDR 与单个 IP 混配")
    void supportsCidrAndSingleIp() {
        stubRequest("192.168.1.10", "203.0.113.9");
        assertEquals("203.0.113.9", resolver("10.0.0.0/8,192.168.1.10").resolve(request));

        stubRequest("192.168.1.11", "203.0.113.9");
        assertEquals("192.168.1.11", resolver("10.0.0.0/8,192.168.1.10").resolve(request));
    }

    @Test
    @DisplayName("XFF 里带端口的 IPv4 形式会被规范成纯 IP")
    void stripsPortFromForwardedIp() {
        stubRequest("10.0.0.5", "203.0.113.9:51423");

        assertEquals("203.0.113.9", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("XFF 里的空项/非法项被跳过，取右侧第一个合法地址")
    void skipsBlankEntries() {
        stubRequest("10.0.0.5", " , 203.0.113.9 , ");

        assertEquals("203.0.113.9", resolver("10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("IPv6 直连方不会误命中 IPv4 可信列表（按不可信处理）")
    void ipv6PeerDoesNotMatchIpv4TrustList() {
        stubRequest("::1", "203.0.113.9");

        assertEquals("::1", resolver("127.0.0.1,10.0.0.0/8").resolve(request));
    }

    @Test
    @DisplayName("IPv6 可信代理支持精确匹配")
    void ipv6TrustedProxyExactMatch() {
        stubRequest("::1", "203.0.113.9");

        assertEquals("203.0.113.9", resolver("::1").resolve(request));
    }

    @Test
    @DisplayName("非法配置项被忽略，不会让解析崩溃")
    void ignoresInvalidTrustListEntries() {
        stubRequest("10.0.0.5", "203.0.113.9");

        assertEquals("203.0.113.9", resolver("not-an-ip,300.1.1.1/8,10.0.0.0/8").resolve(request));
    }
}
