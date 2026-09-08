import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { projectRoot } from '../release-version.mjs';

test('distribution archives reject corrupt or wrong-platform JDKs and preserve launchable layouts', () => {
  execFileSync(process.platform === 'win32' ? 'python' : 'python3',
    ['scripts/test-package-distributions.py'], { cwd: projectRoot, stdio: 'inherit', timeout: 120000 });
});
