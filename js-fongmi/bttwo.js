// 比特 —— FongMi / TVBox `__JS_SPIDER__` 格式（ESM）
//
// 播放地址由站点 wasm（nbmovie_wasm_bg.wasm）算签名，FongMi 的 QuickJS 没有 WebAssembly，
// 所以这里把算法**完整复刻**成纯 JS（逆向结论，已用 wasm 做黑盒对拍 4/4 通过）：
//
//   k = base64( ulink 逐字节 XOR "nbmovie2024secretkey"（循环） )
//   s = HMAC_SHA256( key = vodsid, msg = "<dataid>:<t>:<vodsid>" ).hex[0:32]
//   播放 URL = /video/play?p=<dataid>&v=<vodsid>&q=1080&s=<s>&t=<t>&k=<k>
//
// 其中 t = Date.now()（毫秒）。q（画质）与 ulink 不参与 s。

const HOST = 'https://www.bttwo.life';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA };
const SECRET = 'nbmovie2024secretkey';
const Q = '1080';

// ── SHA-256 / HMAC（纯 JS，QuickJS 里没有 crypto）──────────────────────
const K256 = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];
const IV256 = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];

function rotr(x, n) { return ((x >>> n) | (x << (32 - n))) >>> 0; }

function sha256(bytes) {
    const bitLen = bytes.length * 8;
    const padded = [];
    for (let i = 0; i < bytes.length; i++) padded.push(bytes[i]);
    padded.push(0x80);
    while (padded.length % 64 !== 56) padded.push(0);
    const hi = Math.floor(bitLen / 4294967296), lo = bitLen >>> 0;
    for (let i = 3; i >= 0; i--) padded.push((hi >>> (i * 8)) & 0xff);
    for (let i = 3; i >= 0; i--) padded.push((lo >>> (i * 8)) & 0xff);

    let h = IV256.slice();
    const w = new Array(64);
    for (let off = 0; off < padded.length; off += 64) {
        for (let i = 0; i < 16; i++) {
            const j = off + i * 4;
            w[i] = ((padded[j] << 24) | (padded[j + 1] << 16) | (padded[j + 2] << 8) | padded[j + 3]) >>> 0;
        }
        for (let i = 16; i < 64; i++) {
            const s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
            const s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
            w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
        }
        let a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (let i = 0; i < 64; i++) {
            const S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
            const ch = ((e & f) ^ (~e & g)) >>> 0;
            const t1 = (hh + S1 + ch + K256[i] + w[i]) >>> 0;
            const S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
            const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
            const t2 = (S0 + maj) >>> 0;
            hh = g; g = f; f = e; e = (d + t1) >>> 0;
            d = c; c = b; b = a; a = (t1 + t2) >>> 0;
        }
        h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
        h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
    }
    const out = [];
    for (let i = 0; i < 8; i++) {
        out.push((h[i] >>> 24) & 0xff, (h[i] >>> 16) & 0xff, (h[i] >>> 8) & 0xff, h[i] & 0xff);
    }
    return out;
}

function hmacSha256Hex(keyStr, msgStr) {
    const key = []; for (let i = 0; i < keyStr.length; i++) key.push(keyStr.charCodeAt(i) & 0xff);
    const msg = []; for (let i = 0; i < msgStr.length; i++) msg.push(msgStr.charCodeAt(i) & 0xff);
    const k = key.length > 64 ? sha256(key) : key;
    const ipad = [], opad = [];
    for (let i = 0; i < 64; i++) {
        const b = i < k.length ? k[i] : 0;
        ipad.push(b ^ 0x36); opad.push(b ^ 0x5c);
    }
    const inner = sha256(ipad.concat(msg));
    const outer = sha256(opad.concat(inner));
    let hex = '';
    for (let i = 0; i < outer.length; i++) hex += ('0' + outer[i].toString(16)).slice(-2);
    return hex;
}

function b64(bytes) {
    const tbl = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let out = '';
    for (let i = 0; i < bytes.length; i += 3) {
        const b0 = bytes[i], b1 = i + 1 < bytes.length ? bytes[i + 1] : 0, b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
        out += tbl[b0 >> 2];
        out += tbl[((b0 & 3) << 4) | (b1 >> 4)];
        out += i + 1 < bytes.length ? tbl[((b1 & 15) << 2) | (b2 >> 6)] : '=';
        out += i + 2 < bytes.length ? tbl[b2 & 63] : '=';
    }
    return out;
}

