/* Comparison report viewer. Everything the report contains is untrusted: the DOM is built with textContent only. */
(function () {
  'use strict';

  var TYPE_LABEL = {
    MISSING_IN_TARGET: 'Missing in target', MISSING_IN_SOURCE: 'Missing in source', VALUE_MISMATCH: 'Value differs',
    TYPE_MISMATCH: 'Type differs', STATUS_MISMATCH: 'HTTP status differs'
  };
  var TYPE_ORDER = ['VALUE_MISMATCH', 'TYPE_MISMATCH', 'MISSING_IN_TARGET', 'MISSING_IN_SOURCE', 'STATUS_MISMATCH'];
  var MAX_LINES = 20000;

  var state = {
    report: null, name: '', docs: {}, status: 'ALL', query: '', types: {}, theme: null,
    startTab: 'run', stash: null, request: null, running: null
  };
  var token = 0;
  var app = document.getElementById('app');

  /* ---------- helpers ---------- */
  function h(tag, props) {
    var el = document.createElement(tag);
    props = props || {};
    Object.keys(props).forEach(function (k) {
      var v = props[k];
      if (v === null || v === undefined || v === false) return;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.indexOf('on') === 0) el.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.keys(v).forEach(function (d) { el.dataset[d] = v[d]; });
      else el.setAttribute(k, v === true ? '' : v);
    });
    for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }
  function append(el, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) child.forEach(function (c) { append(el, c); });
    else el.appendChild(child.nodeType ? child : document.createTextNode(String(child)));
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
  function svg(path) {
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 16 16');
    var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', path);
    s.appendChild(p);
    return s;
  }
  function toast(text) {
    var t = h('div', { class: 'toast', role: 'status', text: text });
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3500);
  }
  function store(key, value) { try { if (value === undefined) return localStorage.getItem(key); localStorage.setItem(key, value); } catch (e) { return null; } return null; }
  function plural(n, word) { return n + ' ' + word + (n === 1 ? '' : 's'); }
  function show(v) { return JSON.stringify(v, null, 2); }
  function truncate(text, max) { return text.length > max ? text.slice(0, max) + '…' : text; }

  /* ---------- theme ---------- */
  function applyTheme(theme) {
    state.theme = theme;
    if (theme) document.documentElement.setAttribute('data-theme', theme);
    else document.documentElement.removeAttribute('data-theme');
  }
  function isDark() {
    if (state.theme) return state.theme === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  applyTheme(store('viewer-theme'));

  /* ---------- loading a report ---------- */
  function normalize(data) {
    if (data && !Array.isArray(data) && Array.isArray(data.results)) return data;
    if (data && data.path && data.status) { // a single result, e.g. a saved {path}/report.json
      var counts = { IDENTICAL: 0, DIFFERENT: 0, ERROR: 0 };
      counts[data.status] = 1;
      return { summary: { total: 1, identical: counts.IDENTICAL, different: counts.DIFFERENT, errors: counts.ERROR }, results: [data] };
    }
    throw new Error('This is not a comparison report: expected an object with "summary" and "results".');
  }
  function loadReport(data, name, docs) {
    var report = normalize(data);
    state.report = report;
    state.name = name || '';
    state.docs = docs || {};
    state.status = 'ALL';
    state.query = '';
    state.types = {};
    location.hash = '#/';
    render();
  }
  function loadText(text, name) {
    var data;
    try { data = JSON.parse(text); } catch (e) { toast('Not valid JSON: ' + e.message); return; }
    try { loadReport(data, name); } catch (e) { toast(e.message); }
  }
  function loadFile(file) {
    var reader = new FileReader();
    reader.onload = function () { loadText(String(reader.result), file.name); };
    reader.onerror = function () { toast('Could not read ' + file.name); };
    reader.readAsText(file);
  }
  function fetchJson(url) {
    return fetch(url, { headers: { Accept: 'application/json' } }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }
  function savedUrl(path) { return 'reports/file?path=' + encodeURIComponent(path); }
  function openRun(run) {
    fetchJson(savedUrl(run + '/report.json')).then(function (data) { loadReport(data, run); })
      .catch(function (e) { toast('Could not open run ' + run + ': ' + e.message); });
  }
  function loadSample() {
    if (!window.SAMPLE) { toast('No sample available'); return; }
    var sample = JSON.parse(JSON.stringify(window.SAMPLE));
    loadReport(sample.report, 'sample report', sample.docs);
  }

  /* ---------- global drag and drop ---------- */
  var overlay = h('div', { class: 'drop-overlay', text: 'Drop a report .json file' });
  document.body.appendChild(overlay);
  var dragDepth = 0;
  window.addEventListener('dragenter', function (e) { if (hasFiles(e)) { dragDepth++; overlay.classList.add('show'); } });
  window.addEventListener('dragleave', function (e) { if (hasFiles(e) && --dragDepth <= 0) { dragDepth = 0; overlay.classList.remove('show'); } });
  window.addEventListener('dragover', function (e) { if (hasFiles(e)) e.preventDefault(); });
  window.addEventListener('drop', function (e) {
    if (!hasFiles(e)) return;
    e.preventDefault(); dragDepth = 0; overlay.classList.remove('show');
    if (e.dataTransfer.files.length) loadFile(e.dataTransfer.files[0]);
  });
  function hasFiles(e) { return e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') >= 0; }

  /* ---------- routing ---------- */
  function route() {
    var m = /^#\/p\/(\d+)(?:\/(diff|side|raw))?$/.exec(location.hash);
    if (!m) return { page: 'overview' };
    return { page: 'path', index: parseInt(m[1], 10), tab: m[2] || null };
  }
  window.addEventListener('hashchange', function () { if (state.report) render(); }); // the landing page does not depend on the route

  /* ---------- derived data ---------- */
  function result(i) { return state.report.results[i]; }
  function diffCount(r) { return r.differenceCount || (r.differences ? r.differences.length : 0); }
  function visibleResults() {
    var q = state.query.trim().toLowerCase();
    return state.report.results.map(function (r, i) { return { r: r, i: i }; }).filter(function (x) {
      return (state.status === 'ALL' || x.r.status === state.status) && (!q || String(x.r.path).toLowerCase().indexOf(q) >= 0);
    });
  }
  function statusCounts() {
    var c = { IDENTICAL: 0, DIFFERENT: 0, ERROR: 0 };
    state.report.results.forEach(function (r) { c[r.status] = (c[r.status] || 0) + 1; });
    return c;
  }

  /* ---------- rendering: shell ---------- */
  function render() {
    token++;
    var scrollKeep = window.scrollY;
    clear(app);
    if (!state.report) { app.appendChild(landing()); return; }
    var rt = route();
    var main = h('main', { class: 'main' });
    app.appendChild(h('div', { class: 'app' }, topbar(), h('div', { class: 'layout' }, sidebar(rt), main)));
    if (rt.page === 'overview') overview(main);
    else if (rt.index >= state.report.results.length) main.appendChild(h('div', { class: 'card empty', text: 'No such path.' }));
    else pathPage(main, rt.index, rt.tab);
    if (rt.page === 'overview') window.scrollTo(0, scrollKeep);
  }

  function topbar() {
    return h('header', { class: 'topbar' },
      h('a', { class: 'brand', href: '#/' }, h('span', { class: 'brand-mark' }, svg('M3 3h4v10H3zM9 3h4v10H9z')), 'Comparison report'),
      state.name ? h('span', { class: 'report-name', title: state.name, text: state.name }) : null,
      h('span', { class: 'spacer' }),
      h('button', { class: 'btn small primary', onclick: function () { goStart('run'); } }, '▶ New comparison'),
      h('button', { class: 'btn small', onclick: function () { goStart('open'); } }, 'Open a report'),
      h('button', {
        class: 'btn small ghost', title: 'Toggle dark mode', 'aria-label': 'Toggle dark mode', onclick: function () {
          var next = isDark() ? 'light' : 'dark'; applyTheme(next); store('viewer-theme', next);
        }
      }, isDark() ? '☀ Light' : '☾ Dark'));
  }

  function sidebar(rt) {
    var counts = statusCounts();
    var list = h('nav', { class: 'path-list', 'aria-label': 'Compared paths' });
    var items = visibleResults();
    items.forEach(function (x) {
      var n = diffCount(x.r);
      var cls = x.r.status === 'ERROR' ? 'count err' : (n === 0 ? 'count zero' : 'count');
      list.appendChild(h('a', {
        class: 'path-item' + (rt.page === 'path' && rt.index === x.i ? ' active' : ''), href: '#/p/' + x.i, title: x.r.path
      }, h('span', { class: 'dot ' + x.r.status }), h('span', { class: 'p', text: x.r.path }),
        h('span', { class: cls, text: x.r.status === 'ERROR' ? '!' : String(n) })));
    });
    if (!items.length) list.appendChild(h('div', { class: 'empty', text: 'No path matches.' }));

    var search = h('input', {
      class: 'search', type: 'search', placeholder: 'Filter paths…', value: state.query, 'aria-label': 'Filter paths'
    });
    search.addEventListener('input', function () {
      state.query = search.value; refreshList(list, rt);
    });
    var chips = h('div', { class: 'chips' });
    [['ALL', 'All ' + state.report.results.length], ['DIFFERENT', 'Different ' + counts.DIFFERENT],
      ['ERROR', 'Errors ' + counts.ERROR], ['IDENTICAL', 'Identical ' + counts.IDENTICAL]].forEach(function (c) {
      chips.appendChild(h('button', {
        class: 'chip' + (state.status === c[0] ? ' on' : ''), text: c[1], onclick: function () { state.status = c[0]; render(); }
      }));
    });
    return h('aside', { class: 'sidebar' },
      h('div', { class: 'sidebar-head' },
        h('a', { class: 'nav-overview' + (rt.page === 'overview' ? ' active' : ''), href: '#/' }, '▦ Overview'),
        search, chips),
      list);
  }
  function refreshList(list, rt) { // keep the focus in the search box while typing
    var fresh = sidebar(rt).querySelector('.path-list');
    list.replaceChildren.apply(list, Array.prototype.slice.call(fresh.childNodes));
  }

  /* ---------- overview ---------- */
  function overview(main) {
    var s = state.report.summary || {};
    var counts = statusCounts();
    var total = state.report.results.length;
    main.appendChild(h('div', { class: 'card card-pad' },
      h('div', { class: 'page-title' }, h('h1', { text: 'Overview', style: 'font-family:inherit' }),
        h('span', { class: 'sub', text: plural(total, 'path') + ' compared' })),
      h('div', { class: 'opts' },
        opt('typeMatching', s.typeMatching), opt('nullMatching', s.nullMatching),
        s.orderStrict === undefined ? null : opt('orderStrict', String(s.orderStrict)))));

    main.appendChild(h('div', { class: 'tiles' },
      tile(total, 'Paths', ''), tile(counts.IDENTICAL, 'Identical', 'ok'), tile(counts.DIFFERENT, 'Different', 'warn'), tile(counts.ERROR, 'Errors', 'bad')));

    // distribution of differences by type
    var byType = {};
    var totalDiffs = 0;
    state.report.results.forEach(function (r) {
      (r.differences || []).forEach(function (d) { byType[d.type] = (byType[d.type] || 0) + 1; totalDiffs++; });
    });
    var cs = getComputedStyle(document.documentElement);
    function color(name) { return cs.getPropertyValue(name).trim(); }
    var parts = [[counts.IDENTICAL, '--ok', 'Identical'], [counts.DIFFERENT, '--warn', 'Different'], [counts.ERROR, '--bad', 'Error']];
    var acc = 0, stops = [];
    parts.forEach(function (p) {
      if (!p[0]) return;
      var from = acc / total * 360; acc += p[0]; var to = acc / total * 360;
      stops.push(color(p[1]) + ' ' + from + 'deg ' + to + 'deg');
    });
    var donut = h('div', { class: 'donut', style: 'background:conic-gradient(' + (stops.join(',') || color('--border') + ' 0deg 360deg') + ')' },
      h('div', null, h('span', null, h('b', { text: total ? Math.round(counts.IDENTICAL / total * 100) + '%' : '–' }), h('span', { text: 'identical' }))));
    var legend = h('div', { class: 'legend' });
    parts.forEach(function (p) {
      legend.appendChild(h('div', { class: 'legend-row' }, h('span', { class: 'sw', style: 'background:' + color(p[1]) }), h('span', { text: p[2] + ' paths' }), h('b', { text: p[0] })));
    });
    var typeBar = h('div', { class: 'bar', title: 'Differences by type' });
    var typeLegend = h('div', { class: 'opts' });
    var typeColor = { MISSING_IN_TARGET: '--bad', MISSING_IN_SOURCE: '--ok', VALUE_MISMATCH: '--warn', TYPE_MISMATCH: '--violet', STATUS_MISMATCH: '--info' };
    TYPE_ORDER.forEach(function (t) {
      if (!byType[t]) return;
      typeBar.appendChild(h('i', { style: 'width:' + (byType[t] / totalDiffs * 100) + '%;background:' + color(typeColor[t]) }));
      typeLegend.appendChild(h('span', { class: 'badge ' + t, text: TYPE_LABEL[t] + ' · ' + byType[t] }));
    });
    main.appendChild(h('div', { class: 'card card-pad overview-grid' }, donut,
      h('div', null, legend, h('h3', { text: totalDiffs ? plural(totalDiffs, 'difference') + ' by type' : 'No difference', style: 'margin-top:18px' }),
        totalDiffs ? typeBar : null, totalDiffs ? h('div', { style: 'margin-top:10px' }, typeLegend) : null)));

    var items = visibleResults();
    var body = h('tbody');
    items.forEach(function (x) {
      var r = x.r, types = h('div', { class: 'mini-types' });
      TYPE_ORDER.forEach(function (t) { if (r.byType && r.byType[t]) types.appendChild(h('span', { class: 'badge ' + t, text: TYPE_LABEL[t] + ' ' + r.byType[t] })); });
      var row = h('tr', { tabindex: '0', onclick: function () { location.hash = '#/p/' + x.i; } },
        h('td', null, h('span', { class: 'badge ' + r.status, text: r.status })),
        h('td', { class: 'pathcell', text: r.path }),
        h('td', { text: r.status === 'ERROR' ? '—' : String(diffCount(r)) }),
        h('td', null, r.status === 'ERROR' ? h('span', { class: 'sub', text: truncate(r.summary || '', 90) }) : types));
      row.addEventListener('keydown', function (e) { if (e.key === 'Enter') location.hash = '#/p/' + x.i; });
      body.appendChild(row);
    });
    main.appendChild(h('div', { class: 'card' },
      h('div', { class: 'card-pad', style: 'padding-bottom:6px' }, h('h2', { text: 'Paths' })),
      items.length ? h('table', { class: 'paths' },
        h('thead', null, h('tr', null, h('th', { text: 'Status' }), h('th', { text: 'Path' }), h('th', { text: 'Differences' }), h('th', { text: 'Details' }))), body)
        : h('div', { class: 'empty', text: 'No path matches the filter.' })));
  }
  function opt(name, value) { return value === undefined ? null : h('span', { class: 'opt' }, h('b', { text: name }), String(value)); }
  function tile(n, label, cls) { return h('div', { class: 'tile ' + cls }, h('div', { class: 'n', text: String(n) }), h('div', { class: 'l', text: label })); }

  /* ---------- path page ---------- */
  function pathPage(main, i, tab) {
    var r = result(i);
    var diffs = r.differences || [];
    tab = tab || (r.status === 'DIFFERENT' ? 'diff' : (r.status === 'ERROR' ? 'diff' : 'side'));
    var total = state.report.results.length;
    function tabLink(id, label, n) {
      return h('a', { class: 'tab' + (tab === id ? ' active' : ''), href: '#/p/' + i + '/' + id }, label, n === undefined ? null : h('span', { class: 'n', text: String(n) }));
    }
    main.appendChild(h('div', { class: 'card card-pad' },
      h('div', { class: 'page-title' },
        h('h1', { text: r.path }), h('span', { class: 'badge ' + r.status, text: r.status }),
        h('div', { class: 'pager' },
          h('a', { class: 'btn small', href: i > 0 ? '#/p/' + (i - 1) : null, 'aria-disabled': i === 0 ? 'true' : null, text: '← Prev' }),
          h('a', { class: 'btn small', href: i < total - 1 ? '#/p/' + (i + 1) : null, 'aria-disabled': i === total - 1 ? 'true' : null, text: 'Next →' }))),
      h('div', { class: 'sub', style: 'margin-top:6px', text: r.status === 'ERROR' ? '' : (r.summary || '') }),
      r.status === 'ERROR' ? h('div', { class: 'banner err', style: 'margin-top:10px' }, h('b', { text: 'Comparison failed' }), h('span', { text: r.summary || 'unknown error' })) : null,
      r.status === 'IDENTICAL' ? h('div', { class: 'banner ok', style: 'margin-top:10px' }, h('b', { text: 'Identical' }), h('span', { text: 'Source and target are semantically equal.' })) : null));

    var body = h('div');
    main.appendChild(h('div', { class: 'card' },
      h('div', { class: 'tabs' }, tabLink('diff', 'Differences', diffs.length), tabLink('side', 'Side by side'), tabLink('raw', 'Report JSON')), body));

    if (tab === 'diff') differencesTab(body, r, i);
    else if (tab === 'raw') body.appendChild(h('pre', { class: 'raw mono', text: show(r) }));
    else sideBySideTab(body, r, i);
  }

  /* ---- differences tab ---- */
  var typeFilter = null;
  function differencesTab(body, r, i) {
    var diffs = r.differences || [];
    if (!diffs.length) {
      body.appendChild(h('div', { class: 'empty' }, h('div', { class: 'big', text: r.status === 'ERROR' ? '⚠' : '✓' }),
        h('div', { text: r.status === 'ERROR' ? 'No comparison was made, so there is no difference to show.' : 'No difference.' })));
      return;
    }
    var list = h('div', { class: 'diff-list' });
    var chips = h('div', { class: 'chips', style: 'padding:14px 20px 0' });
    function paint() {
      clear(list); clear(chips);
      chips.appendChild(h('button', { class: 'chip' + (!typeFilter ? ' on' : ''), text: 'All ' + diffs.length, onclick: function () { typeFilter = null; paint(); } }));
      TYPE_ORDER.forEach(function (t) {
        var n = diffs.filter(function (d) { return d.type === t; }).length;
        if (n) chips.appendChild(h('button', { class: 'chip' + (typeFilter === t ? ' on' : ''), text: TYPE_LABEL[t] + ' ' + n, onclick: function () { typeFilter = t; paint(); } }));
      });
      diffs.forEach(function (d, k) { if (!typeFilter || d.type === typeFilter) list.appendChild(diffCard(d, k, i)); });
    }
    paint();
    body.appendChild(chips); body.appendChild(list);
  }

  function locationEl(d) {
    var text = d.location || d.jsonPath;
    var out = h('div', { class: 'loc', title: d.jsonPath });
    var parts = text.split(/(\[[^\]]*\])/).filter(function (p) { return p !== ''; });
    parts.forEach(function (p, n) {
      var isEl = /^\[.*\]$/.test(p) && !/^\['/.test(p);
      var last = n === parts.length - 1;
      out.appendChild(h('span', { class: isEl ? 'el' : (last ? 'leaf' : 'seg'), text: p }));
    });
    return out;
  }

  function valueBox(cls, title, present, value, other, otherPresent) {
    var pre = h('pre');
    if (!present) { pre.textContent = '— missing —'; }
    else {
      var text = typeof value === 'string' ? JSON.stringify(value) : show(value);
      if (typeof value === 'string' && typeof other === 'string' && otherPresent && value !== other) {
        var a = value, b = other, p = 0;
        while (p < a.length && p < b.length && a[p] === b[p]) p++;
        var s = 0;
        while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] === b[b.length - 1 - s]) s++;
        pre.appendChild(document.createTextNode('"' + a.slice(0, p)));
        pre.appendChild(h('span', { class: 'x', text: a.slice(p, a.length - s) }));
        pre.appendChild(document.createTextNode(a.slice(a.length - s) + '"'));
      } else pre.textContent = truncate(text, 4000);
    }
    return h('div', { class: 'side ' + cls + (present ? '' : ' missing') }, h('h4', { text: title }), pre);
  }

  function diffCard(d, k, i) {
    var hasSource = d.type !== 'MISSING_IN_SOURCE';
    var hasTarget = d.type !== 'MISSING_IN_TARGET';
    var body = null;
    if (d.type === 'STATUS_MISMATCH') {
      body = h('div', { class: 'diff-body' }, valueBox('src', 'Source status', true, d.source), valueBox('tgt', 'Target status', true, d.target));
    } else {
      body = h('div', { class: 'diff-body' },
        valueBox('src', 'Source', hasSource, d.source, d.target, hasTarget), valueBox('tgt', 'Target', hasTarget, d.target, d.source, hasSource));
    }
    var foot = h('div', { class: 'diff-foot' }, h('span', { class: 'msg', text: d.message || '' }),
      d.type === 'STATUS_MISMATCH' ? null : h('a', { class: 'btn small', href: '#/p/' + i + '/side', onclick: function () { pendingFocus = k; }, text: 'Show in context' }));
    return h('article', { class: 'diff ' + d.type, id: 'diff-' + k },
      h('div', { class: 'diff-head' }, h('span', { class: 'badge ' + d.type, text: TYPE_LABEL[d.type] || d.type }), locationEl(d),
        d.targetPath ? h('span', { class: 'sub mono', title: 'Position in the target document', text: 'target: ' + d.targetPath }) : null),
      body, foot);
  }

  /* ---- side by side ---- */
  var pendingFocus = null;

  function docsFor(i) {
    var r = result(i);
    var entry = state.docs[i] || (state.docs[i] = { source: undefined, target: undefined });
    var jobs = [];
    ['source', 'target'].forEach(function (side) {
      var file = r[side + 'File'];
      if (entry[side] === undefined && file) {
        jobs.push(fetchJson(savedUrl(file)).then(function (v) { entry[side] = v; }).catch(function () { entry[side] = undefined; entry[side + 'Failed'] = true; }));
      }
    });
    return Promise.all(jobs).then(function () { return entry; });
  }

  function sideBySideTab(body, r, i) {
    var mine = token;
    var holder = h('div', null, h('div', { class: 'spinner', role: 'status', 'aria-label': 'Loading documents' }));
    body.appendChild(holder);
    docsFor(i).then(function (entry) {
      if (mine !== token) return;
      clear(holder);
      holder.appendChild(sideBySideView(r, i, entry));
    });
  }

  function marksFor(diffs, side) {
    var marks = new Map();
    diffs.forEach(function (d, k) {
      if (d.type === 'STATUS_MISMATCH') return;
      var path, kind;
      if (side === 'source') {
        if (d.type === 'MISSING_IN_SOURCE') return;
        path = d.jsonPath; kind = d.type === 'MISSING_IN_TARGET' ? 'del' : 'chg';
      } else {
        if (d.type === 'MISSING_IN_TARGET') return;
        path = d.targetPath || d.jsonPath; kind = d.type === 'MISSING_IN_SOURCE' ? 'add' : 'chg';
      }
      var m = marks.get(path);
      if (!m) marks.set(path, { kind: kind, ids: [k] }); else m.ids.push(k);
    });
    return marks;
  }

  function renderPane(title, doc, marks, extraNote) {
    var code = h('div', { class: 'code', tabindex: '0', 'aria-label': title + ' document' });
    var els = new Map(); // diff id -> first direct line
    var note = extraNote;
    if (doc === undefined) {
      code.appendChild(h('div', { class: 'empty', text: note || 'Not available.' }));
      return { root: h('div', { class: 'pane' }, h('div', { class: 'pane-title' }, h('span', { text: title })), code), code: code, els: els };
    }
    var built = window.JsonView.buildLines(doc, marks, MAX_LINES);
    var frag = document.createDocumentFragment();
    built.lines.forEach(function (ln, n) {
      var own = ln.mark, inh = ln.inherited, m = own || inh;
      var cls = 'line' + (m ? ' hl-' + m.kind + (own ? ' direct' : ' inh') : '');
      var mk = own ? (m.kind === 'del' ? '−' : m.kind === 'add' ? '+' : '≠') : '';
      var tx = h('span', { class: 'tx', style: 'padding-left:' + (ln.depth * 1.6) + 'ch' });
      if (ln.key !== null && ln.key !== undefined && ln.type !== 'close') tx.appendChild(h('span', { class: 'k', text: JSON.stringify(String(ln.key)) + ': ' }));
      tx.appendChild(h('span', { class: ln.cls, text: ln.text }));
      if (ln.comma && ln.type !== 'open') tx.appendChild(document.createTextNode(','));
      var line = h('div', { class: cls, dataset: { path: ln.path } }, h('span', { class: 'no', text: String(n + 1) }), h('span', { class: 'mk', text: mk }), tx);
      if (own && ln.type !== 'close') {
        line.dataset.diff = own.ids.join(',');
        own.ids.forEach(function (id) { if (!els.has(id)) els.set(id, line); });
      }
      frag.appendChild(line);
    });
    code.appendChild(frag);
    if (built.truncated) code.appendChild(h('div', { class: 'banner warn', style: 'margin:10px', text: 'Showing the first ' + MAX_LINES + ' lines only.' }));
    return { root: h('div', { class: 'pane' }, h('div', { class: 'pane-title' }, h('span', { text: title }), h('span', { text: built.lines.length + ' lines' })), code), code: code, els: els };
  }

  function sideBySideView(r, i, entry) {
    var diffs = r.differences || [];
    var wrap = h('div');
    var haveBoth = entry.source !== undefined && entry.target !== undefined;

    var attach = null;
    if (entry.source === undefined || entry.target === undefined) {
      attach = h('div', { class: 'banner info', style: 'margin:16px 20px' },
        h('span', null, entry.sourceFailed || entry.targetFailed
          ? 'The saved documents could not be loaded from the server. ' : 'This report has no saved documents for this path. ',
          'Attach the source and target JSON to see them side by side: ', filePicker('source', i), ' ', filePicker('target', i)));
    }
    var legend = h('div', { class: 'legend-inline' },
      h('span', null, h('i', { style: 'background:var(--hl-del-strong)' }), 'only in source / removed'),
      h('span', null, h('i', { style: 'background:var(--hl-add-strong)' }), 'only in target / added'),
      h('span', null, h('i', { style: 'background:var(--hl-chg-strong)' }), 'value or type differs'));

    var navDiffs = diffs.map(function (d, k) { return k; }).filter(function (k) { return diffs[k].type !== 'STATUS_MISMATCH'; });
    var current = -1;
    var counter = h('span', { class: 'sub', text: navDiffs.length ? '0 / ' + navDiffs.length : 'no difference' });
    var msg = h('span', { class: 'sub', style: 'max-width:46ch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' });
    var prev = h('button', { class: 'btn small', text: '↑ Previous', disabled: navDiffs.length ? null : true });
    var next = h('button', { class: 'btn small primary', text: 'Next difference ↓', disabled: navDiffs.length ? null : true });
    var bar = h('div', { class: 'sbs-bar' }, legend, h('div', { class: 'nav-diff' }, msg, counter, prev, next));

    var left = renderPane('Source', entry.source, marksFor(diffs, 'source'), 'No source document.');
    var right = renderPane('Target', entry.target, marksFor(diffs, 'target'), 'No target document.');
    var sbs = h('div', { class: 'sbs' }, left.root, right.root);

    // proportional scrolling between the two panes
    var syncing = false;
    function link(from, to) {
      from.addEventListener('scroll', function () {
        if (syncing) return; syncing = true;
        var max = from.scrollHeight - from.clientHeight;
        var ratio = max > 0 ? from.scrollTop / max : 0;
        to.scrollTop = ratio * (to.scrollHeight - to.clientHeight);
        requestAnimationFrame(function () { syncing = false; });
      });
    }
    link(left.code, right.code); link(right.code, left.code);

    function center(pane, el) {
      if (!el) return;
      var top = el.offsetTop - pane.clientHeight / 2 + el.clientHeight / 2;
      pane.scrollTop = Math.max(0, top);
    }
    function goTo(n, user) {
      if (!navDiffs.length) return;
      current = (n + navDiffs.length) % navDiffs.length;
      var id = navDiffs[current];
      syncing = true;
      var l = left.els.get(id), rr = right.els.get(id);
      wrap.querySelectorAll('.line.active').forEach(function (e) { e.classList.remove('active'); });
      [[left, l], [right, rr]].forEach(function (p) { if (p[1]) { p[1].classList.add('active'); center(p[0].code, p[1]); } });
      requestAnimationFrame(function () { setTimeout(function () { syncing = false; }, 60); });
      counter.textContent = (current + 1) + ' / ' + navDiffs.length;
      msg.textContent = truncate(diffs[id].location || diffs[id].jsonPath, 60);
      msg.title = diffs[id].message || '';
      if (!(l || rr)) return;
      if (user) (l || rr).scrollIntoView({ block: 'nearest' });
    }
    prev.addEventListener('click', function () { goTo(current - 1, true); });
    next.addEventListener('click', function () { goTo(current + 1, true); });
    function onLineClick(e) {
      var line = e.target.closest ? e.target.closest('.line.direct') : null;
      if (!line || !line.dataset.diff) return;
      var id = parseInt(line.dataset.diff.split(',')[0], 10);
      var pos = navDiffs.indexOf(id);
      if (pos >= 0) goTo(pos, true);
    }
    sbs.addEventListener('click', onLineClick);

    wrap.appendChild(bar);
    if (attach) wrap.appendChild(attach);
    wrap.appendChild(sbs);
    if (state.status === 'ERROR' && !haveBoth && !attach) wrap.appendChild(h('div', { class: 'empty', text: 'Nothing to compare.' }));

    // after layout: honour "show in context" or start on the first difference
    setTimeout(function () {
      if (pendingFocus !== null) { var pos = navDiffs.indexOf(pendingFocus); pendingFocus = null; if (pos >= 0) goTo(pos); }
      else if (navDiffs.length && haveBoth) goTo(0);
    }, 0);
    return wrap;
  }

  function filePicker(side, i) {
    var input = h('input', { type: 'file', accept: '.json,application/json', class: 'sr-only', id: 'pick-' + side });
    input.addEventListener('change', function () {
      var f = input.files[0]; if (!f) return;
      var reader = new FileReader();
      reader.onload = function () {
        try {
          var entry = state.docs[i] || (state.docs[i] = {});
          entry[side] = JSON.parse(String(reader.result));
          render();
        } catch (e) { toast('Not valid JSON: ' + e.message); }
      };
      reader.readAsText(f);
    });
    return h('span', null, input, h('label', { class: 'btn small', for: 'pick-' + side, text: 'Attach ' + side + '…' }));
  }

  /* ---------- start page: run a comparison / open a report ---------- */
  function goStart(tab) {
    if (state.report) state.stash = { report: state.report, name: state.name, docs: state.docs, status: state.status, query: state.query };
    state.report = null; state.startTab = tab;
    location.hash = '#/'; render();
  }
  function restoreStash() {
    var st = state.stash; if (!st) return;
    state.report = st.report; state.name = st.name; state.docs = st.docs; state.status = st.status; state.query = st.query;
    render();
  }

  var EXAMPLE_REQUEST = {
    sourceBaseUrl: 'https://source.example.com',
    targetBaseUrl: 'https://target.example.com',
    paths: ['/customers/42/orders', '/products/1001'],
    sourceHeaders: { Authorization: 'Bearer <token>' },
    targetHeaders: { 'X-Api-Key': '<key>' }
  };

  /** Validates the request JSON the same way the server does, to answer before any call. */
  function checkRequest(text) {
    var body;
    try { body = JSON.parse(text); } catch (e) { return { error: 'Not valid JSON: ' + e.message }; }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return { error: 'The request must be a JSON object.' };
    var badUrl = ['sourceBaseUrl', 'targetBaseUrl'].filter(function (k) { return !/^https?:\/\/[^\s/]+/i.test(String(body[k] || '')); })[0];
    if (badUrl) return { error: badUrl + ' must be an absolute http or https URL.' };
    if (!Array.isArray(body.paths) || !body.paths.length) return { error: 'paths must be a non-empty list.' };
    if (body.paths.some(function (p) { return typeof p !== 'string' || !p.trim(); })) return { error: 'paths must not contain blank entries.' };
    return { body: body };
  }

  function runComparison(text, options, ui) {
    var checked = checkRequest(text);
    if (checked.error) { ui.fail(checked.error); return; }
    var q = [];
    if (options.typeMatching) q.push('typeMatching=' + options.typeMatching);
    if (options.nullMatching) q.push('nullMatching=' + options.nullMatching);
    if (options.orderStrict) q.push('orderStrict=' + options.orderStrict);
    if (options.idProperties) q.push('idProperties=' + encodeURIComponent(options.idProperties));
    var controller = new AbortController();
    state.running = controller;
    ui.busy(true);
    var started = Date.now();
    fetch('compare/custom' + (q.length ? '?' + q.join('&') : ''), {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: text, signal: controller.signal
    }).then(function (r) {
      return r.text().then(function (t) {
        var data = null; try { data = JSON.parse(t); } catch (e) { /* not JSON */ }
        if (!r.ok) throw new Error((data && (data.detail || data.message || data.error)) || ('HTTP ' + r.status));
        if (!data) throw new Error('The server did not return a report.');
        return data;
      });
    }).then(function (data) {
      state.request = text; state.running = null;
      var secs = ((Date.now() - started) / 1000).toFixed(1);
      loadReport(data, 'live run · ' + new Date().toLocaleTimeString() + ' · ' + secs + 's');
    }).catch(function (e) {
      state.running = null; ui.busy(false);
      ui.fail(e.name === 'AbortError' ? 'Comparison cancelled.' : e.message);
    });
  }

  function runPanel() {
    var editor = h('textarea', {
      class: 'editor', spellcheck: 'false', autocapitalize: 'off', 'aria-label': 'Request body of POST /compare/custom',
      placeholder: JSON.stringify(EXAMPLE_REQUEST, null, 2)
    });
    editor.value = state.request || '';
    var status = h('div', { class: 'form-status', role: 'status' });
    var errorBox = h('div', { class: 'banner err', role: 'alert', style: 'display:none' });
    function validate() {
      if (!editor.value.trim()) { status.textContent = ''; status.className = 'form-status'; return; }
      var c = checkRequest(editor.value);
      if (c.error) { status.textContent = '✗ ' + c.error; status.className = 'form-status bad'; }
      else { status.textContent = '✓ ' + plural(c.body.paths.length, 'path') + ' · ' + c.body.sourceBaseUrl + ' → ' + c.body.targetBaseUrl; status.className = 'form-status ok'; }
    }
    editor.addEventListener('input', function () { state.request = editor.value; errorBox.style.display = 'none'; validate(); });
    validate();

    function select(label, id, opts) {
      var sel = h('select', { id: id, class: 'select' });
      opts.forEach(function (o) { sel.appendChild(h('option', { value: o[0], text: o[1] })); });
      return h('div', { class: 'field' }, h('label', { for: id, text: label }), sel);
    }
    var type = select('Type matching', 'opt-type', [['', 'Default'], ['STRICT', 'Strict'], ['LENIENT', 'Lenient ("11" = 11)']]);
    var nul = select('Null matching', 'opt-null', [['', 'Default'], ['STRICT', 'Strict'], ['LENIENT', 'Lenient (null = absent)']]);
    var order = select('Array order', 'opt-order', [['', 'Default'], ['false', 'Order does not matter'], ['true', 'Order is strict']]);
    var ids = h('div', { class: 'field' }, h('label', { for: 'opt-ids', text: 'Id properties' }),
      h('input', { id: 'opt-ids', class: 'input', placeholder: 'id,code (default: configured)', autocomplete: 'off' }));
    var go = h('button', { class: 'btn primary big', type: 'button' }, h('span', { class: 'btn-label', text: '▶ Run comparison' }));
    var cancel = h('button', { class: 'btn', type: 'button', style: 'display:none', text: 'Cancel', onclick: function () { if (state.running) state.running.abort(); } });
    var ui = {
      busy: function (on) { go.disabled = on; go.classList.toggle('loading', on); go.querySelector('.btn-label').textContent = on ? 'Comparing…' : '▶ Run comparison'; cancel.style.display = on ? '' : 'none'; if (on) errorBox.style.display = 'none'; },
      fail: function (msg) { errorBox.style.display = ''; clear(errorBox); errorBox.appendChild(h('b', { text: 'Could not run the comparison' })); errorBox.appendChild(h('span', { text: msg })); }
    };
    function value(id) { var el = document.getElementById(id); return el ? el.value.trim() : ''; }
    go.addEventListener('click', function () {
      runComparison(editor.value, { typeMatching: value('opt-type'), nullMatching: value('opt-null'), orderStrict: value('opt-order'), idProperties: value('opt-ids') }, ui);
    });
    editor.addEventListener('keydown', function (e) { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); go.click(); } });

    return h('div', { class: 'panel' },
      h('div', { class: 'panel-head' },
        h('div', null, h('h2', { text: 'Run a comparison' }), h('p', { class: 'sub', text: 'The JSON body of POST /compare/custom: both base URLs, the paths and, if needed, headers for each side.' })),
        h('button', { class: 'btn small', type: 'button', text: 'Insert an example', onclick: function () { editor.value = JSON.stringify(EXAMPLE_REQUEST, null, 2); state.request = editor.value; validate(); editor.focus(); } })),
      editor, status, errorBox,
      h('div', { class: 'form-grid' }, type, nul, order, ids),
      h('div', { class: 'row' }, go, cancel, h('span', { class: 'hint', text: 'Ctrl/⌘ + Enter to run. Header values are only sent to the services you name; they are not stored.' })));
  }

  function openPanel() {
    var paste = h('textarea', { class: 'editor short', spellcheck: 'false', placeholder: 'Paste the JSON returned by POST /compare, /compare/custom, /compare/files or /compare/json…', 'aria-label': 'Report JSON' });
    var fileInput = h('input', { type: 'file', accept: '.json,application/json', class: 'sr-only', id: 'file-input' });
    fileInput.addEventListener('change', function () { if (fileInput.files[0]) loadFile(fileInput.files[0]); });
    var runs = h('div', { class: 'runs-slot' });
    fetchJson('reports/runs').then(function (list) {
      if (!list || !list.length) { runs.appendChild(h('span', { class: 'hint', text: 'No saved run yet. Live runs are saved on the server and listed here.' })); return; }
      var sel = h('select', { class: 'select', 'aria-label': 'Saved runs' }, h('option', { value: '', text: 'Open a saved run…' }));
      list.forEach(function (r) { sel.appendChild(h('option', { value: r, text: r })); });
      sel.addEventListener('change', function () { if (sel.value) openRun(sel.value); });
      runs.appendChild(sel);
    }).catch(function () { /* the viewer also works without the server API */ });

    var drop = h('div', { class: 'drop' }, h('div', { class: 'drop-icon', text: '⇪' }),
      h('div', { class: 'drop-title', text: 'Drop a report .json file here' }),
      h('div', { class: 'row', style: 'justify-content:center' }, h('label', { class: 'btn primary', for: 'file-input', text: 'Choose a file…' }), fileInput,
        window.SAMPLE ? h('button', { class: 'btn', type: 'button', text: 'Try the sample', onclick: loadSample }) : null));
    ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function () { drop.classList.add('over'); }); });
    ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function () { drop.classList.remove('over'); }); });

    return h('div', { class: 'panel' },
      h('div', { class: 'panel-head' }, h('div', null, h('h2', { text: 'Open a report' }), h('p', { class: 'sub', text: 'Load the result of an earlier comparison: a saved run, a file, or pasted JSON.' }))),
      drop,
      h('div', { class: 'split' },
        h('div', null, h('h3', { text: 'Saved runs' }), runs),
        h('div', null, h('h3', { text: 'Paste a report' }), paste,
          h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'btn primary', type: 'button', text: 'Preview', onclick: function () { loadText(paste.value, 'pasted report'); } })))));
  }

  function landing() {
    var tab = state.startTab;
    var body = h('div', { class: 'tab-body' }, tab === 'run' ? runPanel() : openPanel());
    function segment(id, label) {
      return h('button', { class: 'seg-btn' + (tab === id ? ' on' : ''), type: 'button', role: 'tab', 'aria-selected': tab === id ? 'true' : 'false', text: label,
        onclick: function () { state.startTab = id; render(); } });
    }
    return h('div', { class: 'landing' },
      h('div', { class: 'landing-top' }, h('a', { class: 'brand', href: '#/' }, h('span', { class: 'brand-mark' }, svg('M3 3h4v10H3zM9 3h4v10H9z')), 'Comparison report'),
        h('span', { style: 'flex:1' }),
        state.stash ? h('button', { class: 'btn small', type: 'button', text: '← Back to the report', onclick: restoreStash }) : null,
        h('button', { class: 'btn small ghost', type: 'button', onclick: function () { var n = isDark() ? 'light' : 'dark'; applyTheme(n); store('viewer-theme', n); render(); }, text: isDark() ? '☀ Light' : '☾ Dark' })),
      h('div', { class: 'hero' }, h('h1', null, 'See exactly what ', h('span', { class: 'grad', text: 'differs' }), '.'),
        h('p', { text: 'Run a comparison between two services, or open an existing report. Get one page per path, every difference with its exact location, and the source and target side by side with the differing parts highlighted.' })),
      h('div', { class: 'seg', role: 'tablist' }, segment('run', '▶ Run a comparison'), segment('open', '📂 Open a report')),
      body);
  }

  render();
})();
