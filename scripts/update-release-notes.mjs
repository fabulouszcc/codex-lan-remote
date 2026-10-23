import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const tag = process.env.CODEX_LAN_RELEASE_TAG || 'v0.3.0-preview';
if (!/^v\d+\.\d+\.\d+(?:-preview)?$/.test(tag)) throw Error('Invalid release tag');
const version = tag.slice(1).replace(/-preview$/, '');
const suffix = tag.endsWith('-preview') ? '-preview' : '';
const repo = process.env.GH_REPO || process.env.GITHUB_REPOSITORY;
if (!repo) throw Error('Set GH_REPO or GITHUB_REPOSITORY');
const gh = args => execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const release = JSON.parse(gh(['api', `repos/${repo}/releases/tags/${tag}`]));
const rows = [];
for (const name of [`codex-lan-desktop-${version}${suffix}.zip`, `codex-lan-${version === '0.3.0' ? '0.2.4' : version}${suffix}.apk`]) {
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
if (rows.length !== 2) throw Error('Both installation packages must exist before updating release notes');
const introduction = (await readFile(`docs/release-${version}.md`, 'utf8')).split('\n## SHA-256')[0].trimEnd();
const notes = `${introduction}\n\n## SHA-256\n\n下载后可使用以下 SHA-256 核对文件完整性：\n\n\`\`\`text\n${rows.join('\n')}\n\`\`\`\n`;
const file = join(tmpdir(), `codex-lan-release-notes-${process.pid}.md`);
await writeFile(file, notes);
gh(['release', 'edit', tag, '--repo', repo, '--notes-file', file]);
// Remove only the two obsolete checksum downloads explicitly requested by the owner.
for (const name of ['SHA256SUMS.txt', 'SHA256SUMS-android.txt']) {
  if (release.assets.some(row => row.name === name)) gh(['release', 'delete-asset', tag, name, '--repo', repo, '--yes']);
}
console.log('Release introduction updated; installer packages retained.');
