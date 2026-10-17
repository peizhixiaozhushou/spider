var UASTR = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
var rule = {
  title: '爱壹帆',
  host: 'https://www.iyf.tv',
  homeUrl: '/',
  searchUrl: '/search/**',
  detailUrl: '',
  searchable: 2,
  quickSearch: 1,
  class_name: '电影&电视剧&综艺&动漫&纪录片&短剧&体育',
  class_url: '0,1,3&0,1,4&0,1,5&0,1,6&0,1,7&0,1,8&0,1,9',
  headers: { 'User-Agent': UASTR, 'Referer': 'https://www.iyf.tv/', 'Accept': 'application/json, text/plain, */*' },
  timeout: 20000,
  play_parse: true,
  lazy: `js:
var ep = ('' + input).split('/e/')[1] ? ('' + input).split('/e/')[1].split('?')[0] : ('' + input);
var qs = 'cinema=1&id=' + ep + '&a=0&lang=none&usersign=1&region=SG&device=1&isMasterSupport=1&v=1';
var d = self.__iyf.get('/v3/video/play', qs);
var info = (((d.data || {}).info) || [{}])[0] || {};
var list = info.flvPathList || [];
var pick = '';
for (var i = 0; i < list.length; i++) { if (list[i] && list[i].isHls && list[i].result) { pick = list[i].result; break; } }
if (!pick) { for (var j = 0; j < list.length; j++) { if (list[j] && list[j].result) { pick = list[j].result; break; } } }
input = { parse: 0, url: pick, header: { 'User-Agent': rule.headers['User-Agent'], 'Referer': 'https://www.iyf.tv/' } };
`,
  一级: `js:
var cid = MY_CATE ? ('' + MY_CATE) : '0,1,3';
var pg = MY_PAGE > 0 ? MY_PAGE : 1;
var qs = 'cinema=1&page=' + pg + '&size=36&orderby=0&desc=1&cid=' + cid + '&isserial=-1&isIndex=-1&isfree=-1&isVertical=-1&v=1';
var d = self.__iyf.get('/api/list/Search', qs);
var info = (d.data && d.data.info) || [];
var list = (info[0] && info[0].result) || [];
var out = [];
for (var i = 0; i < list.length; i++) {
  var v = list[i];
  if (!v || !v.key) continue;
  var rm = [];
  if (v.vipResource) rm.push(v.vipResource);
  if (v.score) rm.push(v.score);
  out.push({ vod_id: 'https://www.iyf.tv/play/' + v.key + '?cid=' + (v.videoClassID || ''), vod_name: v.title, vod_pic: v.image || v.imgPath || '', vod_remarks: rm.join(' ') });
}
VODS = out;
`,
  搜索: `js:
var pg = MY_PAGE > 0 ? MY_PAGE : 1;
var kw = encodeURIComponent(KEY);
var url = 'https://rankv21.iyf.tv/v3/list/briefsearch?tags=' + kw + '&orderby=4&page=' + pg + '&size=36&desc=1&isserial=-1&v=1';
var d = self.__iyf.json(request(url, { headers: rule.headers }));
var info = (d.data && d.data.info) || [];
var list = (info[0] && info[0].result) || [];
var out = [];
for (var i = 0; i < list.length; i++) {
  var v = list[i];
  var key = ('' + (v.contxt || ''));
  if (!key || key.length > 24 || key.indexOf(' ') > 0) key = '';
  if (!key || !v.videoClassID) continue;
  var rm = [];
  if (v.vipResource) rm.push(v.vipResource);
  if (v.score) rm.push(v.score);
  out.push({ vod_id: 'https://www.iyf.tv/play/' + key + '?cid=' + v.videoClassID, vod_name: v.title, vod_pic: v.imgPath || '', vod_remarks: rm.join(' ') });
}
VODS = out;
`,
  二级: `js:
var key = ('' + input).split('/play/')[1] ? ('' + input).split('/play/')[1].split('?')[0] : '';
var cid = ('' + input).indexOf('cid=') > 0 ? ('' + input).split('cid=')[1].split('&')[0] : '';
var d = self.__iyf.get('/v3/video/detail', 'cinema=1&device=1&player=CkPlayer&tech=HLS&lang=cns&v=1&id=' + key + '&region=SG');
var v = (((d.data || {}).info) || [{}])[0] || {};
var ep = self.__iyf.get('/v3/video/languagesplaylist', 'cinema=1&vid=' + key + '&lsk=1&taxis=1&cid=' + cid + '&v=1');
var langs = ((ep.data || {}).info) || [];
var fromList = [];
var urlList = [];
for (var i = 0; i < langs.length; i++) {
  var pl = (langs[i] && langs[i].playList) || [];
  if (!pl.length) continue;
  fromList.push('爱壹帆');
  var eps = [];
  for (var j = 0; j < pl.length; j++) {
    var p = pl[j];
    if (!p || !p.key) continue;
    eps.push('第' + p.name + '集$https://www.iyf.tv/e/' + p.key + '?cid=' + cid);
  }
  urlList.push(eps.join('#'));
}
VOD = {
  vod_id: orId,
  vod_name: v.title || '',
  vod_pic: v.imgPath || '',
  vod_content: v.contxt || '',
  type_name: (v.videoType || v.channel || ''),
  vod_year: '' + (v.post_Year || ''),
  vod_area: v.regional || '',
  vod_actor: v.starring || '',
  vod_director: v.directed || '',
  vod_play_from: fromList.join('$$$'),
  vod_play_url: urlList.join('$$$')
};
`
};
self.__iyf = {
  privs: ['version001','vers1on001','vers1on00i','bersion001','vcrsion001','versi0n001','versio_001','version0o1'],
  sign: function (qs) {
    var ts = '' + Date.now();
    var priv = this.privs[(Number(ts) % this.privs.length)];
    return 'vv=' + md5(ts + '&' + ('' + qs).toLowerCase() + '&' + priv) + '&pub=' + ts;
  },
  json: function (txt) {
    if (typeof txt !== 'string') return txt || {};
    try { return JSON.parse(txt); } catch (e) { return {}; }
  },
  get: function (path, qs) {
    var url = 'https://m10.iyf.tv' + path + '?' + qs + '&' + this.sign(qs);
    return this.json(request(url, { headers: rule.headers }));
  }
};
