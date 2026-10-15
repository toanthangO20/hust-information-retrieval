// Trang so sánh mô hình: /api/compare-info (mô hình + truy vấn có nhãn), /api/compare (top-k từng mô hình).
// Trạng thái nằm trên URL (?q=&qid=&s=bm25;hybrid:w=2) để mở thẳng một phép so sánh từ slide báo cáo.
(function () {
  const $ = (id) => document.getElementById(id);
  const form = $('cmpForm'), qIn = $('cq'), topicSel = $('ctopic'), sysBox = $('csys');
  const status = $('cstatus'), cols = $('ccols'), legend = $('clegend'), need = $('cneed');
  const DEFAULT = ['current', 'bm25', 'dense', 'hybrid:w=2'];
  const TYPE = { keyword: 'từ khoá', question: 'câu hỏi', paraphrase: 'diễn đạt lại', english: 'tiếng Anh' };
  let topics = [];

  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const num = (v, d = 3) => v.toFixed(d).replace('.', ',');

  function selected() {
    return [...sysBox.querySelectorAll('input:checked')].map((i) => i.value);
  }

  function topicFor(text) {
    const id = topicSel.value;
    const t = topics.find((x) => x.qid === id);
    return t && t.text === text ? t : null;
  }

  async function init() {
    const p = new URLSearchParams(location.search);
    let info;
    try {
      const r = await fetch('/api/compare-info');
      info = await r.json();
      if (info.error) throw new Error(info.error);
    } catch (e) {
      status.innerHTML = '<span class="err">Không nạp được mô hình: ' + esc(e.message) + '</span>';
      return;
    }
    topics = info.topics;
    const want = p.get('s') ? p.get('s').split(';') : DEFAULT;
    sysBox.insertAdjacentHTML('beforeend', info.systems.map((s) =>
      '<label class="chip' + (s.unavailable ? ' off' : '') + '"' + (s.unavailable ? ' title="' + esc(s.unavailable) + '"' : '') + '>' +
      '<input type="checkbox" value="' + esc(s.id) + '"' + (s.unavailable ? ' disabled' : want.includes(s.id) ? ' checked' : '') + '>' +
      '<b>' + esc(s.code) + '</b>' + esc(s.label) + '</label>').join(''));
    for (const split of ['test', 'dev']) {
      const g = document.createElement('optgroup');
      const list = topics.filter((t) => t.split === split);
      g.label = split + ' (' + list.length + ' truy vấn)';
      g.innerHTML = list.map((t) => '<option value="' + t.qid + '">' + t.qid + ' · ' + (TYPE[t.type] || t.type) + ' · ' + esc(t.text) + '</option>').join('');
      topicSel.appendChild(g);
    }
    if (p.get('qid') && topics.some((t) => t.qid === p.get('qid'))) {
      topicSel.value = p.get('qid');
      qIn.value = topics.find((t) => t.qid === p.get('qid')).text;
    } else if (p.get('q')) qIn.value = p.get('q');
    status.textContent = 'Chọn một truy vấn có nhãn để thấy mô hình nào đưa tài liệu phù hợp lên đầu, hoặc tự nhập truy vấn.';
    if (qIn.value) run();
  }

  topicSel.addEventListener('change', () => {
    const t = topics.find((x) => x.qid === topicSel.value);
    if (t) { qIn.value = t.text; run(); }
  });
  form.addEventListener('submit', (e) => { e.preventDefault(); run(); });
  sysBox.addEventListener('change', () => { if (qIn.value.trim()) run(); });

  async function run() {
    const q = qIn.value.trim();
    const systems = selected();
    if (!q) return;
    if (!systems.length) { status.textContent = 'Chọn ít nhất một mô hình.'; return; }
    const t = topicFor(q);
    if (!t) topicSel.value = '';
    need.hidden = !t;
    if (t) need.textContent = 'Nhu cầu thông tin (' + t.qid + ', ' + t.relevant + ' tài liệu phù hợp trong qrels): ' + t.narrative;
    const url = new URLSearchParams();
    if (t) url.set('qid', t.qid); else url.set('q', q);
    url.set('s', systems.join(';'));
    history.replaceState(null, '', '?' + url);
    status.textContent = 'Đang so sánh…';
    try {
      const api = new URLSearchParams({ q, systems: systems.join(';'), k: '10' });
      if (t) api.set('qid', t.qid);
      const r = await fetch('/api/compare?' + api);
      const data = await r.json();
      if (data.error) throw new Error(data.error);
      render(data, t);
    } catch (e) {
      status.innerHTML = '<span class="err">Lỗi: ' + esc(e.message) + '</span>';
    }
  }

  function relClass(h) {
    return h.rel === undefined ? '' : h.rel == null ? 'rn' : 'r' + h.rel;
  }

  function render(data, t) {
    legend.hidden = !t;
    const scored = data.columns.filter((c) => c.ndcg != null);
    const best = scored.length ? Math.max(...scored.map((c) => c.ndcg)) : null;
    const ref = data.columns.find((c) => c.id === 'bm25');
    const refIds = ref ? new Set(ref.hits.map((h) => h.id)) : null;
    status.innerHTML = t
      ? '<strong>' + esc(t.qid) + '</strong> · ' + (TYPE[t.type] || t.type) + ' · khung màu bên trái mỗi kết quả là nhãn phù hợp; cột viền vàng có nDCG@10 cao nhất.'
      : 'Truy vấn tự nhập: không có nhãn nên không chấm điểm, chỉ so thứ hạng và độ trùng lặp giữa các mô hình.';
    cols.innerHTML = data.columns.map((c) => {
      const overlap = refIds && c.id !== 'bm25' ? c.hits.filter((h) => refIds.has(h.id)).length : null;
      const head = '<div class="col-h"><div class="name"><b>' + esc(c.code) + '</b>' + esc(c.label) + '</div>' +
        (c.ndcg != null
          ? '<div class="score"><strong>' + num(c.ndcg) + '</strong><span>nDCG@10</span></div>' +
            '<div class="spark" aria-hidden="true">' + c.hits.slice(0, 10).map((h) => '<i class="' + relClass(h) + '"></i>').join('') + '</div>'
          : '') +
        '<div class="meta">' + num(c.ms, 1) + ' ms' +
        (c.judged != null && c.judged < 1 ? ' · ' + Math.round(c.judged * 10) + '/10 đã có nhãn' : '') +
        (overlap != null ? ' · trùng BM25 ' + overlap + '/' + c.hits.length : '') + '</div></div>';
      const list = '<ol class="hits-c">' + c.hits.map((h, i) =>
        '<li class="' + relClass(h) + '" data-doc="' + h.id + '"><span class="rk">' + (i + 1) + '</span>' +
        '<a href="' + esc(h.url) + '" target="_blank" rel="noopener">' + esc(h.title || '(không có tiêu đề)') + '</a>' +
        '<span class="h">' + esc(h.host) + (h.rel !== undefined ? '<span class="tag">' + (h.rel == null ? '?' : h.rel) + '</span>' : '') + '</span></li>').join('') + '</ol>';
      return '<section class="col' + (best != null && c.ndcg === best ? ' best' : '') + '">' + head + list + '</section>';
    }).join('');
  }

  cols.addEventListener('mouseover', (e) => {
    const li = e.target.closest('li[data-doc]');
    cols.querySelectorAll('li.hl').forEach((x) => x.classList.remove('hl'));
    if (li) cols.querySelectorAll('li[data-doc="' + li.dataset.doc + '"]').forEach((x) => x.classList.add('hl'));
  });
  cols.addEventListener('mouseleave', () => cols.querySelectorAll('li.hl').forEach((x) => x.classList.remove('hl')));

  init();
})();
