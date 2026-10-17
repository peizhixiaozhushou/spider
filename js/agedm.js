var rule = {
  title: '推广AGE动漫',
  host: 'https://www.agedm.io',
  homeUrl: '/',
  searchUrl: '/search?query=**',
  searchable: 2,
  quickSearch: 0,
  class_name: '全部动漫&一周更新&排行榜',
  class_url: 'catalog&update&rank',
  headers: { 'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36', 'Referer': 'https://www.agedm.io/' },
  timeout: 20000,
  play_parse: true,
  lazy: `js:
var h = getHtml(input);
var a = h.split('vip/?url=');
var tk = a.length > 1 ? a[1].split('"')[0] : '';
console.log('[agedm] token=' + tk.slice(0, 40));
input = { parse: 1, url: tk, header: { 'User-Agent': rule.headers['User-Agent'], 'Referer': rule.host + '/' } };
`,
  一级: `js:
var cid = '' + MY_CATE;
if (cid !== 'catalog' && cid !== 'update' && cid !== 'rank') cid = 'catalog';
var pg = MY_PAGE > 0 ? MY_PAGE : 1;
var url;
if (cid === 'catalog') url = rule.host + '/catalog/all-all-all-all-all-time-' + pg;
else url = rule.host + '/' + cid;
var h = getHtml(url);
var parts = h.split('/detail/');
var out = [];
var seen = {};
for (var i = 1; i < parts.length; i++) {
  var seg = parts[i];
  var vid = seg.split('"')[0];
  if (!vid || seen[vid]) continue;
  var t = seg.split('title="')[1];
  t = t ? t.split('"')[0] : '';
  if (!t) { var tx = seg.split('>'); t = tx.length > 1 ? tx[1].split('<')[0].trim() : ''; }
  if (!t) { t = seg.split('alt="')[1]; t = t ? t.split('"')[0] : ''; }
  var img = seg.split('data-original="')[1]; img = img ? img.split('"')[0] : '';
  if (!img) { img = seg.split('src="')[1]; img = img ? img.split('"')[0] : ''; }
  var st = seg.split('video_play_status');
  var stt = '';
  if (st.length > 1) { stt = st[1].split('>')[1]; stt = stt ? stt.split('<')[0].trim() : ''; }
  if (!t) continue;
  seen[vid] = 1;
  out.push({ vod_id: rule.host + '/detail/' + vid, vod_name: t.split('&amp;').join('&'), vod_pic: img.split('&amp;').join('&'), vod_remarks: stt });
}
VODS = out;
`,
  搜索: `js:
var url = rule.host + '/search?query=' + encodeURIComponent(KEY);
if (MY_PAGE > 1) url += '&page=' + MY_PAGE;
var h = getHtml(url);
var parts = h.split('/detail/');
var out = [];
var seen = {};
for (var i = 1; i < parts.length; i++) {
  var seg = parts[i];
  var vid = seg.split('"')[0];
  if (!vid || seen[vid]) continue;
  var t = seg.split('title="')[1]; t = t ? t.split('"')[0] : '';
  var img = seg.split('data-original="')[1]; img = img ? img.split('"')[0] : '';
  if (!t) continue;
  seen[vid] = 1;
  out.push({ vod_id: rule.host + '/detail/' + vid, vod_name: t.split('&amp;').join('&'), vod_pic: img.split('&amp;').join('&'), vod_remarks: '' });
}
VODS = out;
`,
  二级: `js:
var h = getHtml(input);
var vid = ('' + input).split('/detail/')[1]; vid = vid ? vid.split('/')[0].split('?')[0] : '';
var name = '';
var n1 = h.split('<h2');
if (n1.length > 1) { var n2 = n1[1].split('>'); name = n2.slice(1).join('>').split('<')[0].trim(); }
var pic = '';
var p1 = h.split('class="video_thumbs');
if (p1.length > 1) { var p2 = p1[1].split('data-original="'); if (p2.length > 1) pic = p2[1].split('"')[0]; }
if (!pic) { var p3 = h.split('<img'); if (p3.length > 1) { var p4 = p3[1].split('src="'); if (p4.length > 1) pic = p4[1].split('"')[0]; } }
var content = '';
var c1 = h.split('video_detail_desc');
if (c1.length > 1) { var c2 = c1[1].split('>'); content = c2.slice(1).join('>').split('</div>')[0].split('<br />').join(' ').split('<').join(' ').trim(); }
// 线路名(按钮顺序)
var names = [];
var bt = h.split('data-bs-target="#playlist-source-');
for (var i = 1; i < bt.length; i++) {
  var seg = bt[i].split('</button>')[0];
  var nm = seg.split('>');
  nm = nm[nm.length - 1].replace(/ +/g, '');
  if (nm) names.push(nm);
}
// 剧集(tab-pane 顺序)
var panes = h.split('id="playlist-source-');
var fromList = [];
var urlList = [];
for (var j = 1; j < panes.length; j++) {
  var seg2 = panes[j];
  var eps = seg2.split('video_detail_spisode_link');
  var list = [];
  for (var k = 1; k < eps.length; k++) {
    var label = eps[k].split('>')[1];
    label = label ? label.split('<')[0].trim() : ('第' + k + '集');
    list.push(label + '$' + rule.host + '/play/' + vid + '/' + j + '/' + k);
  }
  if (!list.length) continue;
  fromList.push(names[j - 1] || ('线路' + j));
  urlList.push(list.join('#'));
}
VOD = {
  vod_id: orId,
  vod_name: name,
  vod_pic: pic.split('&amp;').join('&'),
  vod_content: content,
  vod_play_from: fromList.join('$$$'),
  vod_play_url: urlList.join('$$$')
};
`
};
