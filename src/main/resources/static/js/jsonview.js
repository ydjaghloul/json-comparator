/* Path-aware JSON rendering: every rendered line knows its JSON path, so differences can be highlighted. */
(function () {
  'use strict';

  var IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

  /** Same notation as the server: $.a.b[0]['odd name'] */
  function field(path, name) {
    return path + (IDENT.test(name) ? '.' + name : "['" + name.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "']");
  }
  function index(path, i) { return path + '[' + i + ']'; }

  function kindOf(v) {
    if (v === null) return 'null';
    return typeof v;
  }

  /**
   * Flattens a value into display lines (pretty printed, 2 spaces).
   * marks: Map(path -> {kind: 'del'|'add'|'chg', ids: [diff ids]}).
   */
  function buildLines(value, marks, maxLines) {
    var lines = [];
    var truncated = false;

    function push(line) {
      if (lines.length >= maxLines) { truncated = true; return; }
      lines.push(line);
    }

    function walk(v, path, depth, key, last, inherited) {
      if (truncated) return;
      var own = marks.get(path) || null;
      var effective = own || inherited;
      var base = { depth: depth, path: path, key: key, comma: !last, mark: own, inherited: own ? null : inherited };
      if (v !== null && typeof v === 'object') {
        var isArr = Array.isArray(v);
        var keys = isArr ? v.map(function (_, i) { return i; }) : Object.keys(v);
        if (keys.length === 0) {
          push(Object.assign({}, base, { type: 'leaf', text: isArr ? '[]' : '{}', cls: 'punct' }));
          return;
        }
        push(Object.assign({}, base, { type: 'open', text: isArr ? '[' : '{', cls: 'punct', comma: false }));
        keys.forEach(function (k, n) {
          walk(v[k], isArr ? index(path, k) : field(path, k), depth + 1, isArr ? null : k,
            n === keys.length - 1, effective);
        });
        push(Object.assign({}, base, { type: 'close', text: isArr ? ']' : '}', cls: 'punct', key: null }));
      } else {
        push(Object.assign({}, base, { type: 'leaf', text: JSON.stringify(v), cls: kindOf(v) }));
      }
    }

    walk(value, '$', 0, null, true, null);
    return { lines: lines, truncated: truncated };
  }

  window.JsonView = { field: field, index: index, buildLines: buildLines };
})();
