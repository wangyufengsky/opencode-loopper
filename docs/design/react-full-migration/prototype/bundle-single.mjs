// Packaging only: keep the original design HTML, CSS and JS unchanged.
// No product imports, downloads or candidate framework installation.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { strict as assert } from 'node:assert';

const directory = dirname(fileURLToPath(import.meta.url));
const [html, css, js] = await Promise.all(
  ['index.html', 'prototype.css', 'prototype.js'].map(name => readFile(join(directory, name), 'utf8'))
);
// Fail rather than silently rewrite source bytes if future source cannot be inlined as-is.
assert(!/<\/style\s*[>\s]/i.test(css), 'CSS contains an HTML style terminator');
assert(!/<\/script\s*[>\s]/i.test(js), 'JS contains an HTML script terminator');
assert.equal((html.match(/<link rel="stylesheet" href="prototype\.css">/g) || []).length, 1);
assert.equal((html.match(/<script src="prototype\.js" defer><\/script>/g) || []).length, 1);
assert.equal((html.match(/<meta http-equiv="Content-Security-Policy" content="[^"]*">/g) || []).length, 1);
assert.equal((html.match(/<\/body>/g) || []).length, 1);

const scriptHash = createHash('sha256').update(js).digest('base64');
const csp = `default-src 'none'; script-src 'sha256-${scriptHash}'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'`;
const bundled = html
  .replace(/<meta http-equiv="Content-Security-Policy" content="[^"]*">/, `<meta http-equiv="Content-Security-Policy" content="${csp}">`)
  .replace('<link rel="stylesheet" href="prototype.css">', () => `<style>${css}</style>`)
  .replace('  <script src="prototype.js" defer></script>\n', '')
  // Inline defer would execute too early. Run the same bytes after the existing body DOM.
  .replace('</body>', () => `  <script>${js}</script>\n</body>`);
assert(!/<(?:script|link|img|iframe)\b[^>]*\b(?:src|href)=/i.test(bundled), 'External resource element remains');
assert.equal(bundled.match(/<style>([\s\S]*?)<\/style>/)[1], css);
assert.equal(bundled.match(/<script>([\s\S]*?)<\/script>/)[1], js);
const target = join(directory, 'review-single.html');
if (process.argv.includes('--check')) {
  assert.equal(await readFile(target, 'utf8'), bundled, 'Single HTML is stale; regenerate it');
  console.log('Single HTML is reproducible; inline CSS and JS bytes equal the original sources.');
} else {
  await writeFile(target, bundled);
  console.log(JSON.stringify({ file: 'review-single.html', bytes: Buffer.byteLength(bundled), sha256: createHash('sha256').update(bundled).digest('hex') }));
}
