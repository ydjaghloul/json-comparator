/*
 * Browser test of the report viewer, run against a running application (not part of the Maven build).
 *
 *   CLIENT_ID=x CLIENT_SECRET=x mvn spring-boot:run          # in one terminal
 *   npm i playwright && npx playwright install chromium       # once
 *   node ui-tests/viewer.e2e.js                               # BASE_URL=http://localhost:8080 by default
 *
 * It starts two tiny fake services (source and target) itself and drives the UI like a user: runs a comparison
 * from the form, opens saved runs and pasted reports, and checks the highlighting and the error handling.
 * Set SCREENSHOTS=/some/dir to also save screenshots.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const FAKE_PORT = parseInt(process.env.FAKE_PORT || '18091', 10);
const SHOTS = process.env.SCREENSHOTS;

const docs = {
  '/s/orders/42': { orderId: 42, total: 100, customer: { name: 'Ada', email: null }, tags: ['vip', 'eu', 'new'],
    items: [{ sku: 'A-100', qty: 1 }, { sku: 'B-200', qty: 2, discount: 5 }, { sku: 'C-300', qty: 3 }] },
  '/t/orders/42': { orderId: 42, total: '100', customer: { name: 'Ada', email: 'ada@example.com' }, tags: ['vip', 'eu', 'beta'],
    items: [{ sku: 'B-200', qty: 3 }, { sku: 'A-100', qty: 1 }, { sku: 'D-400', qty: 1 }], note: 'gift' },
  '/s/products/1': { id: 1, name: 'Laptop' }, '/t/products/1': { name: 'Laptop', id: 1 },
  '/s/orders/43': { orderId: 43 },
};
const received = { source: [], target: [] };
const fake = http.createServer((req, res) => {
  const side = req.url.startsWith('/s/') ? 'source' : 'target';
  received[side].push(req.headers);
  if (req.url === '/t/orders/43') { res.writeHead(500); return res.end(); }
  const d = docs[req.url];
  res.writeHead(d ? 200 : 404, { 'content-type': 'application/json' });
  res.end(d ? JSON.stringify(d) : '');
});

let failures = 0;
function check(name, condition, detail) {
  if (condition) console.log('  ok   ' + name);
  else { failures++; console.log('  FAIL ' + name + (detail !== undefined ? ' -> ' + JSON.stringify(detail) : '')); }
}
async function shot(page, name) { if (SHOTS) { fs.mkdirSync(SHOTS, { recursive: true }); await page.screenshot({ path: path.join(SHOTS, name + '.png') }); } }

(async () => {
  await new Promise(r => fake.listen(FAKE_PORT, '127.0.0.1', r));
  const request = {
    sourceBaseUrl: `http://127.0.0.1:${FAKE_PORT}/s`, targetBaseUrl: `http://127.0.0.1:${FAKE_PORT}/t`,
    paths: ['/orders/42', '/products/1', '/orders/43'],
    sourceHeaders: { Authorization: 'Bearer tok-ui-123' }, targetHeaders: { 'X-Api-Key': 'key-ui-456' },
  };
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1360, height: 900 } });
  const problems = [];
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !/status of 400/.test(m.text())) problems.push('console: ' + m.text()); });

  console.log('Start page');
  await page.goto(BASE + '/');
  check('the run form is shown first', await page.locator('textarea.editor[aria-label*="compare/custom"]').count() === 1);
  await page.fill('textarea.editor', '{nope');
  check('invalid JSON is reported while typing', /Not valid JSON/.test(await page.locator('.form-status').textContent()));
  await page.fill('textarea.editor', JSON.stringify({ sourceBaseUrl: 'ftp://x', targetBaseUrl: 'http://y', paths: ['/a'] }));
  check('a bad URL is reported', /sourceBaseUrl must be/.test(await page.locator('.form-status').textContent()));
  await page.click('button:has-text("Run comparison")');
  check('nothing is sent when the request is invalid', await page.locator('.banner.err').isVisible() && await page.locator('.tiles').count() === 0);
  await page.click('button:has-text("Insert an example")');
  check('the example can be inserted', /source.example.com/.test(await page.inputValue('textarea.editor')));
  await shot(page, '1-run-form');

  console.log('Run a comparison from the form');
  await page.fill('textarea.editor', JSON.stringify(request, null, 2));
  check('a valid request is confirmed', /✓ 3 paths/.test(await page.locator('.form-status').textContent()));
  await page.selectOption('#opt-type', 'LENIENT');
  await page.selectOption('#opt-order', 'false');
  await page.fill('#opt-ids', 'sku');
  await page.click('button:has-text("Run comparison")');
  await page.waitForSelector('.tiles');
  const opts = await page.locator('.opts .opt').allTextContents();
  check('the options chosen in the form were used', opts.some(o => /typeMatching\s*LENIENT/.test(o)) && opts.some(o => /orderStrict\s*false/.test(o)), opts);
  check('the overview lists the three paths', await page.locator('.path-item').count() === 3);
  check('headers of the form reached each service', received.source.some(h => h.authorization === 'Bearer tok-ui-123')
    && received.target.some(h => h['x-api-key'] === 'key-ui-456') && !received.target.some(h => h.authorization), received);
  check('the report itself does not contain the header values', !(await page.content()).includes('tok-ui-123'));
  await shot(page, '2-overview');

  console.log('Path page');
  await page.click('.path-item >> nth=0');
  await page.waitForSelector('.diff');
  const kinds = await page.locator('.diff .badge').allTextContents();
  check('differences are listed', kinds.length >= 4, kinds);
  check('the location names the array element by id', (await page.locator('.loc .el').allTextContents()).some(t => t === '[sku=B-200]'));
  await page.click('.tab >> text=Side by side');
  await page.waitForSelector('.line.direct');
  await page.waitForTimeout(300);
  const src = await page.locator('.pane >> nth=0 >> .line.direct').allTextContents();
  const tgt = await page.locator('.pane >> nth=1 >> .line.direct').allTextContents();
  check('source side highlights what is only in source / differs', src.some(t => /discount/.test(t)) && src.some(t => /"new"/.test(t)), src);
  check('target side highlights what is only in target / differs', tgt.some(t => /beta/.test(t)) && tgt.some(t => /note/.test(t)), tgt);
  const moved = await page.evaluate(() => ({
    s: [...document.querySelectorAll('.pane:nth-child(1) .line.direct')].find(l => /qty/.test(l.textContent) && /"qty": 2/.test(l.textContent))?.querySelector('.no').textContent,
    t: [...document.querySelectorAll('.pane:nth-child(2) .line.direct')].find(l => /"qty": 3/.test(l.textContent))?.querySelector('.no').textContent }));
  check('a moved element is highlighted at its own line on each side', moved.s && moved.t && moved.s !== moved.t, moved);
  await page.click('text=Next difference'); await page.click('text=Next difference');
  check('next difference moves through the list (starts on 1, two clicks reach 3)', /^3 \/ \d+/.test((await page.locator('.nav-diff .sub').nth(1).textContent()).trim()));
  await shot(page, '3-side-by-side');
  await page.click('.path-item >> nth=2');
  check('an error path explains what failed', /HTTP 500/.test(await page.locator('.banner.err').textContent()));

  console.log('A rejected request');
  await page.click('button:has-text("New comparison")');
  check('the form comes back with the previous request', (await page.inputValue('textarea.editor')).includes('tok-ui-123'));
  check('the current report can be restored', await page.locator('button:has-text("Back to the report")').count() === 1);
  const bad = Object.assign({}, request, { sourceHeaders: { Host: 'evil' } });
  await page.fill('textarea.editor', JSON.stringify(bad));
  await page.click('button:has-text("Run comparison")');
  await page.waitForSelector('.banner.err');
  const msg = await page.locator('.banner.err').textContent();
  check('the server message is shown', /must not set the header Host/.test(msg), msg);
  check('and the value is not echoed', !/evil/.test(msg));
  check('the button is usable again', await page.locator('button:has-text("Run comparison")').isEnabled());
  await shot(page, '4-rejected');
  await page.click('button:has-text("Back to the report")');
  await page.waitForSelector('.tiles');
  check('back to the report', await page.locator('.path-item').count() === 3);

  console.log('Open a report');
  await page.click('button:has-text("Open a report")');
  await page.waitForSelector('select[aria-label="Saved runs"]');
  const runs = await page.locator('select[aria-label="Saved runs"] option').count();
  check('the run just made is listed', runs >= 2, runs);
  await page.selectOption('select[aria-label="Saved runs"]', { index: 1 });
  await page.waitForSelector('.tiles');
  check('a saved run opens', await page.locator('.path-item').count() === 3);
  await page.click('.path-item >> nth=0'); await page.click('.tab >> text=Side by side'); await page.waitForSelector('.line.direct');
  check('its documents are loaded from the server', await page.locator('.pane >> nth=0 >> .line').count() > 10);

  await page.click('button:has-text("Open a report")');
  const file = path.join(require('os').tmpdir(), 'viewer-e2e-report.json');
  fs.writeFileSync(file, JSON.stringify({ summary: { total: 1 }, results: [{ path: '/from/file', status: 'IDENTICAL', summary: 'identical' }] }));
  await page.setInputFiles('#file-input', file);
  await page.waitForSelector('.tiles');
  check('a report file opens', (await page.locator('.path-item .p').allTextContents()).join() === '/from/file');
  await page.click('button:has-text("Open a report")');
  await page.fill('textarea.editor', JSON.stringify({ summary: {}, results: [{ path: '<img src=x onerror=window.__x=1>', status: 'DIFFERENT', summary: 's', differenceCount: 1,
    differences: [{ jsonPath: '$.a', type: 'VALUE_MISMATCH', source: '<script>window.__x=1</script>', target: 'ok', message: '<b>m</b>' }] }] }));
  await page.click('button:has-text("Preview")');
  await page.waitForSelector('.tiles'); await page.click('.path-item >> nth=0'); await page.waitForSelector('.diff');
  check('a hostile report cannot inject script or markup', (await page.evaluate(() => window.__x)) === undefined && await page.locator('#app img, #app script').count() === 0);
  await page.click('button:has-text("Open a report")');
  await page.fill('textarea.editor', '{nope'); await page.click('button:has-text("Preview")');
  check('an invalid report is refused', /Not valid JSON/.test(await page.locator('.toast').first().textContent()));

  console.log('Theme and layout');
  await page.click('button:has-text("Dark")');
  check('dark theme applies', await page.evaluate(() => document.documentElement.getAttribute('data-theme')) === 'dark');
  await page.setViewportSize({ width: 390, height: 800 });
  check('no horizontal scroll on a phone', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await shot(page, '5-phone-dark');

  check('no console or page errors', problems.length === 0, problems);
  await browser.close(); fake.close();
  console.log(failures ? `\n${failures} check(s) FAILED` : '\nAll checks passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
