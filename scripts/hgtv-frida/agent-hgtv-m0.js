// HGTV 1.3.8.13 wire 请求抓取 agent（2026-11-23）。
// 该包 okhttp 已混淆（okhttp3 只剩 4 个文件），不能 hook RealCall；改 hook 全 API 执行入口
// H0.m0.p(m0, String url, K3, K1.z, boolean, boolean, boolean, Map, v1.l, int)：
// K3 = 签名后的最终请求（a=url, b=headers(LinkedHashMap 保序), c=body(可能已 gzip), d=method）。
// p() 收到「200 空 body」时会 domain failover（换 host 重签名后递归调 p），递归调用同样被本 hook 捕获。
// 类名/字段名用 dex 真实名（jadx 输出的 C0186m0 / f1838a 是显示用重命名）。
// 字段一律走反射：K3.a 既是实例字段又是静态方法名，frida 17 的 `x.a.value` 取不到这类同名字段。
import Java from 'frida-java-bridge';
globalThis.Java = Java;

const RESP_HEAD = 400;      // 响应体头部留存量：够看 code/message/数据形态，不整包传

function field(clsName, name) {
    const f = Java.use(clsName).class.getDeclaredField(name);
    f.setAccessible(true);
    return f;
}

function listToJs(list) {
    const out = [];
    if (list === null || list === undefined) return null;
    const arr = Java.cast(list, Java.use('java.util.Collection')).toArray();
    for (let i = 0; i < arr.length; i++) out.push(String(arr[i]));
    return out;
}

let K3_url, K3_headers, K3_body, K3_method, JBase64, JString;

function dumpK3(k3) {
    if (k3 === null || k3 === undefined) return null;
    const url = K3_url.get(k3);
    const headers = K3_headers.get(k3);
    const headersOut = [];
    if (headers !== null) {
        const map = Java.cast(headers, Java.use('java.util.Map'));
        // LinkedHashMap.keySet() 迭代序即插入序
        const keys = Java.cast(map.keySet(), Java.use('java.util.Collection')).toArray();
        for (let i = 0; i < keys.length; i++) {
            const k = keys[i];
            headersOut.push([String(k), String(map.get(k))]);
        }
    }
    const body = K3_body.get(k3);
    let bodyB64 = null;
    let bodyLen = 0;
    if (body !== null) {
        const bytes = Java.cast(body, Java.use('[B'));
        bodyLen = bytes.length;
        // 显式指定重载：从反射拿到的数组经 cast 后 frida 无法自行匹配重载
        bodyB64 = String(JBase64.encodeToString.overload('[B', 'int').call(JBase64, bytes, 2));   // NO_WRAP
    }
    return {
        url: String(Java.cast(url, JString)),
        method: String(Java.cast(K3_method.get(k3), JString)),
        headers: headersOut,
        body_b64: bodyB64,
        body_len: bodyLen,
    };
}

