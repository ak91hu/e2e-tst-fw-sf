import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// npm runs only while building. The published Java CLI embeds this locked installation.
const directory = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(directory, '../target/classes/allure-engine/allure-commandline.zip');
const stamp = path.resolve(directory, '../target/allure-bundle.sha256');
const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'));
const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 26 || major === 26 && minor < 10) throw new Error('Allure build requires Node.js 26.10.0 or later');
const digest = crypto.createHash('sha256');
for (const file of ['package.json', 'package-lock.json', 'bundle.mjs']) digest.update(fs.readFileSync(path.join(directory, file)));
const key = digest.digest('hex');
const previous = fs.existsSync(stamp) ? JSON.parse(fs.readFileSync(stamp, 'utf8')) : null;
const archiveHash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
if (fs.existsSync(target) && previous?.key === key && previous.sha256 === archiveHash(target)) {
  process.stdout.write('Locked Allure engine bundle is current\n');
} else {
  // npm.cmd is a fixed build command; no user-controlled shell arguments are interpolated.
  const install = process.platform === 'win32'
    ? spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', 'npm.cmd ci --ignore-scripts --no-audit --no-fund'], { cwd: directory, stdio: 'inherit', windowsHide: true })
    : spawnSync('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: directory, stdio: 'inherit' });
  if (install.error || install.status !== 0) throw new Error('Locked Allure dependency installation failed');
  const installed = JSON.parse(fs.readFileSync(path.join(directory, 'node_modules/allure/package.json'), 'utf8'));
  if (installed.version !== manifest.dependencies.allure) throw new Error('Allure installation does not match the exact manifest pin');
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const jar = path.join(process.env.JAVA_HOME || '', 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar');
  const archive = spawnSync(jar, ['--create', '--no-manifest', '--file', target, '-C', directory, 'node_modules', '-C', directory, 'package.json', '-C', directory, 'package-lock.json'], { stdio: 'inherit', windowsHide: true });
  if (archive.error || archive.status !== 0) throw new Error('Allure archive creation failed; set JAVA_HOME to a JDK');
  fs.writeFileSync(stamp, JSON.stringify({ key, sha256: archiveHash(target) }));
  process.stdout.write(`Bundled Allure ${installed.version} with its locked dependencies\n`);
}
