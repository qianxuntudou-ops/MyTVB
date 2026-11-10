// ==UserScript==
// @name         红果登录态导出（MyTVB 导入用）
// @namespace    mytvb.hongguo
// @version      1.6
// @description  在 hongguoduanju.com 登录后一键导出：仿 cookieStore.xml 文件（含完整 cookie，TLV hex 编码）+ 复制 sessionid。导出内容直接粘贴进 MyTVB 红果「导入账号」输入框。
// @author       MyTVB HG
// @match        https://hongguoduanju.com/*
// @match        https://www.hongguoduanju.com/*
// @grant        GM_cookie
// @grant        GM_setClipboard
// @grant        GM_registerMenuCommand
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    // ---- 请求头捕获：红果网页版登录态走 header 鉴权（实测 cookie 与网页存储里都没有 sessionid），
    // hook fetch/XHR 抓 API 请求头，菜单「导出捕获的请求头」即可定位 token 所在 ----
    const captured = [];

    function redactValue(v) {
        const s = String(v);
        return s.length > 24 ? s.slice(0, 12) + `…(len=${s.length})` : s;
    }

    function headersOf(h) {
        const out = {};
        if (!h) return out;
        try {
            if (typeof Headers !== 'undefined' && h instanceof Headers) {
                h.forEach((v, k) => { out[k] = redactValue(v); });
            } else if (Array.isArray(h)) {
                for (const [k, v] of h) out[k] = redactValue(v);
            } else {
                for (const k in h) out[k] = redactValue(h[k]);
            }
        } catch (e) { /* 忽略 */ }
        return out;
    }

    function captureRecord(rec) {
        captured.push(rec);
        while (captured.length > 60) captured.shift();
    }

    function looksApi(url) {
        return /hongguoduanju|zijieapi|fqnovel|passport|snssdk/i.test(url);
    }

    function paramsOf(url) {
        const out = {};
        const i = url.indexOf('?');
        if (i < 0) return out;
        try {
            for (const kv of url.slice(i + 1).split('&').slice(0, 40)) {
                const j = kv.indexOf('=');
                if (j > 0) out[decodeURIComponent(kv.slice(0, j))] = redactValue(decodeURIComponent(kv.slice(j + 1)));
            }
        } catch (e) { /* 忽略 */ }
        return out;
    }

    const origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function (input, init) {
            let rec = null;
            try {
                const fullUrl = String(typeof input === 'string' ? input : (input && input.url) || '');
                if (looksApi(fullUrl)) {
                    const headers = Object.assign({}, headersOf(input && input.headers), headersOf(init && init.headers));
                    rec = {
                        kind: 'fetch',
                        method: ((init && init.method) || (input && input.method) || 'GET').toUpperCase(),
                        url: fullUrl.slice(0, 600),
                        params: paramsOf(fullUrl),
                        headers
                    };
                    captureRecord(rec);
                }
            } catch (e) { /* 捕获失败不影响请求 */ }
            const p = origFetch.apply(this, arguments);
            if (rec) {
                try {
                    p.then(resp => {
                        try {
                            resp.clone().text().then(t => {
                                rec.respStatus = resp.status;
                                rec.resp = String(t).slice(0, 300);
                            }).catch(() => { /* 流式响应跳过 */ });
                        } catch (e) { /* clone 失败跳过 */ }
                    }).catch(() => { /* 请求失败跳过 */ });
                } catch (e) { /* 忽略 */ }
            }
            return p;
        };
    }

    const XHR_OPEN = XMLHttpRequest.prototype.open;
    const XHR_SET = XMLHttpRequest.prototype.setRequestHeader;
    const XHR_SEND = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        try {
            const fullUrl = String(url || '');
            if (looksApi(fullUrl)) {
                this.__hgRec = { kind: 'xhr', method: String(method || 'GET').toUpperCase(), url: fullUrl.slice(0, 600), params: paramsOf(fullUrl), headers: {} };
            }
        } catch (e) { /* 忽略 */ }
        return XHR_OPEN.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
        try {
            if (this.__hgRec) this.__hgRec.headers[k] = redactValue(v);
        } catch (e) { /* 忽略 */ }
        return XHR_SET.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
        try {
            const rec = this.__hgRec;
            if (rec) {
                this.addEventListener('load', () => {
                    try {
                        rec.respStatus = this.status;
                        rec.resp = String(this.responseText || '').slice(0, 300);
                    } catch (e) { /* 忽略 */ }
                });
                captureRecord(rec);
            }
        } catch (e) { /* 忽略 */ }
        return XHR_SEND.apply(this, arguments);
    };

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

    const SID_NAMES = ['sessionid', 'sessionid_ss'];
    // 字节系常见 cookie 域（红果/头条通行证/抖音系），兜底扫描后聚类用
    const HG_COOKIE_DOMAIN_PAT = /hongguo|snssdk|douyin|toutiao|zijie|bytedance/i;

    function gmList(filter) {
        return new Promise((resolve, reject) => {
            try {
                GM_cookie.list(filter, (cookies, error) => {
                    if (error) reject(new Error('GM_cookie 失败：' + error));
                    else resolve(cookies || []);
                });
            } catch (e) {
                reject(new Error('当前脚本管理器不支持 GM_cookie（请用 Tampermonkey）'));
            }
        });
    }

    function hasSessionId(list) {
        return list.some(c => SID_NAMES.includes(c.name));
    }

    async function fetchCookies() {
        // 第一轮：按红果域过滤
        let byDomain = [];
        let firstError = null;
        try {
            byDomain = await gmList({ domain: 'hongguoduanju.com' });
        } catch (e) {
            firstError = e;
        }
        if (hasSessionId(byDomain)) return { cookies: byDomain, via: 'domain=hongguoduanju.com' };

        // 兜底：不做域过滤，全浏览器扫描后前端按字节系域名聚类——
        // 用于定位「登录 cookie 不在 hongguoduanju.com / 不叫 sessionid」的情况
        let all = [];
        for (const filter of [{ currentBrowser: true }, {}, { firstPartyDomain: '' }]) {
            try {
                const r = await gmList(filter);
                if (r.length > all.length) all = r;
            } catch (e) {
                if (!firstError) firstError = e;
            }
            if (hasSessionId(all)) break;
        }
        if (!byDomain.length && !all.length && firstError) throw firstError;

        const related = all.filter(c => HG_COOKIE_DOMAIN_PAT.test(c.domain || ''));
        if (hasSessionId(all) && !hasSessionId(related)) {
            return { cookies: all, via: 'browser-scan(all)' };
        }
        return {
            cookies: related.length ? related : (all.length ? all : byDomain),
            via: all.length ? 'browser-scan' : (byDomain.length ? 'domain-filter only' : 'empty')
        };
    }

    async function exportSession() {
        let result;
        try {
            result = await fetchCookies();
        } catch (e) {
            toast('❌ ' + e.message, 8000);
            return;
        }
        const map = {};
        for (const c of result.cookies) map[c.name] = c.value;

        let sid = map['sessionid'] || map['sessionid_ss'] || '';
        let sidVia = null;
        if (!sid) {
            // 红果网页版登录态不走 cookie：从 localStorage / sessionStorage 找 sessionid 兜底
            const cands = findSidCandidates(scanStorage());
            if (cands.length === 1) {
                sid = cands[0].sid;
                sidVia = `web-storage: ${cands[0].key}`;
            } else if (cands.length > 1) {
                dumpStorageCandidates(cands);
                return;
            } else {
                dumpStorageDiagnostics(scanStorage());
                return;
            }
        }
        // sid 可能来自网页存储兜底：cookie 里没有时 map 缺这个键，必须补上才能进 xml
        map['sessionid'] = sid;

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
        const viaText = sidVia ? `${result.via} + ${sidVia}` : result.via;
        toast(`✅ 已导出（读取方式：${viaText}）；sessionid 已复制到剪贴板`, 5000);
    }

    function dumpDiagnostics(cookies, via) {
        const related = c => HG_COOKIE_DOMAIN_PAT.test(c.domain || '');
        const sorted = [...cookies].sort((a, b) => (related(b) ? 1 : 0) - (related(a) ? 1 : 0));
        const lines = sorted.map(c =>
            `${c.name} @ ${c.domain}${c.httpOnly ? ' [HttpOnly]' : ''}${related(c) ? ' ★' : ''}`);
        console.log(`[红果导出] 读取方式=${via}，共 ${cookies.length} 条 cookie：`, cookies);
        const clip = lines.slice(0, 200).join('\n') + (lines.length > 200 ? `\n…共 ${lines.length} 条` : '');
        try { GM_setClipboard(clip || '(空列表：GM_cookie 一条 cookie 都没读到)'); } catch (e) { /* 忽略 */ }
        const shown = lines.slice(0, 15).join('\n');
        toast(`❌ 没读到 sessionid（读取方式：${via}）。共读到 ${cookies.length} 条 cookie，字节系相关的带 ★。\n${shown || '（空列表）'}\n完整名单已复制到剪贴板——请把它发给开发者定位`, 20000);
    }

    // ---- 网页存储扫描：红果网页版登录态不走 cookie（实测只有 CSRF/埋点 cookie），兜底找 sessionid ----
    // App 端导入通道（HongguoAccount.kt:338）接受裸的 32 位十六进制 sessionid
    const SID_HEX_RE = /^[0-9a-fA-F]{32}$/;

    function scanStorage() {
        const out = [];
        for (const [store, s] of [['local', window.localStorage], ['session', window.sessionStorage]]) {
            try {
                for (let i = 0; i < s.length; i++) {
                    const k = s.key(i);
                    let v = '';
                    try { v = s.getItem(k) || ''; } catch (e) { /* 跳过取不到的键 */ }
                    out.push({ store, key: k, value: v });
                }
            } catch (e) { /* 某个 store 不可访问 */ }
        }
        return out;
    }

    // 从网页存储找疑似 sessionid：值本身是 32 位 hex，或 JSON 值里 session* 字段是 32 位 hex
    function findSidCandidates(entries) {
        const found = [];
        for (const e of entries) {
            const v = e.value.trim();
            if (SID_HEX_RE.test(v)) { found.push({ ...e, sid: v }); continue; }
            if (!v.startsWith('{') && !v.startsWith('[')) continue;
            try {
                const stack = [JSON.parse(v)];
                let guard = 0;
                while (stack.length && guard++ < 200) {
                    const cur = stack.pop();
                    if (!cur || typeof cur !== 'object') continue;
                    for (const [k2, v2] of Object.entries(cur)) {
                        if (typeof v2 === 'string' && SID_HEX_RE.test(v2) && /session|sid/i.test(k2)) {
                            found.push({ ...e, key: `${e.key} → ${k2}`, sid: v2 });
                        } else if (v2 && typeof v2 === 'object') {
                            stack.push(v2);
                        }
                    }
                }
            } catch (err) { /* 非 JSON 忽略 */ }
        }
        return found;
    }

    function dumpStorageCandidates(cands) {
        const lines = cands.map(c => `[${c.store}] ${c.key} = ${c.sid.slice(0, 8)}…（32 位 hex）`);
        console.log('[红果导出] 找到多个疑似 sessionid：', cands);
        try { GM_setClipboard(lines.join('\n')); } catch (e) { /* 忽略 */ }
        toast(`⚠️ cookie 里没有 sessionid，网页存储里找到 ${cands.length} 个疑似登录凭证（名单已复制到剪贴板）：\n${lines.join('\n')}\n请把剪贴板内容发给开发者确认用哪个`, 20000);
    }

    function dumpStorageDiagnostics(entries) {
        const lines = entries.map(e =>
            `[${e.store}] ${e.key} = ${e.value.slice(0, 60)}${e.value.length > 60 ? '…' : ''} (len=${e.value.length})`);
        console.log('[红果导出] 网页存储共', entries.length, '条：', entries);
        try { GM_setClipboard(lines.join('\n') || '(localStorage / sessionStorage 都是空的)'); } catch (e) { /* 忽略 */ }
        const shown = lines.slice(0, 12).join('\n');
        toast(`❌ cookie 里没有 sessionid，网页存储里也没找到 32 位 hex 登录凭证。\n网页存储共 ${entries.length} 条（名单已复制到剪贴板）：\n${shown || '（空）'}\n——下一步请点页面上橙色的「诊断：捕获请求头」按钮，把它的输出发给开发者（登录 token 靠它定位）`, 20000);
    }

    function toast(msg, ms) {
        const el = document.createElement('div');
        el.textContent = msg;
        el.style.cssText = 'position:fixed;left:50%;top:16px;transform:translateX(-50%);' +
            'background:#1e2430;color:#fff;padding:10px 18px;border-radius:8px;' +
            'font:14px/1.4 sans-serif;z-index:2147483647;box-shadow:0 4px 16px rgba(0,0,0,.4);' +
            'white-space:pre-line;max-width:70vw;text-align:left';
        document.body.appendChild(el);
        setTimeout(() => el.remove(), ms || 3000);
    }

    // 网页存储读不到登录凭证时的关键诊断：把捕获到的 API 请求（query+headers+cookie 快照）导出
    function exportCaptured() {
        const fmt = r => {
            const lines = [`${r.method} ${r.url}`];
            const ps = Object.entries(r.params || {});
            if (ps.length) lines.push('  [query] ' + ps.map(([k, v]) => `${k}=${v}`).join('&'));
            const hs = Object.entries(r.headers || {});
            if (hs.length) lines.push('  [headers] ' + hs.map(([k, v]) => `${k}: ${v}`).join('\n  [headers] '));
            if (r.resp != null) lines.push(`  [resp ${r.respStatus}] ${r.resp}`);
            return lines.join('\n');
        };
        const text = `document.cookie 快照：${(document.cookie || '(空)').slice(0, 300)}\n\n` +
            (captured.map(fmt).join('\n---\n') || '(还没捕获到 API 请求——请先刷新页面、浏览几秒再点这个按钮)');
        console.log('[红果导出] 捕获的 API 请求：', captured);
        try { GM_setClipboard(text); } catch (e) { /* 忽略 */ }
        toast(`已捕获 ${captured.length} 条 API 请求（含 query 参数与请求头，值已截断脱敏），已复制到剪贴板。\n若为 0 条：先刷新红果页面、浏览几秒再点一次。\n请把剪贴板内容发给开发者——这是定位登录 token 的关键`, 20000);
    }

    // 页面右下角悬浮按钮（未登录页也能点，报错提示登录）；
    // 版本号印在按钮上，用户刷新页面即可确认脚本版本装对了没有
    function makeBtn(text, bg, bottom, onClick) {
        const b = document.createElement('button');
        b.textContent = text;
        b.style.cssText = 'position:fixed;right:16px;bottom:' + bottom + 'px;z-index:2147483647;' +
            'background:' + bg + ';color:#fff;border:none;border-radius:20px;padding:10px 16px;' +
            'font:14px sans-serif;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.35)';
        b.onclick = onClick;
        return b;
    }

    function mountButtons() {
        document.body.appendChild(makeBtn('导出红果登录（v1.6）', '#ff5f5f', 16, exportSession));
        document.body.appendChild(makeBtn('诊断：捕获请求头', '#e8962d', 64, exportCaptured));
    }
    if (document.body) mountButtons();
    else document.addEventListener('DOMContentLoaded', mountButtons);

    GM_registerMenuCommand('导出红果登录态（xml 文件 + 复制 sessionid）', exportSession);
    GM_registerMenuCommand('诊断：列出 cookie（定位为什么读不到 sessionid）', async () => {
        try {
            const r = await fetchCookies();
            dumpDiagnostics(r.cookies, r.via);
        } catch (e) {
            toast('❌ ' + e.message, 8000);
        }
    });
    GM_registerMenuCommand('诊断：列出网页存储（localStorage / sessionStorage）', () => {
        dumpStorageDiagnostics(scanStorage());
    });
    GM_registerMenuCommand('诊断：导出捕获的 API 请求头（找登录 token 在哪个 header）', exportCaptured);
})();
