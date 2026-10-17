import { join } from 'node:path';
import { dataRoot } from './client.mjs';
import { readJson, atomicJson } from './config.mjs';
const index = process.argv.indexOf('--data');
const root = index < 0 ? dataRoot() : process.argv[index + 1];
const config = await readJson(join(root, 'config.local.json'), null);
await atomicJson(join(root,'background-status.local.json'),{phase:'configured',enabled:config?.autoStart===true});
if (config?.autoStart === true) {
  await import('./daemon.mjs');
  await atomicJson(join(root,'background-status.local.json'),{phase:'ready',pid:process.pid});
}
