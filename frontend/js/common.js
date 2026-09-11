/* ============================================================
   公共 JS：API 基础封装 + 页头导航渲染 + 登录态管理
   ============================================================ */

/**
 * 后端 API 地址（v3）：
 *   1. 优先取页面 URL 的同源地址（前端与后端同端口部署时，走相对路径，彻底摆脱端口硬编码）；
 *   2. 直接 file:// 或其他端口打开页面时，兜底 8083。
 */
const API_BASE = (location.protocol === 'http:' || location.protocol === 'https:')
    ? location.origin
    : 'http://127.0.0.1:8083';

/** Token 在 localStorage 的键名 */
const TOKEN_KEY = 'cache_token';
const USER_KEY  = 'cache_user';

/* ====== 登录态管理 ====== */

/** 保存登录态（token + 用户信息） */
function saveAuth(token, user) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** 读取 token（可能为 null） */
function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

/** 读取当前登录用户信息（可能为 null） */
function getLoginUser() {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
}

/** 是否已登录 */
function isLoggedIn() {
    return !!getToken();
}

/** 是否是管理员 */
function isAdmin() {
    const u = getLoginUser();
    return u && u.role === 'ADMIN';
}

/** 清除登录态并跳转登录页 */
function logout() {
    const token = getToken();
    if (token) {
        apiSend('/api/auth/logout', 'POST').catch(() => {});
    }
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    location.href = 'login.html';
}

/* ====== API 封装 ====== */

/** 构造请求头（自动附加 Authorization） */
function buildHeaders(extra) {
    const headers = { 'Content-Type': 'application/json' };
    const token = getToken();
    if (token) {
        headers['Authorization'] = token;
    }
    return Object.assign(headers, extra || {});
}

/**
 * 统一 GET 请求
 * 401 → 清除登录态并跳转登录页
 */
async function apiGet(path) {
    const res = await fetch(API_BASE + path, { headers: buildHeaders() });
    if (res.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        location.href = 'login.html';
        throw new Error('登录已过期，请重新登录');
    }
    if (!res.ok) {
        throw new Error('HTTP ' + res.status);
    }
    const json = await res.json();
    if (json.code !== 200) {
        throw new Error(json.message || '接口返回异常');
    }
    return json.data;
}

/**
 * 统一 POST/PUT/DELETE 请求
 * 401 → 清除登录态并跳转登录页
 */
async function apiSend(path, method, body) {
    const res = await fetch(API_BASE + path, {
        method: method,
        headers: buildHeaders(),
        body: body ? JSON.stringify(body) : undefined
    });
    if (res.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        location.href = 'login.html';
        throw new Error('登录已过期，请重新登录');
    }
    if (!res.ok) {
        throw new Error('HTTP ' + res.status);
    }
    const json = await res.json();
    if (json.code !== 200) {
        throw new Error(json.message || '接口返回异常');
    }
    return json.data;
}

/* ====== 导航栏 ====== */

/**
 * 简易消息提示（替代 Element Plus 的 ElMessage）
 */
function showToast(msg, type) {
    const colors = { success: '#67c23a', error: '#f56c6c', info: '#909399', warning: '#e6a23c' };
    const div = document.createElement('div');
    div.textContent = msg;
    div.style.cssText = `position:fixed;top:24px;left:50%;transform:translateX(-50%);
        background:${colors[type] || colors.info};color:#fff;padding:10px 24px;
        border-radius:6px;font-size:14px;z-index:9999;box-shadow:0 4px 12px rgba(0,0,0,.2)`;
    document.body.appendChild(div);
    setTimeout(() => div.remove(), 2500);
}

/** HTML 转义：用户可控内容拼进 innerHTML 前必须转义（XSS 防护） */
function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * 渲染页头导航（每个页面调用，active 参数为当前页）
 * 动态根据登录态 / 角色 显示不同导航项和右侧用户区域
 * v3：昵称等用户可控内容经 escapeHtml 转义后再拼入，修复存储型 XSS
 */
function renderHeader(active) {
    const navItems = [
        { key: 'mall',      href: 'mall.html',      label: '商城',        roles: ['guest', 'USER', 'ADMIN'] },
        { key: 'dashboard', href: 'dashboard.html',  label: '监控大盘',    roles: ['guest', 'USER', 'ADMIN'] },
        { key: 'hotspot',   href: 'hotspot.html',    label: '热点排行',    roles: ['guest', 'USER', 'ADMIN'] },
        { key: 'compare',   href: 'compare.html',    label: '耗时对比',    roles: ['guest', 'USER', 'ADMIN'] },
        { key: 'myOrders',  href: 'my-orders.html',  label: '我的订单',    roles: ['USER', 'ADMIN'] },
        { key: 'products',  href: 'products.html',   label: '商品管理',    roles: ['ADMIN'] },
        { key: 'adminOrders', href: 'admin-orders.html', label: '订单审核', roles: ['ADMIN'] }
    ];

    const user = getLoginUser();
    const role = user ? user.role : 'guest';

    const links = navItems
        .filter(item => item.roles.includes(role))
        .map(item =>
            `<a href="${item.href}" class="${item.key === active ? 'active' : ''}">${item.label}</a>`
        ).join('');

    const headerEl = document.getElementById('header');
    headerEl.className = 'header';
    headerEl.innerHTML = `
        <div class="header-row">
            <div class="brand">
                <h1>热点数据缓存预热与一致性保障系统</h1>
                <span class="sub">v3.0 · Spring Boot 3 · 分布式锁 · 自动热点识别</span>
            </div>
            <div class="user-area">
                ${user
                    ? `<span class="user-name">${escapeHtml(user.nickname || user.username)}</span>
                       <span class="user-tag ${user.role === 'ADMIN' ? 'tag-admin' : 'tag-user'}">${user.role === 'ADMIN' ? '管理员' : '用户'}</span>
                       <a href="javascript:logout()" class="logout-btn">退出</a>`
                    : `<a href="login.html" class="login-link">登录 / 注册</a>`
                }
            </div>
        </div>
        <div class="nav">${links}</div>
    `;
}
