// Design packaging only. Inline exact local source bytes, without product imports.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { strict as assert } from 'node:assert';

const directory = dirname(fileURLToPath(import.meta.url));
const names = ['index.html', 'styles.css', 'semantic-registry.js', 'app.js'];
const [html, css, registry, app] = await Promise.all(names.map(name => readFile(join(directory, name), 'utf8')));
assert(!/<\/style\s*[>\s]/i.test(css), 'Cannot safely inline CSS terminator');
for (const source of [registry, app]) assert(!/<\/script\s*[>\s]/i.test(source), 'Cannot safely inline JS terminator');
const styleTag = '<link rel="stylesheet" href="styles.css">';
const registryTag = '<script src="semantic-registry.js" defer></script>';
const appTag = '<script src="app.js" defer></script>';
for (const tag of [styleTag, registryTag, appTag]) assert.equal(html.split(tag).length, 2, `Expected exactly one ${tag}`);
assert.equal((html.match(/<meta http-equiv="Content-Security-Policy" content="[^"]*">/g) || []).length, 1);
assert.equal((html.match(/<\/body>/g) || []).length, 1);
const hash = source => createHash('sha256').update(source).digest('base64');
const policy = `default-src 'none'; script-src 'sha256-${hash(registry)}' 'sha256-${hash(app)}'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'`;
const bundled = html
  .replace(/<meta http-equiv="Content-Security-Policy" content="[^"]*">/, `<meta http-equiv="Content-Security-Policy" content="${policy}">`)
  .replace(styleTag, () => `<style>${css}</style>`)
  .replace(`  ${registryTag}`, '').replace(`  ${appTag}`, '')
  .replace('</body>', () => `<script>${registry}</script>\n<script>${app}</script>\n</body>`);
assert(!/<(?:script|link|img|iframe)\b[^>]*\b(?:src|href)=/i.test(bundled), 'External resource element remains');
assert.equal(bundled.match(/<style>([\s\S]*?)<\/style>/)[1], css);
assert.deepEqual([...bundled.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]), [registry, app]);
const target = join(directory, 'review-single.html');
if (process.argv.includes('--check')) {
  assert.equal(await readFile(target, 'utf8'), bundled, 'Single HTML stale; regenerate');
  console.log('Desktop single HTML reproducible; exact CSS/registry/app bytes; no resource links.');
} else {
  await writeFile(target, bundled);
  console.log(JSON.stringify({ file: 'review-single.html', bytes: Buffer.byteLength(bundled), sha256: createHash('sha256').update(bundled).digest('hex') }));
}
