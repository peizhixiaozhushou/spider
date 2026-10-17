var UASTR = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
var rule = {
  title: "最大资源",
  host: "https://api.zuidapi.com",
  homeUrl: '/api.php/provide/vod/?ac=list',
  searchUrl: '/api.php/provide/vod/?wd=**&pg=fypage',
  headers: {'User-Agent': UASTR},
  timeout: 15000,
  searchable: 2,
  quickSearch: 1,
  class_name: "动作片&喜剧片&爱情片&科幻片&恐怖片&剧情片&战争片&动画片&纪录片&国产剧&港剧&台剧&日剧&韩剧&欧美剧&泰剧&海外剧&国产动漫&日韩动漫&欧美动漫",
  class_url: "6&7&8&9&10&11&12&39&20&13&17&18&16&15&14&19&23&29&30&31",
  play_parse: true,
  lazy: `js:
input = { parse: 0, url: input, header: { 'User-Agent': rule.headers['User-Agent'] } };
`,
  一级: `js:
var api = rule.host + '/api.php/provide/vod/?ac=videolist&t=' + MY_CATE + '&pg=' + (MY_PAGE > 0 ? MY_PAGE : 1);
var txt = request(api, { headers: rule.headers });
var d = typeof txt === 'string' ? JSON.parse(txt) : txt;
var lst = (d && d.list) ? d.list : [];
var out = [];
for (var i = 0; i < lst.length; i++) {
  var v = lst[i];
  if (!v || !v.vod_id) continue;
  out.push({ vod_id: rule.host + '/api.php/provide/vod/?ac=detail&ids=' + v.vod_id, vod_name: v.vod_name, vod_pic: v.vod_pic, vod_remarks: v.vod_remarks });
}
VODS = out;
`,
  搜索: `js:
var api = rule.host + '/api.php/provide/vod/?wd=' + encodeURIComponent(KEY) + '&pg=' + (MY_PAGE > 0 ? MY_PAGE : 1);
var txt = request(api, { headers: rule.headers });
var d = typeof txt === 'string' ? JSON.parse(txt) : txt;
var lst = (d && d.list) ? d.list : [];
var out = [];
for (var i = 0; i < lst.length; i++) {
  var v = lst[i];
  if (!v || !v.vod_id) continue;
  out.push({ vod_id: rule.host + '/api.php/provide/vod/?ac=detail&ids=' + v.vod_id, vod_name: v.vod_name, vod_pic: v.vod_pic, vod_remarks: v.vod_remarks });
}
VODS = out;
`,
  二级: `js:
var txt = request(input, { headers: rule.headers });
var d = typeof txt === 'string' ? JSON.parse(txt) : txt;
var v = (d && d.list && d.list[0]) ? d.list[0] : {};
var froms = ('' + (v.vod_play_from || '')).split('$$$');
var urls = ('' + (v.vod_play_url || '')).split('$$$');
var outFrom = [], outUrl = [];
for (var i = 0; i < froms.length; i++) {
  var nm = froms[i];
  if (('' + nm).toLowerCase().indexOf('m3u8') < 0) continue;
  var eps = (urls[i] || '').split('#').filter(function (s) { return s && s.indexOf('.m3u8') > 0; });
  if (!eps.length) continue;
  outFrom.push(nm);
  outUrl.push(eps.join('#'));
}
if (!outFrom.length) { outFrom = froms; outUrl = urls; }
var content = v.vod_blurb || v.vod_content || '';
VOD = {
  vod_id: orId,
  vod_name: v.vod_name || '',
  vod_pic: v.vod_pic || '',
  vod_content: ('' + content).replace(/<[^>]*>/g, ''),
  type_name: v.vod_class || '',
  vod_year: '' + (v.vod_year || ''),
  vod_area: v.vod_area || '',
  vod_actor: v.vod_actor || '',
  vod_director: v.vod_director || '',
  vod_play_from: outFrom.join('$$$'),
  vod_play_url: outUrl.join('$$$')
};
`
};
