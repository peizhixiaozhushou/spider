// AGE动漫 —— FongMi / TVBox `__JS_SPIDER__` 格式（ESM）
//
// 原 js/agedm.js 是 drpy 规则，FongMi 的 JS 引擎不认（只认 export default {...}）。
// 这里按 FongMi 约定重写，流程与原规则一致：
//   home/category : HTML 列表，链接形如 /detail/<id>
//   detail        : 详情页解析标题/封面/简介，按 playlist-source 分组出线路与剧集
//   play          : 详情页里 vip/?url=<token> → 交给宿主解析（parse:1）

const HOST = 'https://www.agedm.io';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Referer': HOST + '/' };

function get(url) {
    const res = req(url, { headers: HEADERS });
    return (res && res.content) || '';
}

function unesc(s) {
    if (!s) return '';
    return ('' + s).split('&amp;').join('&').split('&#39;').join("'").split('&quot;').join('"')
        .split('&lt;').join('<').split('&gt;').join('>');
}

function classList() {
    return [
        { type_id: 'catalog', type_name: '全部动漫' },
        { type_id: 'update', type_name: '一周更新' },
        { type_id: 'rank', type_name: '排行榜' }
    ];
}

function listUrl(tid, pg) {
    const cid = ('' + tid) === 'update' || ('' + tid) === 'rank' ? ('' + tid) : 'catalog';
    const p = Number(pg) > 0 ? Number(pg) : 1;
    if (cid === 'catalog') return HOST + '/catalog/all-all-all-all-all-time-' + p;
    return HOST + '/' + cid;
}

function parseList(html) {
    const parts = ('' + html).split('/detail/');
    const out = [];
    const seen = {};
    for (let i = 1; i < parts.length; i++) {
        const seg = parts[i];
        const vid = seg.split('"')[0];
        if (!vid || seen[vid]) continue;
        let t = seg.split('title="')[1];
        t = t ? t.split('"')[0] : '';
        if (!t) { const tx = seg.split('>'); t = tx.length > 1 ? tx[1].split('<')[0].trim() : ''; }
        if (!t) { t = seg.split('alt="')[1]; t = t ? t.split('"')[0] : ''; }
        if (!t) continue;
        let img = seg.split('data-original="')[1]; img = img ? img.split('"')[0] : '';
        if (!img) { img = seg.split('src="')[1]; img = img ? img.split('"')[0] : ''; }
        const st = seg.split('video_play_status');
        let stt = '';
        if (st.length > 1) { stt = st[1].split('>')[1]; stt = stt ? stt.split('<')[0].trim() : ''; }
        seen[vid] = 1;
        out.push({ vod_id: HOST + '/detail/' + vid, vod_name: unesc(t), vod_pic: unesc(img), vod_remarks: stt });
    }
    return out;
}

function home() {
    const list = parseList(get(listUrl('catalog', 1)));
    return JSON.stringify({ class: classList(), filters: {}, list: list });
}

function category(tid, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    const list = parseList(get(listUrl(tid, p)));
    return JSON.stringify({ page: p, pagecount: list.length ? p + 1 : p, limit: 24, total: 999999, list: list });
}

function search(key, quick, pg) {
    const p = Number(pg) > 0 ? Number(pg) : 1;
    let url = HOST + '/search?query=' + encodeURIComponent(key);
    if (p > 1) url += '&page=' + p;
    const list = parseList(get(url));
    return JSON.stringify({ page: p, pagecount: list.length ? p + 1 : p, limit: 24, total: 999999, list: list });
}

function detail(id) {
    const inputUrl = '' + id;
    const h = get(inputUrl);
    const vid = inputUrl.indexOf('/detail/') > 0 ? inputUrl.split('/detail/')[1].split('/')[0].split('?')[0] : '';

    let name = '';
    const n1 = h.split('<h2');
    if (n1.length > 1) {
        const n2 = n1[1].split('>');
        name = n2.slice(1).join('>').split('<')[0].trim();
    }
    let pic = '';
    const p1 = h.split('class="video_thumbs');
    if (p1.length > 1) {
        const p2 = p1[1].split('data-original="');
        if (p2.length > 1) pic = p2[1].split('"')[0];
    }
    if (!pic) {
        const p3 = h.split('<img');
        if (p3.length > 1) {
            const p4 = p3[1].split('src="');
            if (p4.length > 1) pic = p4[1].split('"')[0];
        }
    }
    let content = '';
    const c1 = h.split('video_detail_desc');
    if (c1.length > 1) {
        const c2 = c1[1].split('>');
        content = c2.slice(1).join('>').split('</div>')[0].split('<br />').join(' ').split('<').join(' ').trim();
    }

    const names = [];
    const bt = h.split('data-bs-target="#playlist-source-');
    for (let i = 1; i < bt.length; i++) {
        const seg = bt[i].split('</button>')[0];
        const nm = seg.split('>');
        const label = nm[nm.length - 1].replace(/ +/g, '');
        if (label) names.push(label);
    }

    const panes = h.split('id="playlist-source-');
    const fromList = [], urlList = [];
    for (let j = 1; j < panes.length; j++) {
        const eps = panes[j].split('video_detail_spisode_link');
        const list = [];
        for (let k = 1; k < eps.length; k++) {
            let label = eps[k].split('>')[1];
            label = label ? label.split('<')[0].trim() : ('第' + k + '集');
            list.push(label + '$' + HOST + '/play/' + vid + '/' + j + '/' + k);
        }
        if (!list.length) continue;
        fromList.push(names[j - 1] || ('线路' + j));
        urlList.push(list.join('#'));
    }

    const vod = {
        vod_id: inputUrl, vod_name: unesc(name), vod_pic: unesc(pic), vod_content: content,
        vod_play_from: fromList.join('$$$'), vod_play_url: urlList.join('$$$')
    };
    return JSON.stringify({ list: [vod] });
}

function play(flag, id) {
    const pageUrl = '' + id;
    const h = get(pageUrl);
    const a = h.split('vip/?url=');
    const token = a.length > 1 ? a[1].split('"')[0] : '';
    if (!token) return JSON.stringify({ parse: 0, url: '', header: HEADERS });
    // parse:1 表示交给宿主配置的解析器（config 里是 wuzhoupai）取直链
    return JSON.stringify({ parse: 1, url: unesc(token), header: { 'User-Agent': UA, 'Referer': HOST + '/' } });
}

const spider = { init: () => {}, home: home, category: category, detail: detail, play: play, search: search };

export function __jsEvalReturn() { return spider; }
export default spider;
