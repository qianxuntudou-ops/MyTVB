// ==UserScript==
// @name         红果登录态导出（MyTVB 导入用）
// @namespace    mytvb.hongguo
// @version      1.0
// @description  在 hongguoduanju.com 登录后一键导出：仿 cookieStore.xml 文件（含完整 cookie，TLV hex 编码）+ 复制 sessionid。导出内容直接粘贴进 MyTVB 红果「导入账号」输入框。
// @author       MyTVB HG
// @match        https://hongguoduanju.com/*
// @match        https://www.hongguoduanju.com/*
// @grant        GM_cookie
// @grant        GM_download
// @grant        GM_setClipboard
// @grant        GM_registerMenuCommand
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    // 与 App 端 HongguoCookieStoreParser.COOKIE_ORDER 同序（HGTV AbstractC0133c.f1871a）
    const COOKIE_ORDER = [
        'sessionid', 'sessionid_ss', 'sid_tt', 'uid_tt', 'uid_tt_ss', 'd_ticket',
        'sid_guard', 'session_tlb_tag', 'store-region', 'store-region-src',
        'install_id', 'n_mh', 'odin_tt', 'ttreq', 'passport_csrf_token',
        'passport_csrf_token_default'
    ];

    // TTNet cookieStore 值编码：字符串数组 → 't' + BE16 长度 + UTF-8 字节 → hex
    // （App 端 decodeTlvCookieValue 的逆过程；单条目 [name, value] 对）
    function tlvHex(name, value) {
        const enc = new TextEncoder();
        let out = '';
        for (const s of [name, value]) {
            const bytes = enc.encode(s);
            out += '74' + bytes.length.toString(16).padStart(4, '0');
            for (const b of bytes) out += b.toString(16).padStart(2, '0');
        }
        return out;
    }

    function fetchCookies() {
        return new Promise((resolve, reject) => {
            try {
                GM_cookie.list({ domain: 'hongguoduanju.com' }, (cookies, error) => {
                    if (error) reject(new Error('GM_cookie 失败：' + error));
                    else resolve(cookies || []);
                });
            } catch (e) {
                reject(new Error('当前脚本管理器不支持 GM_cookie（请用 Tampermonkey）'));
            }
        });
    }

    async function exportSession() {
        let cookies;
        try {
            cookies = await fetchCookies();
        } catch (e) {
            toast('❌ ' + e.message, 5000);
            return;
        }
        const map = {};
        for (const c of cookies) map[c.name] = c.value;

        const sid = map['sessionid'] || map['sessionid_ss'] || '';
        if (!sid) {
            toast('❌ 没读到 sessionid——请先在本页登录红果账号', 5000);
            return;
        }

        // 生成仿 cookieStore.xml（App 端按 snssdk.com 域+TLV hex 解析，见 HongguoCookieStoreParser）
        const lines = COOKIE_ORDER
            .filter(name => map[name])
            .map(name => `    <string name="https://snssdk.com/|${name}">${tlvHex(name, map[name])}</string>`);
        const xml = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n${lines.join('\n')}\n</map>\n`;

        const blob = new Blob([xml], { type: 'application/xml' });
        const stamp = new Date().toISOString().slice(0, 10);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `hongguo-session-${stamp}.xml`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 30_000);

        try { GM_setClipboard(sid); } catch (e) { /* 剪贴板失败不影响文件导出 */ }
        toast(`✅ 已导出 ${lines.length} 条 cookie 到文件；sessionid 已复制到剪贴板`, 5000);
    }

    function toast(msg, ms) {
        const el = document.createElement('div');
        el.textContent = msg;
        el.style.cssText = 'position:fixed;left:50%;top:16px;transform:translateX(-50%);' +
            'background:#1e2430;color:#fff;padding:10px 18px;border-radius:8px;' +
            'font:14px/1.4 sans-serif;z-index:2147483647;box-shadow:0 4px 16px rgba(0,0,0,.4)';
        document.body.appendChild(el);
        setTimeout(() => el.remove(), ms || 3000);
    }

    // 页面右下角悬浮按钮（未登录页也能点，报错提示登录）
    const btn = document.createElement('button');
    btn.textContent = '导出红果登录';
    btn.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;' +
        'background:#ff5f5f;color:#fff;border:none;border-radius:20px;padding:10px 16px;' +
        'font:14px sans-serif;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.35)';
    btn.onclick = exportSession;
    document.body.appendChild(btn);

    GM_registerMenuCommand('导出红果登录态（xml 文件 + 复制 sessionid）', exportSession);
})();