function signParams(dataid, vodsid, ulink) {
    const t = '' + Date.now();
    const s = hmacSha256Hex(vodsid, dataid + ':' + t + ':' + vodsid).slice(0, 32);
    const xb = [];
    for (let i = 0; i < ulink.length; i++) {
        xb.push((ulink.charCodeAt(i) & 0xff) ^ (SECRET.charCodeAt(i % SECRET.length) & 0xff));
    }
    return { t: t, s: s, k: b64(xb) };
}

// ── 站点解析 ───────────────────────────────────────────────────────────
function get(url) {
    const res = req(url, { headers: HEADERS });
    return (res && res.content) || '';
}
function unesc(s) {
    if (!s) return '';
    return ('' + s).split('&amp;').join('&').split('&#39;').join("'").split('&quot;').join('"')
        .split('&lt;').join('<').split('&gt;').join('>');
}
function stripTags(s) { return ('' + s).split('<')[0].trim(); }

function parseList(html) {
    const out = [];
    const seen = {};
    const parts = ('' + html).split('data-vod-id="');
    for (let i = 1; i < parts.length; i++) {
        const seg = parts[i];
        const vid = seg.split('"')[0];
        const href = seg.split('href="/play/')[1];
        const sid = href ? href.split('"')[0] : '';
        const pic = seg.split('data-src="')[1];
        const alt = seg.split('alt="')[1];
        const name = alt ? unesc(alt.split('"')[0]) : '';
        if (!vid || !sid || !name || seen[sid]) continue;
        seen[sid] = 1;
        out.push({ vod_id: HOST + '/play/' + sid, vod_name: name, vod_pic: pic ? unesc(pic.split('"')[0]) : '', vod_remarks: '' });
    }
    return out;
}

const CLASSES = [['电影', '1'], ['电视剧', '2'], ['动漫', '3'], ['综艺', '4']];

function home() {
    const list = parseList(get(HOST + '/filter?classify=1&page=1'));
    return JSON.stringify({ class: CLASSES.map(c => ({ type_id: c[1], type_name: c[0] })), filters: {}, list: list });
}

function category(tid, pg, filter, extend) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    let cid = '' + (tid || '1');
    if (!cid || cid === '0') cid = '1';
    let q = 'classify=' + cid;
    const ex = extend || {};
    if (ex['类型']) q += '&types=' + ex['类型'];
    if (ex['地区']) q += '&areas=' + ex['地区'];
    if (ex['年份']) q += '&years=' + ex['年份'];
    if (ex['标签']) q += '&tags=' + ex['标签'];
    if (ex['排序']) q += '&sort_by=' + ex['排序'] + '&order=desc';
    q += '&page=' + p;
    const list = parseList(get(HOST + '/filter?' + q));
    return JSON.stringify({ page: p, pagecount: list.length ? p + 1 : p, limit: 24, total: 999999, list: list });
}

function search(key, quick, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const html = get(HOST + '/search?q=' + encodeURIComponent(key));
    const out = [];
    const seen = {};
    const parts = ('' + html).split('<a href="/play/');
    for (let i = 1; i < parts.length; i++) {
        const seg = parts[i].split('</a>')[0];
        const sid = seg.split('"')[0];
        const alt = seg.split('alt="')[1];
        const pic = seg.split('data-src="')[1];
        const name = alt ? unesc(alt.split('"')[0]) : '';
        if (!sid || !name || seen[sid]) continue;
        seen[sid] = 1;
        out.push({ vod_id: HOST + '/play/' + sid, vod_name: name, vod_pic: pic ? unesc(pic.split('"')[0]) : '', vod_remarks: '' });
    }
    return JSON.stringify({ page: p, pagecount: out.length ? p + 1 : p, limit: 24, total: 999999, list: out });
}

