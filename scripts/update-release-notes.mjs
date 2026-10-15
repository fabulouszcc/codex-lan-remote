import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const tag = 'v0.3.0-preview';
const repo = process.env.GH_REPO || process.env.GITHUB_REPOSITORY;
if (!repo) throw Error('Set GH_REPO or GITHUB_REPOSITORY');
const gh = args => execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const release = JSON.parse(gh(['api', `repos/${repo}/releases/tags/${tag}`]));
const rows = [];
for (const name of ['codex-lan-desktop-0.3.0-preview.zip', 'codex-lan-0.2.4-preview.apk']) {
  const asset = release.assets.find(row => row.name === name);
  if (!asset) continue;
  let digest = asset.digest?.match(/^sha256:([a-f0-9]{64})$/i)?.[1];
  if (!digest) {
    const response = await fetch(asset.browser_download_url);
    if (!response.ok || !response.body) throw Error(`Cannot verify ${name}`);
    const hash = createHash('sha256');
    for await (const chunk of response.body) hash.update(chunk);
    digest = hash.digest('hex');
  }
  rows.push(`${digest.toLowerCase()}  ${name}`);
}
if (!rows.length) throw Error('No published installation packages found');
const introduction = (await readFile('docs/release-0.3.0.md', 'utf8')).split('\n## SHA-256')[0].trimEnd();
const notes = `${introduction}\n\n## SHA-256\n\n下载后可使用以下 SHA-256 核对文件完整性：\n\n\`\`\`text\n${rows.join('\n')}\n\`\`\`\n`;
const file = join(tmpdir(), `codex-lan-release-notes-${process.pid}.md`);
await writeFile(file, notes);
gh(['release', 'edit', tag, '--repo', repo, '--notes-file', file]);
// Remove only the two obsolete checksum downloads explicitly requested by the owner.
for (const name of ['SHA256SUMS.txt', 'SHA256SUMS-android.txt']) {
  if (release.assets.some(row => row.name === name)) gh(['release', 'delete-asset', tag, name, '--repo', repo, '--yes']);
}
console.log('Release introduction updated; installer packages retained.');