Java.perform(function () {
    JBase64 = Java.use('android.util.Base64');
    JString = Java.use('java.lang.String');
    K3_url = field('H0.K3', 'a');
    K3_headers = field('H0.K3', 'b');
    K3_body = field('H0.K3', 'c');
    K3_method = field('H0.K3', 'd');

    const M0 = Java.use('H0.m0');
    M0.p.overloads.forEach(function (ov) {
        ov.implementation = function () {
            const k3 = arguments[2];
            let rec = null;
            try { rec = dumpK3(k3); } catch (e) { send({ type: 'hook-error', where: 'dumpK3', err: String(e) }); }
            const t0 = Date.now();
            const ret = ov.apply(this, arguments);
            if (ret === undefined) throw new Error('m0.p returned undefined — original call lost');
            if (rec !== null) {
                const s = ret === null ? null : String(ret);
                rec.type = 'req';
                rec.ts = t0;
                rec.costMs = Date.now() - t0;
                rec.respLen = s === null ? -1 : s.length;
                rec.respHead = s === null ? null : s.substring(0, RESP_HEAD);
                send(rec);
            }
            return ret;
        };
    });

    // HGTV 内部日志：N1.d("[API] empty-body host=… path=…")、N1.b("domain failover accepted host=…")
    // 自身是 no-op，但入口能拿到日志行——直接看到它自己判定的空响应与换域名过程。
    const N1 = Java.use('H0.N1');
    ['b', 'c', 'd'].forEach(function (name) {
        N1[name].overloads.forEach(function (ov) {
            ov.implementation = function () {
                try { send({ type: 'log', level: name, line: String(arguments[0]), ts: Date.now() }); } catch (e) { }
                return ov.apply(this, arguments);
            };
        });
    });

    // 域名池：V3.g() = api host 候选（记忆的可用 host 排首位）；V3.f(host) = failover 接受某 host
    const V3 = Java.use('H0.V3');
    V3.f.overloads.forEach(function (ov) {
        ov.implementation = function () {
            try { send({ type: 'failover-accept', host: String(arguments[0]), ts: Date.now() }); } catch (e) { }
            return ov.apply(this, arguments);
        };
    });

    // 1.3.8.13 启动 30s 后在 hgtv-startup-deferred 线程延迟初始化友盟 UMCrash；带 frida（jvmti）的
    // 进程里 UC crashsdk 的 native 初始化直接 SIGSEGV 杀进程（2026-11-23 16:47 实证）。置空绕过，
    // 只跳过崩溃回调注册，不影响业务请求。
    try {
        const UMCrash = Java.use('com.umeng.umcrash.UMCrash');
        UMCrash.init.overloads.forEach(function (ov) {
            ov.implementation = function () {
                send({ type: 'log', level: 'umeng', line: 'UMCrash.init skipped (args=' + arguments.length + ')', ts: Date.now() });
            };
        });
        send({ type: 'log', level: 'umeng', line: 'UMCrash.init hooked overloads=' + UMCrash.init.overloads.length, ts: Date.now() });
    } catch (e) {
        send({ type: 'hook-error', where: 'UMCrash.init', err: String(e) });
    }

    send({ type: 'ready' });
});

// 借用 HGTV 自己的签名器 H0.L3.h(base, query, body, method, profile, gzip) 给任意请求签名：
// 产出的 K3 是服务端认可的「金标准」，供 PC 侧重放，用来把我方 medusa 与设备/IP 等变量拆开。
rpc.exports = {
    sign: function (base, queryJson, bodyB64, method, useLive) {
        const out = {};
        Java.performNow(function () {
            try {
                // useLive：用进程里真实的签名器实例（H0.m0.n 字段）；否则新建一个空状态实例
                let inst = null;
                if (useLive) {
                    const live = [null];
                    Java.choose('H0.m0', {
                        onMatch: function (m0) {
                            if (live[0] === null) {
                                try { const l = field('H0.m0', 'n').get(m0); if (l !== null) live[0] = l; } catch (e) { }
                            }
                        },
                        onComplete: function () { }
                    });
                    inst = live[0];
                    out.liveFound = inst !== null;
                }
                if (inst === null) inst = Java.use('H0.L3').$new();
                const q = Java.use('java.util.LinkedHashMap').$new();
                const obj = JSON.parse(queryJson);
                for (const k in obj) q.put(String(k), String(obj[k]));
                const bytes = (bodyB64 === null || bodyB64 === undefined || bodyB64 === '')
                    ? null : Java.use('android.util.Base64').decode(bodyB64, 2);
                const k3 = Java.cast(inst, Java.use('H0.L3')).h
                    .overload('java.lang.String', 'java.util.LinkedHashMap', '[B', 'java.lang.String', 'java.util.Map', 'boolean')
                    .call(inst, base, q, bytes, method, null, true);
                const rec = dumpK3(k3);
                out.url = rec.url; out.method = rec.method; out.headers = rec.headers;
                out.body_b64 = rec.body_b64; out.body_len = rec.body_len;
            } catch (e) { out.err = String(e) + ' | ' + (e.stack || ''); }
        });
        return out;
    },

    state: function () {
        const out = {};
        Java.performNow(function () {
            try {
                const V3 = Java.use('H0.V3');
                const x3 = field('H0.V3', 'd').get(null);
                out.tncLoaded = x3 !== null;
                if (x3 !== null) {
                    out.apiHosts = listToJs(field('H0.X3', 'g').get(x3));
                    const lg = field('H0.X3', 'j').get(x3);
                    out.lastGoodHost = lg === null ? null : String(lg);
                    out.tncHosts = listToJs(field('H0.X3', 'f').get(x3));
                }
                out.pool = listToJs(V3.g());
            } catch (e) { out.err = String(e); }
        });
        return out;
    },
};
