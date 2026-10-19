// 爱壹帆 —— FongMi / TVBox `__JS_SPIDER__` 格式（ESM）
//
// 原 js/iyf.js 是 drpy 规则（var rule = {..., 一级/二级/lazy}），
// drpy 是 TVBox 生态里由「影视仓」等 fork 自带引擎解释的约定，
// FongMi 的 JS 引擎只认 `export default { home, category, detail, play, search }`
// （见 app 内 assets/js/lib/spider.js），所以那份在 FongMi 上永远是 SpiderNull。
//
// 这里把同一套接口搬成 FongMi 约定的模块：纯 HTTP + MD5 签名，不依赖任何宿主扩展。

const HOST = 'https://www.iyf.tv';
const API = 'https://m10.iyf.tv';
const SEARCH_HOST = 'https://rankv21.iyf.tv';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Referer': HOST + '/', 'Accept': 'application/json, text/plain, */*' };
const PRIVS = ['version001', 'vers1on001', 'vers1on00i', 'bersion001', 'vcrsion001', 'versi0n001', 'versio_001', 'version0o1'];

// 站点对 /v3、/api 请求要求 vv=<md5>&pub=<ts> 签名，priv 按时间戳轮换。
// 原规则用的是运行时自带的 md5()，FongMi 的 JS 环境没有这个函数，这里内联一份。
function md5(str) {
    const bytes = [];
    const u8 = unescape(encodeURIComponent('' + str));
    for (let i = 0; i < u8.length; i++) bytes.push(u8.charCodeAt(i) & 0xFF);
    const bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    const hi = Math.floor(bitLen / 4294967296), lo = bitLen >>> 0;
    for (let i = 0; i < 4; i++) bytes.push((lo >>> (8 * i)) & 0xFF);
    for (let i = 0; i < 4; i++) bytes.push((hi >>> (8 * i)) & 0xFF);

    let a0 = 0x67452301, b0 = 0xEFCDAB89, c0 = 0x98BADCFE, d0 = 0x10325476;
    const S = [7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
               5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
               4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
               6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];
    const K = [0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
               0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
               0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
               0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
               0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
               0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
               0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
               0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391];

    for (let off = 0; off < bytes.length; off += 64) {
        const M = [];
        for (let i = 0; i < 16; i++) {
            M[i] = bytes[off + i * 4] | (bytes[off + i * 4 + 1] << 8) | (bytes[off + i * 4 + 2] << 16) | (bytes[off + i * 4 + 3] << 24);
        }
        let A = a0, B = b0, C = c0, D = d0;
        for (let i = 0; i < 64; i++) {
            let F, g;
            if (i < 16) { F = (B & C) | (~B & D); g = i; }
            else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
            else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
            else { F = C ^ (B | ~D); g = (7 * i) % 16; }
            F = (F + A + K[i] + M[g]) | 0;
            A = D; D = C; C = B;
            B = (B + ((F << S[i]) | (F >>> (32 - S[i])))) | 0;
        }
        a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }
    const hex = v => { let s = ''; for (let i = 0; i < 4; i++) s += ((v >>> (i * 8)) & 0xFF).toString(16).padStart(2, '0'); return s; };
    return hex(a0) + hex(b0) + hex(c0) + hex(d0);
}

function sign(qs) {
    const ts = '' + Date.now();
    const priv = PRIVS[Number(ts) % PRIVS.length];
    return 'vv=' + md5(ts + '&' + ('' + qs).toLowerCase() + '&' + priv) + '&pub=' + ts;
}

function apiGet(path, qs) {
    const url = API + path + '?' + qs + '&' + sign(qs);
    const res = req(url, { headers: HEADERS });
    try { return JSON.parse((res && res.content) || '{}'); } catch (e) { console.log('[iyf] json err ' + e); return {}; }
}

function infoOf(d) {
    const arr = (((d || {}).data || {}).info) || [];
    return arr[0] || {};
}

const CLASSES = [
    ['电影', '0,1,3'], ['电视剧', '0,1,4'], ['综艺', '0,1,5'],
    ['动漫', '0,1,6'], ['纪录片', '0,1,7'], ['短剧', '0,1,8'], ['体育', '0,1,9']
];

function toVods(list) {
    const out = [];
    for (let i = 0; i < list.length; i++) {
        const v = list[i] || {};
        if (!v.key) continue;
        const rm = [];
        if (v.vipResource) rm.push(v.vipResource);
        if (v.score) rm.push(v.score);
        out.push({
            vod_id: HOST + '/play/' + v.key + '?cid=' + (v.videoClassID || ''),
            vod_name: v.title || '',
            vod_pic: v.image || v.imgPath || '',
            vod_remarks: rm.join(' ')
        });
    }
    return out;
}

function listUrl(cid, pg) {
    return '/api/list/Search?cinema=1&page=' + (pg > 0 ? pg : 1) + '&size=36&orderby=0&desc=1&cid='
        + cid + '&isserial=-1&isIndex=-1&isfree=-1&isVertical=-1&v=1';
}

function home() {
    const cls = CLASSES.map(c => ({ type_id: c[1], type_name: c[0] }));
    const d = apiGet('/api/list/Search', listUrl('0,1,3', 1).split('?')[1]);
    return JSON.stringify({ class: cls, filters: {}, list: toVods(infoOf(d).result || []) });
}

function category(tid, pg) {
    const d = apiGet('/api/list/Search', listUrl(tid || '0,1,3', Number(pg) || 1).split('?')[1]);
    const list = toVods(infoOf(d).result || []);
    const page = Number(pg) || 1;
    return JSON.stringify({ page: page, pagecount: list.length ? page + 1 : page, limit: 36, total: 999999, list: list });
}

function detail(id) {
    const s = '' + id;
    const key = s.indexOf('/play/') > 0 ? s.split('/play/')[1].split('?')[0] : s;
    const cid = s.indexOf('cid=') > 0 ? s.split('cid=')[1].split('&')[0] : '';
    const d = apiGet('/v3/video/detail', 'cinema=1&device=1&player=CkPlayer&tech=HLS&lang=cns&v=1&id=' + key + '&region=SG');
    const v = infoOf(d);
    const ep = apiGet('/v3/video/languagesplaylist', 'cinema=1&vid=' + key + '&lsk=1&taxis=1&cid=' + cid + '&v=1');
    const langs = ((ep.data || {}).info) || [];
    const froms = [], urls = [];
    for (let i = 0; i < langs.length; i++) {
        const pl = (langs[i] && langs[i].playList) || [];
        if (!pl.length) continue;
        const eps = [];
        for (let j = 0; j < pl.length; j++) {
            const p = pl[j] || {};
            if (!p.key) continue;
            eps.push('第' + p.name + '集$' + HOST + '/e/' + p.key + '?cid=' + cid);
        }
        if (!eps.length) continue;
        froms.push('爱壹帆');
        urls.push(eps.join('#'));
    }
    const vod = {
        vod_id: s, vod_name: v.title || '', vod_pic: v.imgPath || '',
        vod_content: v.contxt || '', type_name: v.videoType || v.channel || '',
        vod_year: '' + (v.post_Year || ''), vod_area: v.regional || '',
        vod_actor: v.starring || '', vod_director: v.directed || '',
        vod_play_from: froms.join('$$$'), vod_play_url: urls.join('$$$')
    };
    return JSON.stringify({ list: [vod] });
}

function search(key, quick, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const url = SEARCH_HOST + '/v3/list/briefsearch?tags=' + encodeURIComponent(key)
        + '&orderby=4&page=' + p + '&size=36&desc=1&isserial=-1&v=1';
    const res = req(url, { headers: HEADERS });
    let d = {};
    try { d = JSON.parse((res && res.content) || '{}'); } catch (e) { d = {}; }
    const info = ((d.data || {}).info) || [];
    const list = (info[0] && info[0].result) || [];
    const out = [];
    for (let i = 0; i < list.length; i++) {
        const v = list[i] || {};
        let k = '' + (v.contxt || '');
        if (!k || k.length > 24 || k.indexOf(' ') > 0) k = '';
        if (!k || !v.videoClassID) continue;
        const rm = [];
        if (v.vipResource) rm.push(v.vipResource);
        if (v.score) rm.push(v.score);
        out.push({
            vod_id: HOST + '/play/' + k + '?cid=' + v.videoClassID,
            vod_name: v.title || '', vod_pic: v.imgPath || '', vod_remarks: rm.join(' ')
        });
    }
    return JSON.stringify({ page: p, pagecount: out.length ? p + 1 : p, limit: 36, total: 999999, list: out });
}

function play(flag, id) {
    const s = '' + id;
    // id 形如 https://www.iyf.tv/e/<key>?cid=...，先切 ? 再切 &，否则会把 query 带进去
    let ep = s;
    if (s.indexOf('/e/') > 0) { ep = s.split('/e/')[1].split('?')[0].split('&')[0]; }
    const d = apiGet('/v3/video/play', 'cinema=1&id=' + ep + '&a=0&lang=none&usersign=1&region=SG&device=1&isMasterSupport=1&v=1');
    const info = infoOf(d);
    const list = info.flvPathList || [];
    let pick = '';
    for (let i = 0; i < list.length; i++) {
        if (list[i] && list[i].isHls && list[i].result) { pick = list[i].result; break; }
    }
    if (!pick) {
        for (let j = 0; j < list.length; j++) {
            if (list[j] && list[j].result) { pick = list[j].result; break; }
        }
    }
    if (!pick) return JSON.stringify({ parse: 0, url: '', header: HEADERS });
    return JSON.stringify({ parse: 0, url: pick, header: { 'User-Agent': UA, 'Referer': HOST + '/' } });
}

const spider = { init: () => {}, home: home, category: category, detail: detail, play: play, search: search };

// 两种入口都给：FongMi 的 spider.js 先看 __jsEvalReturn，再看 default
export function __jsEvalReturn() { return spider; }
export default spider;
