// Anime1 —— FongMi / TVBox `__JS_SPIDER__` 格式（ESM）
//
// 原 js/anime1.js 是 drpy 规则（var rule = {..., 一级/二级/lazy}），FongMi 的 JS
// 引擎不认 drpy 约定，只认 export default { home, category, detail, play, search }。
// 这里按 FongMi 约定重写，接口流程与 drpy 版一致：
//   home/category : https://anime1.me/animelist.json（按 20 条分页）
//   search        : /?s=<kw>（第 2 页起 /page/N/?s=）
//   detail        : 剧集页 + ?cat=<分类> 汇总出全部集数
//   play          : 剧集页取 data-apireq → POST https://v.anime1.me/api 换 Cookie → 直链

const HOST = 'https://anime1.me';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Referer': HOST + '/' };
const PER = 20;

function get(url, headers) {
    const res = req(url, { headers: headers || HEADERS });
    return (res && res.content) || '';
}

function norm(t) {
    return ('' + t).split('&amp;').join('&').split('&#8211;').join('-').split('&#039;').join("'").trim();
}

// animelist.json: [[catId, title, ?, 更新信息, 状态], ...]
function rows() {
    const txt = get(HOST + '/animelist.json');
    try {
        const all = JSON.parse(txt);
        return Array.isArray(all) ? all : [];
    } catch (e) { return []; }
}

function toVods(all, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const start = (p - 1) * PER;
    const slice = all.slice(start, start + PER);
    const out = [];
    for (let i = 0; i < slice.length; i++) {
        const r = slice[i] || [];
        if (!r[0] || !r[1]) continue;
        const rm = [];
        if (r[3]) rm.push(r[3]);
        if (r[4]) rm.push(r[4]);
        if (r[2]) rm.push(r[2]);
        out.push({ vod_id: HOST + '/?cat=' + r[0], vod_name: '' + r[1], vod_pic: '', vod_remarks: rm.join(' ') });
    }
    return out;
}

function home() {
    const all = rows();
    return JSON.stringify({
        class: [{ type_id: 'new', type_name: '最新更新' }],
        filters: {},
        list: toVods(all, 1)
    });
}

function category(tid, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const all = rows();
    const list = toVods(all, p);
    return JSON.stringify({
        page: p, pagecount: Math.max(1, Math.ceil(all.length / PER)),
        limit: PER, total: all.length, list: list
    });
}

// 从 HTML 里抠出 <a href="HOST/<数字>">标题</a>
function grab(html, host) {
    const out = [];
    const seen = {};
    const parts = ('' + html).split('href="' + host + '/');
    for (let i = 1; i < parts.length; i++) {
        const seg = parts[i];
        const id = seg.split('"')[0];
        if (!id || !/^[0-9]+$/.test(id) || seen[id]) continue;
        const t = seg.split('>')[1];
        let title = t ? t.split('<')[0].trim() : '';
        if (!title) continue;
        seen[id] = 1;
        out.push({ id: id, title: norm(title) });
    }
    return out;
}

function search(key, quick, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const kw = encodeURIComponent(key);
    const url = p > 1 ? (HOST + '/page/' + p + '/?s=' + kw) : (HOST + '/?s=' + kw);
    const items = grab(get(url), HOST);
    const list = items.map(it => ({ vod_id: HOST + '/' + it.id, vod_name: it.title, vod_pic: '', vod_remarks: '' }));
    return JSON.stringify({ page: p, pagecount: list.length ? p + 1 : p, limit: 20, total: 999999, list: list });
}

function titleOf(html) {
    const t1 = ('' + html).split('<title>');
    if (t1.length < 2) return '';
    return norm(t1[1].split('</title>')[0].split('&#8211;')[0].split(' - ')[0]);
}

function detail(id) {
    const inputUrl = '' + id;
    const h = get(inputUrl);
    const name = titleOf(h);
    let sh = h;
    let catid = '';
    if (inputUrl.indexOf('?cat=') > 0) {
        catid = inputUrl.split('?cat=')[1].split('&')[0];
    } else {
        const c = h.split('?cat=');
        if (c.length > 1) {
            catid = c[1].split('"')[0].split('&')[0];
            if (catid) sh = get(HOST + '/?cat=' + catid);
        }
    }
    const items = grab(sh, HOST);
    const eps = [];
    for (let i = 0; i < items.length; i++) {
        const t = items[i].title;
        let num = '';
        const lb = t.split('[');
        if (lb.length > 1) num = lb[1].split(']')[0];
        eps.push((num ? ('第' + num + '集') : ('第' + (eps.length + 1) + '集')) + '$' + HOST + '/' + items[i].id);
    }
    if (!eps.length) {
        const last = inputUrl.split('/').pop();
        if (/^[0-9]+$/.test(last)) eps.push((name || '正片') + '$' + inputUrl);
    }
    const vod = {
        vod_id: inputUrl, vod_name: name || 'Anime1', vod_pic: '', vod_content: '',
        vod_play_from: 'Anime1', vod_play_url: eps.join('#')
    };
    return JSON.stringify({ list: [vod] });
}

function pickCookie(headers) {
    // FongMi 的 res.headers 里 set-cookie 可能是数组或单串，统一处理
    let raw = '';
    if (headers) {
        for (const k in headers) {
            if (('' + k).toLowerCase() === 'set-cookie') {
                const v = headers[k];
                raw = Array.isArray(v) ? v.join(',') : ('' + v);
                break;
            }
        }
    }
    if (!raw) return '';
    const seg = ('' + raw).split(',');
    const keep = [];
    for (let i = 0; i < seg.length; i++) {
        const one = seg[i].replace(/^ +/, '');
        if (!one) continue;
        const low = one.toLowerCase();
        if (low.indexOf('path=') === 0 || low.indexOf('expires=') === 0 || low.indexOf('domain=') === 0
            || low.indexOf('max-age=') === 0 || low.indexOf('samesite=') === 0) continue;
        if (low === 'secure' || low === 'httponly') continue;
        if (one.indexOf('=') > 0) keep.push(one.split(';')[0].trim());
    }
    return keep.join('; ');
}

function play(flag, id) {
    const pageUrl = '' + id;
    const h = get(pageUrl);
    const a1 = h.split('data-apireq="');
    const apireq = a1.length > 1 ? a1[1].split('"')[0] : '';
    if (!apireq) return JSON.stringify({ parse: 0, url: '', header: HEADERS });
    const res = req('https://v.anime1.me/api', {
        method: 'POST',
        body: 'd=' + apireq,
        headers: {
            'User-Agent': UA,
            'Referer': pageUrl,
            'Content-Type': 'application/x-www-form-urlencoded'
        }
    });
    let ck = pickCookie(res && res.headers);
    let src = '';
    try {
        const d = JSON.parse((res && res.content) || '{}');
        const list = (d && d.s) || [];
        if (list.length && list[0] && list[0].src) src = list[0].src;
    } catch (e) { src = ''; }
    if (src.indexOf('//') === 0) src = 'https:' + src;
    if (!src) return JSON.stringify({ parse: 0, url: '', header: HEADERS });
    return JSON.stringify({
        parse: 0, url: src,
        header: { 'User-Agent': UA, 'Referer': HOST + '/', 'Cookie': ck }
    });
}

const spider = { init: () => {}, home: home, category: category, detail: detail, play: play, search: search };

export function __jsEvalReturn() { return spider; }
export default spider;
