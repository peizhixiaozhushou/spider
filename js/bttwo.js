var UASTR = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';
var rule = {
  title: '比特',
  host: 'https://www.bttwo.life',
  homeUrl: '/filter',
  url: '/filter?classify=fyclass&page=fypage',
  searchUrl: '/search?q=**',
  searchable: 2,
  quickSearch: 1,
  class_name: '电影&电视剧&动漫&综艺',
  class_url: '1&2&3&4',
  headers: {'User-Agent': UASTR},
  timeout: 20000,
  play_parse: true,
  lazy: `js:
var seg = ('' + input).split('|');
var purl = seg[0] || '';
var dataid = seg[1] || '';
var ulink = seg[2] || '';
var vodsid = purl.split('/play/')[1] || '';
if (vodsid.indexOf('/') > 0) vodsid = vodsid.split('/')[0];
function nbJson(txt) { if (typeof txt === 'string') { try { return JSON.parse(txt); } catch (e) { return {}; } } return txt || {}; }
function nbLoad() {
  if (rule.__4kapi) return rule.__4kapi;
  var js = rule.__4kjs || '/static/wasm/nbmovie_wasm.js';
  var bg = rule.__4kbg || '/static/wasm/nbmovie_wasm_bg.wasm';
  var glue = '' + request(rule.host + js, { headers: rule.headers });
  glue = glue.split('export function ').join('function ');
  var cut = glue.lastIndexOf('export {');
  if (cut >= 0) glue = glue.slice(0, cut);
  glue = glue.split('import.meta.url').join('"' + rule.host + js + '"');
  var api = (new Function(glue + 'return { build_play_url: build_play_url, initSync: initSync };'))();
  var xhr = new XMLHttpRequest();
  xhr.open('GET', rule.host + bg, false);
  xhr.responseType = 'arraybuffer';
  xhr.send();
  api.initSync({ module: xhr.response });
  rule.__4kapi = api;
  return api;
}
function nbPlay(ul) {
  var api = nbLoad();
  var rel = api.build_play_url('' + dataid, '' + vodsid, '1080', '' + ul);
  if (('' + rel).indexOf('http') !== 0) rel = rule.host + (('' + rel).charAt(0) === '/' ? rel : '/' + rel);
  return nbJson(request(rel, { headers: rule.headers }));
}
var r = nbPlay(ulink);
if (!r || r.code !== 200) {
  var h2 = getHtml(purl);
  var u1 = h2.split('userlink:');
  if (u1.length > 1) ulink = u1[1].split(String.fromCharCode(39))[1] || '';
  r = nbPlay(ulink);
}
var url = '';
var qs = (r && r.data && r.data.quality_urls) ? r.data.quality_urls : [];
for (var i = 0; i < qs.length; i++) { if (qs[i] && !qs[i].locked && qs[i].url) { url = qs[i].url; break; } }
if (!url) { for (var j = 0; j < qs.length; j++) { if (qs[j] && qs[j].url) { url = qs[j].url; break; } } }
input = { parse: 0, url: url, header: { 'User-Agent': rule.headers['User-Agent'] } };
`,
  一级: `js:
var unesc = function(s){ if(!s) return ''; return ('' + s).split('&amp;').join('&').split('&#39;').join(String.fromCharCode(39)).split('&quot;').join(String.fromCharCode(34)).split('&lt;').join('<').split('&gt;').join('>'); };
var norm = function(v){ if (v === undefined || v === null) return ''; v = '' + v; return v === '全部' ? '' : v; };
var cid = norm(MY_CATE);
if (!cid || cid === '0') cid = '1';
var q = 'classify=' + cid;
var t = norm(MY_FL && MY_FL['类型']); if (t) q += '&types=' + t;
var a = norm(MY_FL && MY_FL['地区']); if (a) q += '&areas=' + a;
var y = norm(MY_FL && MY_FL['年份']); if (y) q += '&years=' + y;
var g = norm(MY_FL && MY_FL['标签']); if (g) q += '&tags=' + g;
var s = norm(MY_FL && MY_FL['排序']); if (s) q += '&sort_by=' + s + '&order=desc';
q += '&page=' + (MY_PAGE > 0 ? MY_PAGE : 1);
var h = getHtml(rule.host + '/filter?' + q);
var parts = h.split('data-vod-id="');
var out = [];
for (var i = 1; i < parts.length; i++) {
  var seg = parts[i];
  var vid = seg.split('"')[0];
  var href = seg.split('href="/play/')[1]; href = href ? href.split('"')[0] : '';
  var pic = seg.split('data-src="')[1]; pic = pic ? pic.split('"')[0] : '';
  var alt = seg.split('alt="')[1]; alt = alt ? alt.split('"')[0] : '';
  if (!vid || !href) continue;
  out.push({ vod_id: rule.host + '/play/' + href, vod_name: unesc(alt), vod_pic: unesc(pic), vod_remarks: '' });
}
VODS = out;
`,
  搜索: `js:
var unesc = function(s){ if(!s) return ''; return ('' + s).split('&amp;').join('&').split('&#39;').join(String.fromCharCode(39)).split('&quot;').join(String.fromCharCode(34)).split('&lt;').join('<').split('&gt;').join('>'); };
var h = getHtml(rule.host + '/search?q=' + encodeURIComponent(KEY));
var parts = h.split('<a href="/play/');
var seen = {};
var out = [];
for (var i = 1; i < parts.length; i++) {
  var seg = parts[i].split('</a>')[0];
  var href = seg.split('"')[0];
  var alt = seg.split('alt="')[1]; alt = alt ? alt.split('"')[0] : '';
  var pic = seg.split('data-src="')[1]; pic = pic ? pic.split('"')[0] : '';
  if (!href || !alt || seen[href]) continue;
  seen[href] = 1;
  out.push({ vod_id: rule.host + '/play/' + href, vod_name: unesc(alt), vod_pic: unesc(pic), vod_remarks: '' });
}
VODS = out;
`,
  二级: `js:
var unesc = function(s){ if(!s) return ''; return ('' + s).split('&amp;').join('&').split('&#39;').join(String.fromCharCode(39)).split('&quot;').join(String.fromCharCode(34)).split('&lt;').join('<').split('&gt;').join('>'); };
var h = getHtml(input);
var name = '', pic = '', content = '', ulink = '';
var n1 = h.split('<h1');
if (n1.length > 1) { var n2 = n1[1].split('>'); name = n2.slice(1).join('>').split('<')[0].trim(); }
var p1 = h.split('og:image" content="'); if (p1.length > 1) pic = p1[1].split('"')[0];
var c1 = h.split('name="description" content="'); if (c1.length > 1) content = c1[1].split('"')[0];
var u1 = h.split('userlink:');
if (u1.length > 1) ulink = u1[1].split(String.fromCharCode(39))[1] || '';
var j1 = h.split('data-js="'); if (j1.length > 1) rule.__4kjs = j1[1].split('"')[0] || '';
var b1 = h.split('data-bg="'); if (b1.length > 1) rule.__4kbg = b1[1].split('"')[0] || '';
var names = [];
var lm = h.split('lineName:');
for (var i = 1; i < lm.length; i++) { var nm = lm[i].split(String.fromCharCode(39))[1]; if (nm) names.push(nm); }
var anchors = h.split('<a href="/play/');
var lines = {};
for (var i = 1; i < anchors.length; i++) {
  var seg = anchors[i].split('</a>')[0];
  if (seg.indexOf('data-line="') < 0) continue;
  var href = seg.split('"')[0];
  var dl = seg.split('data-line="')[1]; dl = dl ? dl.split('"')[0] : '1';
  var de = seg.split('data-episode="')[1]; de = de ? de.split('"')[0] : '1';
  var di = seg.split('dataid="')[1]; di = di ? di.split('"')[0] : '';
  if (!href || !di) continue;
  if (!lines[dl]) lines[dl] = [];
  lines[dl].push({ ep: de, dataid: di, sid: href });
}
var fromList = [], urlList = [];
for (var ln in lines) {
  if (!lines.hasOwnProperty(ln)) continue;
  var list = lines[ln];
  fromList.push(names[ln - 1] || ('线路' + ln));
  var eps = [];
  for (var k = 0; k < list.length; k++) {
    var it = list[k];
    var label = list.length > 1 ? ('第' + it.ep + '集') : '正片';
    eps.push(label + '$' + rule.host + '/play/' + it.sid + '|' + it.dataid + '|' + ulink);
  }
  urlList.push(eps.join('#'));
}
VOD = {
  vod_id: orId,
  vod_name: name,
  vod_pic: unesc(pic),
  vod_content: content,
  vod_play_from: fromList.join('$$$'),
  vod_play_url: urlList.join('$$$')
};
`,
  filter: {
  "1": [
    {key:'类型', name:'类型', value:[{n:"全部", v:""}, {n:"剧情", v:"1"}, {n:"悬疑", v:"2"}, {n:"恐怖", v:"3"}, {n:"惊悚", v:"4"}, {n:"喜剧", v:"5"}, {n:"爱情", v:"6"}, {n:"犯罪", v:"9"}, {n:"动作", v:"10"}, {n:"动画", v:"11"}, {n:"奇幻", v:"12"}, {n:"音乐", v:"13"}, {n:"科幻", v:"14"}, {n:"历史", v:"15"}, {n:"战争", v:"16"}, {n:"冒险", v:"18"}, {n:"家庭", v:"19"}, {n:"纪录", v:"20"}, {n:"西部", v:"23"}, {n:"电视电影", v:"24"}, {n:"情色", v:"25"}, {n:"真人秀", v:"26"}, {n:"古装", v:"27"}, {n:"传记", v:"28"}, {n:"同性", v:"29"}, {n:"运动", v:"30"}, {n:"武侠", v:"31"}, {n:"歌舞", v:"32"}, {n:"纪录片", v:"33"}, {n:"灾难", v:"34"}, {n:"短片", v:"35"}]},
    {key:'地区', name:'地区', value:[{n:"全部", v:""}, {n:"美国", v:"5"}, {n:"法国", v:"6"}, {n:"中国", v:"7"}, {n:"unknown", v:"9"}, {n:"日本", v:"11"}, {n:"韩国", v:"12"}, {n:"中国香港", v:"14"}, {n:"俄罗斯", v:"16"}, {n:"波兰", v:"17"}, {n:"德国", v:"18"}, {n:"意大利", v:"19"}, {n:"中国台湾", v:"21"}, {n:"澳大利亚", v:"22"}, {n:"西班牙", v:"24"}, {n:"英国", v:"30"}, {n:"加拿大", v:"32"}, {n:"泰国", v:"33"}, {n:"印度", v:"34"}, {n:"丹麦", v:"41"}, {n:"中国大陆", v:"52"}, {n:"马来西亚", v:"65"}, {n:"菲律宾", v:"74"}, {n:"其他", v:"78"}, {n:"瑞典", v:"79"}, {n:"挪威", v:"80"}, {n:"阿根廷", v:"81"}, {n:"冰岛", v:"82"}, {n:"保加利亚", v:"83"}, {n:"爱尔兰", v:"84"}, {n:"墨西哥", v:"86"}, {n:"比利时", v:"87"}, {n:"瑞士", v:"88"}, {n:"克罗地亚", v:"89"}, {n:"Philippines", v:"90"}, {n:"马耳他", v:"91"}, {n:"匈牙利", v:"92"}, {n:"西德", v:"93"}, {n:"新西兰", v:"94"}, {n:"哥伦比亚", v:"95"}, {n:"巴西", v:"96"}, {n:"印度尼西亚", v:"97"}, {n:"南非", v:"98"}]},
    {key:'年份', name:'年份', value:[{n:"全部", v:""}, {n:"2027", v:"78"}, {n:"2026", v:"1"}, {n:"2025", v:"3"}, {n:"2024", v:"4"}, {n:"2023", v:"56"}, {n:"2022", v:"13"}, {n:"2021", v:"2"}, {n:"2020", v:"6"}, {n:"2019", v:"8"}, {n:"2018", v:"9"}, {n:"2017", v:"12"}, {n:"2016", v:"11"}, {n:"2015", v:"14"}, {n:"2014", v:"15"}, {n:"2013", v:"22"}, {n:"2012", v:"10"}, {n:"2011", v:"17"}, {n:"2010", v:"25"}, {n:"2009", v:"20"}, {n:"2008", v:"23"}, {n:"2007", v:"30"}, {n:"2006", v:"31"}, {n:"2005", v:"7"}, {n:"2004", v:"24"}, {n:"2003", v:"28"}, {n:"2002", v:"19"}, {n:"2001", v:"29"}, {n:"2000", v:"43"}, {n:"1999", v:"45"}, {n:"1998", v:"33"}, {n:"1997", v:"34"}, {n:"1996", v:"37"}, {n:"1995", v:"21"}, {n:"1994", v:"27"}, {n:"1993", v:"26"}, {n:"1992", v:"35"}, {n:"1991", v:"18"}, {n:"1990", v:"42"}, {n:"1989", v:"44"}, {n:"1988", v:"60"}, {n:"1987", v:"73"}, {n:"1986", v:"32"}, {n:"1985", v:"40"}, {n:"1984", v:"63"}, {n:"1983", v:"59"}, {n:"1982", v:"36"}, {n:"1981", v:"5"}, {n:"1980", v:"39"}, {n:"1979", v:"61"}, {n:"1978", v:"66"}, {n:"1977", v:"48"}, {n:"1976", v:"38"}, {n:"1975", v:"57"}, {n:"1974", v:"62"}, {n:"1973", v:"53"}, {n:"1972", v:"54"}, {n:"1971", v:"51"}, {n:"1969", v:"41"}, {n:"1968", v:"77"}, {n:"1966", v:"76"}, {n:"1965", v:"58"}, {n:"1964", v:"79"}, {n:"1963", v:"46"}, {n:"1962", v:"65"}, {n:"1961", v:"71"}, {n:"1960", v:"75"}, {n:"1959", v:"16"}, {n:"1958", v:"67"}, {n:"1957", v:"50"}, {n:"1956", v:"49"}, {n:"1955", v:"47"}, {n:"1954", v:"68"}, {n:"1952", v:"72"}, {n:"1949", v:"70"}, {n:"1948", v:"64"}, {n:"1938", v:"52"}, {n:"1931", v:"55"}, {n:"1925", v:"69"}, {n:"1921", v:"74"}]},
    {key:'标签', name:'标签', value:[{n:"全部", v:""}, {n:"4k", v:"1"}, {n:"院线", v:"36"}, {n:"TC", v:"37"}]},
    {key:'排序', name:'排序', value:[{n:"默认", v:""}, {n:"最新上映", v:"update_time"}, {n:"最受欢迎", v:"hits"}, {n:"评分最高", v:"score"}]}
  ],
  "2": [
    {key:'类型', name:'类型', value:[{n:"全部", v:""}, {n:"剧情", v:"1"}, {n:"悬疑", v:"2"}, {n:"恐怖", v:"3"}, {n:"惊悚", v:"4"}, {n:"喜剧", v:"5"}, {n:"爱情", v:"6"}, {n:"犯罪", v:"9"}, {n:"动作", v:"10"}, {n:"动画", v:"11"}, {n:"奇幻", v:"12"}, {n:"音乐", v:"13"}, {n:"科幻", v:"14"}, {n:"历史", v:"15"}, {n:"战争", v:"16"}, {n:"冒险", v:"18"}, {n:"家庭", v:"19"}, {n:"纪录", v:"20"}, {n:"西部", v:"23"}, {n:"电视电影", v:"24"}, {n:"情色", v:"25"}, {n:"真人秀", v:"26"}, {n:"古装", v:"27"}, {n:"传记", v:"28"}, {n:"同性", v:"29"}, {n:"运动", v:"30"}, {n:"武侠", v:"31"}, {n:"歌舞", v:"32"}, {n:"纪录片", v:"33"}, {n:"灾难", v:"34"}, {n:"短片", v:"35"}]},
    {key:'地区', name:'地区', value:[{n:"全部", v:""}, {n:"美国", v:"5"}, {n:"法国", v:"6"}, {n:"中国", v:"7"}, {n:"unknown", v:"9"}, {n:"日本", v:"11"}, {n:"韩国", v:"12"}, {n:"中国香港", v:"14"}, {n:"俄罗斯", v:"16"}, {n:"波兰", v:"17"}, {n:"德国", v:"18"}, {n:"意大利", v:"19"}, {n:"中国台湾", v:"21"}, {n:"澳大利亚", v:"22"}, {n:"西班牙", v:"24"}, {n:"英国", v:"30"}, {n:"加拿大", v:"32"}, {n:"泰国", v:"33"}, {n:"印度", v:"34"}, {n:"丹麦", v:"41"}, {n:"中国大陆", v:"52"}, {n:"马来西亚", v:"65"}, {n:"菲律宾", v:"74"}, {n:"其他", v:"78"}, {n:"瑞典", v:"79"}, {n:"挪威", v:"80"}, {n:"阿根廷", v:"81"}, {n:"冰岛", v:"82"}, {n:"保加利亚", v:"83"}, {n:"爱尔兰", v:"84"}, {n:"墨西哥", v:"86"}, {n:"比利时", v:"87"}, {n:"瑞士", v:"88"}, {n:"克罗地亚", v:"89"}, {n:"Philippines", v:"90"}, {n:"马耳他", v:"91"}, {n:"匈牙利", v:"92"}, {n:"西德", v:"93"}, {n:"新西兰", v:"94"}, {n:"哥伦比亚", v:"95"}, {n:"巴西", v:"96"}, {n:"印度尼西亚", v:"97"}, {n:"南非", v:"98"}]},
    {key:'年份', name:'年份', value:[{n:"全部", v:""}, {n:"2027", v:"78"}, {n:"2026", v:"1"}, {n:"2025", v:"3"}, {n:"2024", v:"4"}, {n:"2023", v:"56"}, {n:"2022", v:"13"}, {n:"2021", v:"2"}, {n:"2020", v:"6"}, {n:"2019", v:"8"}, {n:"2018", v:"9"}, {n:"2017", v:"12"}, {n:"2016", v:"11"}, {n:"2015", v:"14"}, {n:"2014", v:"15"}, {n:"2013", v:"22"}, {n:"2012", v:"10"}, {n:"2011", v:"17"}, {n:"2010", v:"25"}, {n:"2009", v:"20"}, {n:"2008", v:"23"}, {n:"2007", v:"30"}, {n:"2006", v:"31"}, {n:"2005", v:"7"}, {n:"2004", v:"24"}, {n:"2003", v:"28"}, {n:"2002", v:"19"}, {n:"2001", v:"29"}, {n:"2000", v:"43"}, {n:"1999", v:"45"}, {n:"1998", v:"33"}, {n:"1997", v:"34"}, {n:"1996", v:"37"}, {n:"1995", v:"21"}, {n:"1994", v:"27"}, {n:"1993", v:"26"}, {n:"1992", v:"35"}, {n:"1991", v:"18"}, {n:"1990", v:"42"}, {n:"1989", v:"44"}, {n:"1988", v:"60"}, {n:"1987", v:"73"}, {n:"1986", v:"32"}, {n:"1985", v:"40"}, {n:"1984", v:"63"}, {n:"1983", v:"59"}, {n:"1982", v:"36"}, {n:"1981", v:"5"}, {n:"1980", v:"39"}, {n:"1979", v:"61"}, {n:"1978", v:"66"}, {n:"1977", v:"48"}, {n:"1976", v:"38"}, {n:"1975", v:"57"}, {n:"1974", v:"62"}, {n:"1973", v:"53"}, {n:"1972", v:"54"}, {n:"1971", v:"51"}, {n:"1969", v:"41"}, {n:"1968", v:"77"}, {n:"1966", v:"76"}, {n:"1965", v:"58"}, {n:"1964", v:"79"}, {n:"1963", v:"46"}, {n:"1962", v:"65"}, {n:"1961", v:"71"}, {n:"1960", v:"75"}, {n:"1959", v:"16"}, {n:"1958", v:"67"}, {n:"1957", v:"50"}, {n:"1956", v:"49"}, {n:"1955", v:"47"}, {n:"1954", v:"68"}, {n:"1952", v:"72"}, {n:"1949", v:"70"}, {n:"1948", v:"64"}, {n:"1938", v:"52"}, {n:"1931", v:"55"}, {n:"1925", v:"69"}, {n:"1921", v:"74"}]},
    {key:'标签', name:'标签', value:[{n:"全部", v:""}, {n:"4k", v:"1"}, {n:"院线", v:"36"}, {n:"TC", v:"37"}]},
    {key:'排序', name:'排序', value:[{n:"默认", v:""}, {n:"最新上映", v:"update_time"}, {n:"最受欢迎", v:"hits"}, {n:"评分最高", v:"score"}]}
  ],
  "3": [
    {key:'类型', name:'类型', value:[{n:"全部", v:""}, {n:"剧情", v:"1"}, {n:"悬疑", v:"2"}, {n:"恐怖", v:"3"}, {n:"惊悚", v:"4"}, {n:"喜剧", v:"5"}, {n:"爱情", v:"6"}, {n:"犯罪", v:"9"}, {n:"动作", v:"10"}, {n:"动画", v:"11"}, {n:"奇幻", v:"12"}, {n:"音乐", v:"13"}, {n:"科幻", v:"14"}, {n:"历史", v:"15"}, {n:"战争", v:"16"}, {n:"冒险", v:"18"}, {n:"家庭", v:"19"}, {n:"纪录", v:"20"}, {n:"西部", v:"23"}, {n:"电视电影", v:"24"}, {n:"情色", v:"25"}, {n:"真人秀", v:"26"}, {n:"古装", v:"27"}, {n:"传记", v:"28"}, {n:"同性", v:"29"}, {n:"运动", v:"30"}, {n:"武侠", v:"31"}, {n:"歌舞", v:"32"}, {n:"纪录片", v:"33"}, {n:"灾难", v:"34"}, {n:"短片", v:"35"}]},
    {key:'地区', name:'地区', value:[{n:"全部", v:""}, {n:"美国", v:"5"}, {n:"法国", v:"6"}, {n:"中国", v:"7"}, {n:"unknown", v:"9"}, {n:"日本", v:"11"}, {n:"韩国", v:"12"}, {n:"中国香港", v:"14"}, {n:"俄罗斯", v:"16"}, {n:"波兰", v:"17"}, {n:"德国", v:"18"}, {n:"意大利", v:"19"}, {n:"中国台湾", v:"21"}, {n:"澳大利亚", v:"22"}, {n:"西班牙", v:"24"}, {n:"英国", v:"30"}, {n:"加拿大", v:"32"}, {n:"泰国", v:"33"}, {n:"印度", v:"34"}, {n:"丹麦", v:"41"}, {n:"中国大陆", v:"52"}, {n:"马来西亚", v:"65"}, {n:"菲律宾", v:"74"}, {n:"其他", v:"78"}, {n:"瑞典", v:"79"}, {n:"挪威", v:"80"}, {n:"阿根廷", v:"81"}, {n:"冰岛", v:"82"}, {n:"保加利亚", v:"83"}, {n:"爱尔兰", v:"84"}, {n:"墨西哥", v:"86"}, {n:"比利时", v:"87"}, {n:"瑞士", v:"88"}, {n:"克罗地亚", v:"89"}, {n:"Philippines", v:"90"}, {n:"马耳他", v:"91"}, {n:"匈牙利", v:"92"}, {n:"西德", v:"93"}, {n:"新西兰", v:"94"}, {n:"哥伦比亚", v:"95"}, {n:"巴西", v:"96"}, {n:"印度尼西亚", v:"97"}, {n:"南非", v:"98"}]},
    {key:'年份', name:'年份', value:[{n:"全部", v:""}, {n:"2027", v:"78"}, {n:"2026", v:"1"}, {n:"2025", v:"3"}, {n:"2024", v:"4"}, {n:"2023", v:"56"}, {n:"2022", v:"13"}, {n:"2021", v:"2"}, {n:"2020", v:"6"}, {n:"2019", v:"8"}, {n:"2018", v:"9"}, {n:"2017", v:"12"}, {n:"2016", v:"11"}, {n:"2015", v:"14"}, {n:"2014", v:"15"}, {n:"2013", v:"22"}, {n:"2012", v:"10"}, {n:"2011", v:"17"}, {n:"2010", v:"25"}, {n:"2009", v:"20"}, {n:"2008", v:"23"}, {n:"2007", v:"30"}, {n:"2006", v:"31"}, {n:"2005", v:"7"}, {n:"2004", v:"24"}, {n:"2003", v:"28"}, {n:"2002", v:"19"}, {n:"2001", v:"29"}, {n:"2000", v:"43"}, {n:"1999", v:"45"}, {n:"1998", v:"33"}, {n:"1997", v:"34"}, {n:"1996", v:"37"}, {n:"1995", v:"21"}, {n:"1994", v:"27"}, {n:"1993", v:"26"}, {n:"1992", v:"35"}, {n:"1991", v:"18"}, {n:"1990", v:"42"}, {n:"1989", v:"44"}, {n:"1988", v:"60"}, {n:"1987", v:"73"}, {n:"1986", v:"32"}, {n:"1985", v:"40"}, {n:"1984", v:"63"}, {n:"1983", v:"59"}, {n:"1982", v:"36"}, {n:"1981", v:"5"}, {n:"1980", v:"39"}, {n:"1979", v:"61"}, {n:"1978", v:"66"}, {n:"1977", v:"48"}, {n:"1976", v:"38"}, {n:"1975", v:"57"}, {n:"1974", v:"62"}, {n:"1973", v:"53"}, {n:"1972", v:"54"}, {n:"1971", v:"51"}, {n:"1969", v:"41"}, {n:"1968", v:"77"}, {n:"1966", v:"76"}, {n:"1965", v:"58"}, {n:"1964", v:"79"}, {n:"1963", v:"46"}, {n:"1962", v:"65"}, {n:"1961", v:"71"}, {n:"1960", v:"75"}, {n:"1959", v:"16"}, {n:"1958", v:"67"}, {n:"1957", v:"50"}, {n:"1956", v:"49"}, {n:"1955", v:"47"}, {n:"1954", v:"68"}, {n:"1952", v:"72"}, {n:"1949", v:"70"}, {n:"1948", v:"64"}, {n:"1938", v:"52"}, {n:"1931", v:"55"}, {n:"1925", v:"69"}, {n:"1921", v:"74"}]},
    {key:'标签', name:'标签', value:[{n:"全部", v:""}, {n:"4k", v:"1"}, {n:"院线", v:"36"}, {n:"TC", v:"37"}]},
    {key:'排序', name:'排序', value:[{n:"默认", v:""}, {n:"最新上映", v:"update_time"}, {n:"最受欢迎", v:"hits"}, {n:"评分最高", v:"score"}]}
  ],
  "4": [
    {key:'类型', name:'类型', value:[{n:"全部", v:""}, {n:"剧情", v:"1"}, {n:"悬疑", v:"2"}, {n:"恐怖", v:"3"}, {n:"惊悚", v:"4"}, {n:"喜剧", v:"5"}, {n:"爱情", v:"6"}, {n:"犯罪", v:"9"}, {n:"动作", v:"10"}, {n:"动画", v:"11"}, {n:"奇幻", v:"12"}, {n:"音乐", v:"13"}, {n:"科幻", v:"14"}, {n:"历史", v:"15"}, {n:"战争", v:"16"}, {n:"冒险", v:"18"}, {n:"家庭", v:"19"}, {n:"纪录", v:"20"}, {n:"西部", v:"23"}, {n:"电视电影", v:"24"}, {n:"情色", v:"25"}, {n:"真人秀", v:"26"}, {n:"古装", v:"27"}, {n:"传记", v:"28"}, {n:"同性", v:"29"}, {n:"运动", v:"30"}, {n:"武侠", v:"31"}, {n:"歌舞", v:"32"}, {n:"纪录片", v:"33"}, {n:"灾难", v:"34"}, {n:"短片", v:"35"}]},
    {key:'地区', name:'地区', value:[{n:"全部", v:""}, {n:"美国", v:"5"}, {n:"法国", v:"6"}, {n:"中国", v:"7"}, {n:"unknown", v:"9"}, {n:"日本", v:"11"}, {n:"韩国", v:"12"}, {n:"中国香港", v:"14"}, {n:"俄罗斯", v:"16"}, {n:"波兰", v:"17"}, {n:"德国", v:"18"}, {n:"意大利", v:"19"}, {n:"中国台湾", v:"21"}, {n:"澳大利亚", v:"22"}, {n:"西班牙", v:"24"}, {n:"英国", v:"30"}, {n:"加拿大", v:"32"}, {n:"泰国", v:"33"}, {n:"印度", v:"34"}, {n:"丹麦", v:"41"}, {n:"中国大陆", v:"52"}, {n:"马来西亚", v:"65"}, {n:"菲律宾", v:"74"}, {n:"其他", v:"78"}, {n:"瑞典", v:"79"}, {n:"挪威", v:"80"}, {n:"阿根廷", v:"81"}, {n:"冰岛", v:"82"}, {n:"保加利亚", v:"83"}, {n:"爱尔兰", v:"84"}, {n:"墨西哥", v:"86"}, {n:"比利时", v:"87"}, {n:"瑞士", v:"88"}, {n:"克罗地亚", v:"89"}, {n:"Philippines", v:"90"}, {n:"马耳他", v:"91"}, {n:"匈牙利", v:"92"}, {n:"西德", v:"93"}, {n:"新西兰", v:"94"}, {n:"哥伦比亚", v:"95"}, {n:"巴西", v:"96"}, {n:"印度尼西亚", v:"97"}, {n:"南非", v:"98"}]},
    {key:'年份', name:'年份', value:[{n:"全部", v:""}, {n:"2027", v:"78"}, {n:"2026", v:"1"}, {n:"2025", v:"3"}, {n:"2024", v:"4"}, {n:"2023", v:"56"}, {n:"2022", v:"13"}, {n:"2021", v:"2"}, {n:"2020", v:"6"}, {n:"2019", v:"8"}, {n:"2018", v:"9"}, {n:"2017", v:"12"}, {n:"2016", v:"11"}, {n:"2015", v:"14"}, {n:"2014", v:"15"}, {n:"2013", v:"22"}, {n:"2012", v:"10"}, {n:"2011", v:"17"}, {n:"2010", v:"25"}, {n:"2009", v:"20"}, {n:"2008", v:"23"}, {n:"2007", v:"30"}, {n:"2006", v:"31"}, {n:"2005", v:"7"}, {n:"2004", v:"24"}, {n:"2003", v:"28"}, {n:"2002", v:"19"}, {n:"2001", v:"29"}, {n:"2000", v:"43"}, {n:"1999", v:"45"}, {n:"1998", v:"33"}, {n:"1997", v:"34"}, {n:"1996", v:"37"}, {n:"1995", v:"21"}, {n:"1994", v:"27"}, {n:"1993", v:"26"}, {n:"1992", v:"35"}, {n:"1991", v:"18"}, {n:"1990", v:"42"}, {n:"1989", v:"44"}, {n:"1988", v:"60"}, {n:"1987", v:"73"}, {n:"1986", v:"32"}, {n:"1985", v:"40"}, {n:"1984", v:"63"}, {n:"1983", v:"59"}, {n:"1982", v:"36"}, {n:"1981", v:"5"}, {n:"1980", v:"39"}, {n:"1979", v:"61"}, {n:"1978", v:"66"}, {n:"1977", v:"48"}, {n:"1976", v:"38"}, {n:"1975", v:"57"}, {n:"1974", v:"62"}, {n:"1973", v:"53"}, {n:"1972", v:"54"}, {n:"1971", v:"51"}, {n:"1969", v:"41"}, {n:"1968", v:"77"}, {n:"1966", v:"76"}, {n:"1965", v:"58"}, {n:"1964", v:"79"}, {n:"1963", v:"46"}, {n:"1962", v:"65"}, {n:"1961", v:"71"}, {n:"1960", v:"75"}, {n:"1959", v:"16"}, {n:"1958", v:"67"}, {n:"1957", v:"50"}, {n:"1956", v:"49"}, {n:"1955", v:"47"}, {n:"1954", v:"68"}, {n:"1952", v:"72"}, {n:"1949", v:"70"}, {n:"1948", v:"64"}, {n:"1938", v:"52"}, {n:"1931", v:"55"}, {n:"1925", v:"69"}, {n:"1921", v:"74"}]},
    {key:'标签', name:'标签', value:[{n:"全部", v:""}, {n:"4k", v:"1"}, {n:"院线", v:"36"}, {n:"TC", v:"37"}]},
    {key:'排序', name:'排序', value:[{n:"默认", v:""}, {n:"最新上映", v:"update_time"}, {n:"最受欢迎", v:"hits"}, {n:"评分最高", v:"score"}]}
  ]
  }
};
