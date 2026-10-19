import { readFileSync } from 'node:fs';

// Installed bundles and source checkouts use the same plugin manifest version.
export const desktopVersion = (() => {
  for (const relative of ['../.codex-plugin/plugin.json', '../plugins/codex-lan-desktop/.codex-plugin/plugin.json']) {
    try { const value=JSON.parse(readFileSync(new URL(relative,import.meta.url),'utf8')).version;if(/^\d+\.\d+\.\d+(?:-[\w.-]+)?$/.test(value))return value; } catch {}
  }
  return '';
})();