function detail(id) {
    const inputUrl = '' + id;
    const h = get(inputUrl);
    let name = '', pic = '', content = '', ulink = '';
    const n1 = h.split('<h1');
    if (n1.length > 1) { const n2 = n1[1].split('>'); name = stripTags(n2.slice(1).join('>')); }
    const p1 = h.split('og:image" content="'); if (p1.length > 1) pic = p1[1].split('"')[0];
    const c1 = h.split('name="description" content="'); if (c1.length > 1) content = c1[1].split('"')[0];
    const u1 = h.split('userlink:');
    if (u1.length > 1) ulink = u1[1].split("'")[1] || '';

    const names = [];
    const lm = h.split('lineName:');
    for (let i = 1; i < lm.length; i++) { const nm = lm[i].split("'")[1]; if (nm) names.push(nm); }

    const anchors = h.split('<a href="/play/');
    const lines = {};
    for (let i = 1; i < anchors.length; i++) {
        const seg = anchors[i].split('</a>')[0];
        if (seg.indexOf('data-line="') < 0) continue;
        const sid = seg.split('"')[0];
        const dl = seg.split('data-line="')[1]; const line = dl ? dl.split('"')[0] : '1';
        const de = seg.split('data-episode="')[1]; const ep = de ? de.split('"')[0] : '1';
        const di = seg.split('dataid="')[1]; const dataid = di ? di.split('"')[0] : '';
        if (!sid || !dataid) continue;
        if (!lines[line]) lines[line] = [];
        lines[line].push({ ep: ep, dataid: dataid, sid: sid });
    }
    const fromList = [], urlList = [];
    for (const ln in lines) {
        if (!Object.prototype.hasOwnProperty.call(lines, ln)) continue;
        const arr = lines[ln];
        const eps = [];
        for (let k = 0; k < arr.length; k++) {
            const it = arr[k];
            const label = arr.length > 1 ? ('第' + it.ep + '集') : '正片';
            eps.push(label + '$' + HOST + '/play/' + it.sid + '|' + it.dataid + '|' + ulink);
        }
        fromList.push(names[Number(ln) - 1] || ('线路' + ln));
        urlList.push(eps.join('#'));
    }
    const vod = {
        vod_id: inputUrl, vod_name: unesc(name), vod_pic: unesc(pic), vod_content: unesc(content),
        vod_play_from: fromList.join('$$$'), vod_play_url: urlList.join('$$$')
    };
    return JSON.stringify({ list: [vod] });
}

function play(flag, id) {
    const seg = ('' + id).split('|');
    const purl = seg[0] || '';
    let dataid = seg[1] || '';
    let ulink = seg[2] || '';
    let vodsid = purl.indexOf('/play/') > 0 ? purl.split('/play/')[1] : '';
    if (vodsid.indexOf('/') > 0) vodsid = vodsid.split('/')[0];
    if (vodsid.indexOf('?') > 0) vodsid = vodsid.split('?')[0];

    const fetchPlay = (ul) => {
        const sp = signParams(dataid, vodsid, ul || '');
        const url = HOST + '/video/play?p=' + encodeURIComponent(dataid) + '&v=' + encodeURIComponent(vodsid)
            + '&q=' + Q + '&s=' + sp.s + '&t=' + sp.t + '&k=' + sp.k;
        const res = req(url, { headers: { 'User-Agent': UA, 'Referer': HOST + '/' } });
        try { return JSON.parse((res && res.content) || '{}'); } catch (e) { return {}; }
    };
    let r = fetchPlay(ulink);
    if (!r || r.code !== 200) {
        const h2 = get(purl);
        const u1 = h2.split('userlink:');
        if (u1.length > 1) ulink = u1[1].split("'")[1] || '';
        r = fetchPlay(ulink);
    }
    const qs = (r && r.data && r.data.quality_urls) ? r.data.quality_urls : [];
    let url = '';
    for (let i = 0; i < qs.length; i++) { if (qs[i] && !qs[i].locked && qs[i].url) { url = qs[i].url; break; } }
    if (!url) { for (let j = 0; j < qs.length; j++) { if (qs[j] && qs[j].url) { url = qs[j].url; break; } } }
    return JSON.stringify({ parse: 0, url: url, header: { 'User-Agent': UA } });
}

const spider = { init: () => {}, home: home, category: category, detail: detail, play: play, search: search };

export function __jsEvalReturn() { return spider; }
export default spider;
