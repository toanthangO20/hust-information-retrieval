(function () {
  "use strict";

  var TYPE_LABEL = { html: "Trang web", pdf: "PDF", word: "Word", excel: "Excel", powerpoint: "PowerPoint", khac: "Khác" };
  var TAG_COLORS = ["#FFC933", "#FF9B8A", "#8FE0B5", "#9AD1FF", "#CDEB7A", "#FFD6A5"];
  var PAGE_SIZE = 10;

  var $ = function (id) { return document.getElementById(id); };
  var nf = new Intl.NumberFormat("vi-VN");
  var state = { q: "", host: "", type: "", page: 1 };
  var reqSeq = 0;

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }
  function shortHost(h) { return h.replace(/\.hust\.edu\.vn$/, "").replace(/^hust\.edu\.vn$/, "hust.edu.vn"); }
  function tagColor(h) {
    var x = 0;
    for (var i = 0; i < h.length; i++) x = (x * 31 + h.charCodeAt(i)) >>> 0;
    return TAG_COLORS[x % TAG_COLORS.length];
  }
  function prettyUrl(u) {
    try {
      var p = new URL(u);
      var path = decodeURIComponent(p.pathname + p.search).replace(/\/$/, "");
      return p.host + path.replace(/\//g, " › ");
    } catch (e) { return u; }
  }
  function words(n) { return nf.format(n) + " từ"; }

  // ---------- địa chỉ trang ----------
  function readUrl() {
    var p = new URLSearchParams(location.search);
    state.q = p.get("q") || "";
    state.host = p.get("host") || "";
    state.type = p.get("type") || "";
    state.page = Math.max(1, parseInt(p.get("page") || "1", 10) || 1);
  }
  function writeUrl(push) {
    var p = new URLSearchParams();
    if (state.q) p.set("q", state.q);
    if (state.host) p.set("host", state.host);
    if (state.type) p.set("type", state.type);
    if (state.page > 1) p.set("page", state.page);
    var qs = p.toString();
    var url = location.pathname + (qs ? "?" + qs : "");
    if (push) history.pushState(null, "", url); else history.replaceState(null, "", url);
  }

  // ---------- điều hướng ----------
  function showHome() {
    $("home").hidden = false;
    $("results").hidden = true;
    $("topSearch").hidden = true;
    document.title = "Tìm Bách Khoa";
  }
  function showResults() {
    $("home").hidden = true;
    $("results").hidden = false;
    $("topSearch").hidden = false;
  }
  function go(next, push) {
    for (var k in next) state[k] = next[k];
    writeUrl(push !== false);
    render();
  }
  function submitQuery(q) {
    q = q.trim();
    if (!q) return;
    go({ q: q, host: "", type: "", page: 1 });
  }

  // ---------- kết quả ----------
  function render() {
    if (!state.q && !state.host && !state.type) {
      showHome();
      $("heroQ").value = "";
      return;
    }
    showResults();
    $("topQ").value = state.q;
    document.title = (state.q || shortHost(state.host) || "Duyệt") + " – Tìm Bách Khoa";
    var seq = ++reqSeq;
    document.body.classList.add("loading");
    var p = new URLSearchParams({ q: state.q, host: state.host, type: state.type, page: state.page, size: PAGE_SIZE });
    fetch("/api/search?" + p.toString())
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (seq !== reqSeq) return;
        document.body.classList.remove("loading");
        if (data.error) throw new Error(data.error);
        paint(data);
        window.scrollTo({ top: 0 });
      })
      .catch(function (e) {
        if (seq !== reqSeq) return;
        document.body.classList.remove("loading");
        $("summary").textContent = "Không tìm kiếm được: " + e.message;
      });
  }

  function paint(d) {
    var secs = (d.tookMs / 1000).toLocaleString("vi-VN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    var html = "";
    if (d.total === 0) {
      $("summary").innerHTML = "";
      $("hits").innerHTML = "";
      $("pager").innerHTML = "";
      $("hits").innerHTML = '<li class="empty"><h2>Không có trang nào khớp</h2>' +
        "<ul><li>Thử ít từ khóa hơn hoặc dùng từ khác nghĩa gần.</li>" +
        "<li>Bỏ bớt bộ lọc đang chọn.</li><li>Có thể gõ không dấu, ví dụ <em>hoc bong</em>.</li></ul></li>";
    } else {
      $("summary").innerHTML = "<strong>" + nf.format(d.total) + "</strong> kết quả · " + secs + " giây" +
        (d.relaxed ? '<div class="notice">Không có trang nào chứa đủ mọi từ khóa, đang hiển thị các trang chứa một vài từ trong số đó.</div>' : "");
      d.hits.forEach(function (h) {
        html += '<li class="hit"><div class="hit-head"><span class="tag" style="--tag:' + tagColor(h.host) + '">' + esc(shortHost(h.host)) +
          '</span><span class="url" title="' + esc(h.url) + '">' + esc(prettyUrl(h.url)) + "</span></div>" +
          '<h2><a href="' + esc(h.url) + '" target="_blank" rel="noopener">' + h.titleHtml + "</a></h2>" +
          "<p>" + h.snippet + "</p>" +
          '<div class="hit-meta"><span>' + esc(TYPE_LABEL[h.type] || h.type) + "</span><span>" + words(h.words) + "</span></div></li>";
      });
      $("hits").innerHTML = html;
      pager(d);
    }
    filters(d);
    active();
  }

  function pager(d) {
    var pages = Math.ceil(d.total / d.size);
    var cur = d.page;
    var out = "";
    if (pages <= 1) { $("pager").innerHTML = ""; return; }
    function btn(label, page, opts) {
      opts = opts || {};
      return '<button type="button" data-page="' + page + '"' + (opts.disabled ? " disabled" : "") +
        (opts.current ? ' aria-current="page"' : "") + (opts.aria ? ' aria-label="' + opts.aria + '"' : "") + ">" + label + "</button>";
    }
    out += btn("←", cur - 1, { disabled: cur <= 1, aria: "Trang trước" });
    var list = [];
    for (var i = 1; i <= pages; i++) if (i === 1 || i === pages || Math.abs(i - cur) <= 2) list.push(i);
    var prev = 0;
    list.forEach(function (i) {
      if (prev && i - prev > 1) out += '<span class="gap">…</span>';
      out += btn(i, i, { current: i === cur });
      prev = i;
    });
    out += btn("→", cur + 1, { disabled: cur >= pages, aria: "Trang sau" });
    $("pager").innerHTML = out;
  }

  function filters(d) {
    function group(title, items, key, label) {
      if (!items.length) return "";
      var s = "<section><h3>" + title + "</h3><ul>";
      items.forEach(function (f) {
        s += '<li><button type="button" data-' + key + '="' + esc(f.value) + '" aria-pressed="' + (state[key] === f.value) + '">' +
          '<span class="name">' + esc(label(f.value)) + '</span><span class="c">' + nf.format(f.count) + "</span></button></li>";
      });
      return s + "</ul></section>";
    }
    $("filters").innerHTML =
      group("Trang con", d.hosts, "host", shortHost) +
      group("Loại tài liệu", d.types, "type", function (v) { return TYPE_LABEL[v] || v; });
  }

  function active() {
    var s = "";
    if (state.host) s += '<button type="button" data-clear="host">' + esc(shortHost(state.host)) + "</button>";
    if (state.type) s += '<button type="button" data-clear="type">' + esc(TYPE_LABEL[state.type] || state.type) + "</button>";
    $("active").innerHTML = s;
  }

  // ---------- trang chủ: thống kê ----------
  function loadStats() {
    fetch("/api/stats").then(function (r) { return r.json(); }).then(function (s) {
      var ver = s.version || "ver1";
      $("verLabel").textContent = ver;
      $("footVer").textContent = ver;
      $("heroDocs").textContent = nf.format(s.indexed);
      var types = s.by_doc_type || {};
      var facts = [
        [nf.format(s.indexed), "tài liệu tìm kiếm được"],
        [nf.format(s.hosts || 0), "trang con (subdomain)"],
        [((s.total_words || 0) / 1e6).toLocaleString("vi-VN", { maximumFractionDigits: 1 }) + " triệu", "từ văn bản"],
        [nf.format((types.pdf || 0) + (types.word || 0) + (types.excel || 0) + (types.powerpoint || 0)), "tệp PDF và Office"]
      ];
      $("facts").innerHTML = facts.map(function (f) { return "<div><dt>" + f[1] + "</dt><dd>" + f[0] + "</dd></div>"; }).join("");

      var hosts = Object.keys(s.by_host || {}).slice(0, 12);
      var max = hosts.length ? s.by_host[hosts[0]] : 1;
      $("hostBars").innerHTML = hosts.map(function (h) {
        var n = s.by_host[h];
        return '<li><a href="?host=' + encodeURIComponent(h) + '" data-host="' + esc(h) + '">' + esc(shortHost(h)) + '</a>' +
          '<span class="track"><span class="fill" style="width:' + (100 * n / max).toFixed(1) + '%"></span></span>' +
          '<span class="n">' + nf.format(n) + "</span></li>";
      }).join("");

      var tk = Object.keys(types).sort(function (a, b) { return types[b] - types[a]; });
      var tmax = tk.length ? types[tk[0]] : 1;
      $("typeBars").innerHTML = tk.map(function (t) {
        var n = types[t];
        return '<li><a href="?type=' + t + '" data-type="' + t + '">' + esc(TYPE_LABEL[t] || t) + "</a>" +
          '<span class="track"><span class="fill" style="width:' + Math.max(1.5, 100 * n / tmax).toFixed(1) + '%"></span></span>' +
          '<span class="n">' + nf.format(n) + "</span></li>";
      }).join("");
    }).catch(function () { /* thống kê là phần phụ, bỏ qua lỗi */ });
  }

  // ---------- sự kiện ----------
  $("heroSearch").addEventListener("submit", function (e) { e.preventDefault(); submitQuery($("heroQ").value); });
  $("topSearch").addEventListener("submit", function (e) { e.preventDefault(); submitQuery($("topQ").value); });

  document.addEventListener("click", function (e) {
    var t = e.target.closest("button, a");
    if (!t) return;
    if (t.dataset.q) { e.preventDefault(); $("heroQ").value = t.dataset.q; submitQuery(t.dataset.q); return; }
    if (t.hasAttribute("data-home")) { e.preventDefault(); go({ q: "", host: "", type: "", page: 1 }); return; }
    if (t.hasAttribute("data-home-link")) {
      e.preventDefault();
      go({ q: "", host: "", type: "", page: 1 });
      setTimeout(function () { $("dataset").scrollIntoView({ behavior: "smooth" }); }, 0);
      return;
    }
    if (t.dataset.page) { go({ page: parseInt(t.dataset.page, 10) }); return; }
    if (t.closest("#filters") && t.dataset.host !== undefined) { go({ host: state.host === t.dataset.host ? "" : t.dataset.host, page: 1 }); return; }
    if (t.closest("#filters") && t.dataset.type !== undefined) { go({ type: state.type === t.dataset.type ? "" : t.dataset.type, page: 1 }); return; }
    if (t.dataset.clear) { var n = { page: 1 }; n[t.dataset.clear] = ""; go(n); return; }
    if (t.closest(".bars") && t.dataset.host) { e.preventDefault(); go({ q: "", host: t.dataset.host, type: "", page: 1 }); return; }
    if (t.closest(".bars") && t.dataset.type) { e.preventDefault(); go({ q: "", host: "", type: t.dataset.type, page: 1 }); return; }
  });

  document.addEventListener("keydown", function (e) {
    var tag = (document.activeElement && document.activeElement.tagName) || "";
    if (e.key === "/" && tag !== "INPUT") {
      e.preventDefault();
      ($("results").hidden ? $("heroQ") : $("topQ")).focus();
    }
  });

  window.addEventListener("popstate", function () { readUrl(); render(); });

  readUrl();
  loadStats();
  render();
  if (state.q) $("heroQ").value = state.q;
})();
