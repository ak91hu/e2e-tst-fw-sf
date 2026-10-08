import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = [];
function walk(directory, extensions) {
  for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, item.name);
    if (item.isDirectory()) walk(target, extensions);
    else if (extensions.includes(path.extname(item.name))) files.push(target);
  }
}
files.push(path.join(root, 'README.md'), path.join(root, 'AGENTS.md'), path.join(root, 'sf-e2e.yaml'), path.join(root, 'ai-workflow.example.yaml'));
walk(path.join(root, 'docs'), ['.md']);
walk(path.join(root, '.github', 'workflows'), ['.yml']);
walk(path.join(root, 'scripts'), ['.mjs', '.cjs', '.java']);
for (const item of fs.readdirSync(root, { withFileTypes: true })) {
  if (item.isDirectory() && item.name.startsWith('sf-e2e-')) {
    const moduleReadme = path.join(root, item.name, 'README.md');
    if (fs.existsSync(moduleReadme)) files.push(moduleReadme);
    const main = path.join(root, item.name, 'src', 'main');
    if (fs.existsSync(main)) walk(main, ['.java', '.js']);
  }
}
// A repeatable vocabulary check complements human review. Localized SUT fixtures are deliberately excluded.
const hungarian = /(?<![\p{L}])(?:hiba|hibák|képernyő|teszteset|futtatás|beállítás|állapot|sikeres|ellenőrzés|jelentés|keretrendszer|mentés|módosítás|adatok|jelszó|felhasználó|nincs|vagy)(?![\p{L}])/giu;
const failures = [];
let links = 0;
function anchors(text) {
  const used = new Map();
  return new Set([...text.matchAll(/^#{1,6}\s+(.+)$/gm)].map(match => {
    const name = match[1].toLowerCase().replace(/[`*_]/g, '').replace(/[^\p{L}\p{N}\s_-]/gu, '').trim().replace(/\s+/g, '-');
    const ordinal = used.get(name) || 0; used.set(name, ordinal + 1);
    return name + (ordinal ? `-${ordinal}` : '');
  }));
}
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  const relative = path.relative(root, file);
  const vocabularyText = file.endsWith('check-project-language.mjs') ? text.replace(/^const hungarian =.*$/gm, '') : text;
  for (const match of vocabularyText.matchAll(hungarian)) failures.push(`${relative}: non-English framework vocabulary at character ${match.index}`);
  if (file.endsWith('.md')) {
    const fences = (text.match(/^```/gm) || []).length;
    if (fences % 2) failures.push(`${relative}: unclosed Markdown code block`);
    for (const match of text.matchAll(/(?<!!)\[[^\]]+\]\(([^)]+)\)/g)) {
      const target = match[1].replace(/^<|>$/g, '');
      if (/^(?:https?:|mailto:)/.test(target)) continue;
      links++;
      const [local, anchor] = target.split('#');
      const destination = local ? path.resolve(path.dirname(file), local) : file;
      if (!fs.existsSync(destination)) failures.push(`${relative}: missing documentation target ${local}`);
      else if (anchor && destination.endsWith('.md') && !anchors(fs.readFileSync(destination, 'utf8')).has(anchor)) failures.push(`${relative}: missing heading anchor ${target}`);
    }
  }
}
console.log(JSON.stringify({ status: failures.length ? 'FAILED' : 'PASSED', filesChecked: files.length, localLinksChecked: links, scope: 'Root agent guidance, public docs, module READMEs, configuration, CI and production sources; human-reviewed English messages. Localized app/test data excluded.', failures }, null, 2));
if (failures.length) process.exitCode = 1;
