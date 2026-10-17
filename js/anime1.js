var rule = {
  title: 'Anime1',
  host: 'https://anime1.me',
  homeUrl: '/',
  searchUrl: '/?s=**',
  searchable: 2,
  quickSearch: 1,
  class_name: '最新更新',
  class_url: 'new',
  headers: { 'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36', 'Referer': 'https://anime1.me/' },
  timeout: 25000,
  play_parse: true,
  lazy: `js:
var h = getHtml(input);
console.log('[anime1] play len=' + ('' + h).length);
var a1 = h.split('data-apireq="');
var apireq = a1.length > 1 ? a1[1].split('"')[0] : '';
var pick = '';
var ck = '';
if (apireq) {
  try {
    var rc = reqCookie('https://v.anime1.me/api', { method: 'POST', body: 'd=' + apireq, headers: { 'User-Agent': rule.headers['User-Agent'], 'Referer': input, 'Content-Type': 'application/x-www-form-urlencoded' } }, true);
    var raw = ((rc && rc.cookie) || '').split(',').join(';');
    var seg = raw.split(';');
    var keep = [];
    for (var i = 0; i < seg.length; i++) {
      var one = seg[i].replace(/^ +/, '');
      if (!one) continue;
      var low = one.toLowerCase();
      if (low.indexOf('path=') === 0 || low.indexOf('expires=') === 0 || low.indexOf('domain=') === 0 || low.indexOf('max-age=') === 0 || low.indexOf('samesite=') === 0) continue;
      if (low === 'secure' || low === 'httponly') continue;
      if (one.indexOf('=') > 0) keep.push(one);
    }
    ck = keep.join('; ');
    var d = null;
    try { d = JSON.parse((rc && rc.html) || '{}'); } catch (e) { d = null; }
    var list = (d && d.s) || [];
    if (list.length && list[0] && list[0].src) pick = list[0].src;
    if (pick && pick.indexOf('//') === 0) pick = 'https:' + pick;
    console.log('[anime1] cookie=' + ck.slice(0, 40) + ' pick=' + pick.slice(0, 60));
  } catch (e) { console.log('[anime1] err=' + (e && e.message)); }
}
// 直链播放：实测 muan.v.anime1.me 只校验 Cookie/Referer，不需要本地 9978 代理。
// 原实现硬编码 http://127.0.0.1:9978/api/v1/proxy/stream?...，那只在装了 fmplayer 的
// 本机成立，换到电视盒子/别人机器上必然播不了。
var finalUrl = pick ? pick.replace(/^\/\//, 'https://') : '';
input = { parse: 0, url: finalUrl, header: { 'User-Agent': rule.headers['User-Agent'], 'Referer': 'https://anime1.me/', 'Cookie': ck } };
`,
  一级: `js:
var pg = MY_PAGE > 0 ? MY_PAGE : 1;
var txt = request('https://anime1.me/animelist.json', { headers: rule.headers });
var all = typeof txt === 'string' ? JSON.parse(txt) : txt;
if (!all || !all.length) { VODS = []; } else {
  var per = 20;
  var start = (pg - 1) * per;
  var rows = all.slice(start, start + per);
  var out = [];
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i];
    if (!r || !r[0] || !r[1]) continue;
    var rm = [];
    if (r[3]) rm.push(r[3]);
    if (r[4]) rm.push(r[4]);
    if (r[2]) rm.push(r[2]);
    out.push({ vod_id: rule.host + '/?cat=' + r[0], vod_name: r[1], vod_pic: '', vod_remarks: rm.join(' ') });
  }
  VODS = out;
}
`,
  搜索: `js:
var pg = MY_PAGE > 0 ? MY_PAGE : 1;
var url = pg > 1 ? rule.host + '/page/' + pg + '/?s=' + encodeURIComponent(KEY) : rule.host + '/?s=' + encodeURIComponent(KEY);
var h = getHtml(url);
var out = [];
var seen = {};
var parts = h.split('href="' + rule.host + '/');
for (var i = 1; i < parts.length; i++) {
  var seg = parts[i];
  var id = seg.split('"')[0];
  if (!id || !/^[0-9]+$/.test(id) || seen[id]) continue;
  var t = seg.split('>')[1]; t = t ? t.split('<')[0].trim() : '';
  if (!t) continue;
  seen[id] = 1;
  out.push({ vod_id: rule.host + '/' + id, vod_name: t.split('&#8211;').join('-'), vod_pic: '', vod_remarks: '' });
}
VODS = out;
`,
  二级: `js:
var inputUrl = '' + input;
var h = getHtml(inputUrl);
var name = '';
var t1 = h.split('<title>');
if (t1.length > 1) { name = t1[1].split('</title>')[0]; name = name.split('&#8211;')[0].split(' - ')[0].trim(); }
var sh = h;
var catid = '';
if (inputUrl.indexOf('?cat=') > 0) {
  catid = inputUrl.split('?cat=')[1].split('&')[0];
} else {
  var c = h.split('?cat=');
  if (c.length > 1) { catid = c[1].split('"')[0].split('&')[0]; if (catid) sh = getHtml(rule.host + '/?cat=' + catid); }
}
var eps = [];
var sp = sh.split('href="' + rule.host + '/');
var seen = {};
for (var i = 1; i < sp.length; i++) {
  var seg = sp[i];
  var id = seg.split('"')[0];
  if (!id || !/^[0-9]+$/.test(id) || seen[id]) continue;
  var t = seg.split('>')[1]; t = t ? t.split('<')[0].trim() : '';
  if (!t) continue;
  seen[id] = 1;
  var num = '';
  var lb = t.split('[');
  if (lb.length > 1) num = lb[1].split(']')[0];
  eps.push((num ? ('第' + num + '集') : ('第' + eps.length + '集')) + '$' + rule.host + '/' + id);
}
if (!eps.length && /^[0-9]+$/.test(inputUrl.split('/').pop())) {
  eps.push(name + '$' + inputUrl);
}
VOD = {
  vod_id: orId,
  vod_name: name,
  vod_pic: '',
  vod_content: '',
  vod_play_from: 'Anime1',
  vod_play_url: eps.join('#')
};
`
};
