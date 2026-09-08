import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { projectRoot, planVersion, currentVersion } from './release-version.mjs';

export function checkInstructions(root = projectRoot) {
  const limits = [['AGENTS.md', 16 * 1024], ['src/AGENTS.md', 12 * 1024], ['frontend/AGENTS.md', 12 * 1024]];
  const errors = [];
  const routed = new Set(limits.map(([path]) => resolve(root, path)));
  for (const [path, limit] of limits) {
    const bytes = readFileSync(resolve(root, path));
    if (bytes.length > limit) errors.push(`${path}: ${bytes.length} bytes exceeds ${limit}`);
  }
  // Follow one level of AGENTS Markdown links. Check file targets only, not anchors,
  // URLs, code examples, backtick paths or every historical document in the repo.
  for (const file of [...routed]) for (const match of readFileSync(file, 'utf8').matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1].split('#')[0];
    if (!target || /^(?:[a-z]+:|\/)/i.test(target)) continue;
    const path = resolve(dirname(file), target);
    if (path.endsWith('.md') && existsSync(path)) routed.add(path);
  }
  for (const file of routed) {
    const text = readFileSync(file, 'utf8').replace(/```[\s\S]*?```/g, '');
    for (const match of text.matchAll(/\]\(([^)]+)\)/g)) {
      const target = match[1].split('#')[0];
      if (!target || /^(?:[a-z]+:|\/)/i.test(target)) continue;
      if (!existsSync(resolve(dirname(file), target))) errors.push(`${file}: missing link target ${target}`);
    }
  }
  if (errors.length) throw new Error(errors.join('\n'));
  return routed.size;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    planVersion(projectRoot);
    console.log(`Project checks passed: ${currentVersion()}, instruction sizes, links in ${checkInstructions()} routed documents`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
