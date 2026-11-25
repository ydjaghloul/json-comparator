/* One JSON document in its own tab: what a service returned. Built with text nodes only. */
(function () {
  'use strict';

  var MAX_LINES = 50000;
  var app = document.getElementById('app');
  var params = new URLSearchParams(location.search);
  var title = params.get('title') || 'JSON document';
  var calledUrl = params.get('url');
  var side = params.get('side');

  try { var theme = localStorage.getItem('viewer-theme'); if (theme) document.documentElement.setAttribute('data-theme', theme); } catch (e) { /* storage may be blocked */ }
  document.title = title;

  function h(tag, props) {
    var el = document.createElement(tag);
    Object.keys(props || {}).forEach(function (k) {
      var v = props[k]; if (v === null || v === undefined || v === false) return;
      if (k === 'class') el.className = v; else if (k === 'text') el.textContent = v;
      else if (k.indexOf('on') === 0) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v === true ? '' : v);
    });
    for (var i = 2; i < arguments.length; i++) { var c = arguments[i]; if (c !== null && c !== undefined && c !== false) el.appendChild(c.nodeType ? c : document.createTextNode(String(c))); }
    return el;
  }
  function toast(text) { var t = h('div', { class: 'toast', role: 'status', text: text }); document.body.appendChild(t); setTimeout(function () { t.remove(); }, 2500); }

  function shell(body) {
    app.textContent = '';
    var wrap = h('div', { class: 'docwrap' });
    [].concat(body).forEach(function (part) { if (part) wrap.appendChild(part); });
    app.appendChild(wrap);
  }
  function message(text, kind) { shell(h('div', { class: 'banner ' + (kind || 'info'), text: text })); }

  function copy(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(function () { toast('Copied'); }, function () { toast('Copy failed'); });
    else toast('Copy is not available here');
  }

  function show(doc, raw) {
    var text = raw !== undefined ? raw : JSON.stringify(doc, null, 2);
    var built = window.JsonView.buildLines(doc, new Map(), MAX_LINES);
    var code = h('div', { class: 'code tall', tabindex: '0', 'aria-label': title });
    var lineEls = [], lineText = [];
    var frag = document.createDocumentFragment();
    built.lines.forEach(function (ln, n) {
      var tx = h('span', { class: 'tx', style: 'padding-left:' + (ln.depth * 1.6) + 'ch' });
      var plain = '';
      if (ln.key !== null && ln.key !== undefined && ln.type !== 'close') { var k = JSON.stringify(String(ln.key)) + ': '; tx.appendChild(h('span', { class: 'k', text: k })); plain += k; }
      tx.appendChild(h('span', { class: ln.cls, text: ln.text })); plain += ln.text;
      if (ln.comma && ln.type !== 'open') { tx.appendChild(document.createTextNode(',')); }
      var line = h('div', { class: 'line' }, h('span', { class: 'no', text: String(n + 1) }), h('span', { class: 'mk' }), tx);
      lineEls.push(line); lineText.push(plain.toLowerCase()); frag.appendChild(line);
    });
    code.appendChild(frag);

    // find
    var found = [], current = -1;
    var count = h('span', { class: 'sub', text: '' });
    var find = h('input', { class: 'search', type: 'search', placeholder: 'Find in this document…', 'aria-label': 'Find' });
    function go(n) {
      if (!found.length) return;
      if (current >= 0) lineEls[found[current]].classList.remove('active');
      current = (n + found.length) % found.length;
      var el = lineEls[found[current]]; el.classList.add('active');
      code.scrollTop = Math.max(0, el.offsetTop - code.clientHeight / 2);
      count.textContent = (current + 1) + ' / ' + found.length;
    }
    find.addEventListener('input', function () {
      lineEls.forEach(function (e) { e.classList.remove('hl-chg', 'direct', 'active'); });
      found = []; current = -1;
      var q = find.value.trim().toLowerCase();
      if (q) lineText.forEach(function (t, i) { if (t.indexOf(q) >= 0) { found.push(i); lineEls[i].classList.add('hl-chg', 'direct'); } });
      count.textContent = q ? (found.length ? found.length + ' found' : 'no match') : '';
      if (found.length) go(0);
    });
    find.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); go(current + (e.shiftKey ? -1 : 1)); } });

    var blobUrl = null;
    var head = h('header', { class: 'dochead' },
      h('div', { class: 'docmeta' },
        h('span', { class: 'badge ' + (side === 'target' ? 'MISSING_IN_SOURCE' : 'MISSING_IN_TARGET'), text: side ? side.toUpperCase() : 'JSON' }),
        h('h1', { text: title.replace(/^(Source|Target) · /, '') }),
        calledUrl ? h('div', { class: 'docurl' }, h('code', { class: 'mono', text: calledUrl }), h('button', { class: 'btn small', type: 'button', text: 'Copy URL', onclick: function () { copy(calledUrl); } })) : null),
      h('div', { class: 'docbar' },
        find, count,
        h('span', { class: 'sub', text: built.lines.length + ' lines' + (built.truncated ? ' (cut)' : '') }),
        h('button', { class: 'btn small', type: 'button', text: 'Copy JSON', onclick: function () { copy(text); } }),
        h('button', { class: 'btn small', type: 'button', text: 'Download', onclick: function () {
          if (blobUrl) URL.revokeObjectURL(blobUrl);
          blobUrl = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
          var a = h('a', { href: blobUrl, download: (side || 'document') + '.json' }); document.body.appendChild(a); a.click(); a.remove();
        } })));
    shell([head, h('div', { class: 'card docbody' }, built.truncated ? h('div', { class: 'banner warn', style: 'margin:10px', text: 'Showing the first ' + MAX_LINES + ' lines only.' }) : null, code)]);
  }

  /* ---------- where the document comes from ---------- */
  var path = params.get('path');
  if (path) {
    message('Loading…');
    fetch('reports/file?path=' + encodeURIComponent(path), { headers: { Accept: 'application/json' } }).then(function (r) {
      if (!r.ok) throw new Error(r.status === 404 ? 'This document is no longer saved on the server.' : 'The server answered HTTP ' + r.status + '.');
      return r.text();
    }).then(function (raw) {
      var doc; try { doc = JSON.parse(raw); } catch (e) { throw new Error('The saved file is not valid JSON.'); }
      show(doc, raw);
    }).catch(function (e) { message(e.message, 'err'); });
  } else if (params.get('mem') && window.opener) {
    message('Loading…');
    window.addEventListener('message', function (e) {
      if (e.origin !== location.origin || !e.data || e.data.type !== 'doc') return;
      show(e.data.doc);
    });
    window.opener.postMessage({ type: 'doc-ready' }, location.origin);
  } else {
    message('Nothing to show. Open this document from the report viewer.', 'warn');
  }
})();
