/* Real Minecraft acceptance test. Never points at an existing world or server. */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const mineflayer = require('mineflayer');
const YAML = require('yaml');

const root = path.resolve(__dirname, '../..');
const paperSHA = '5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba';
const paperURL = `https://fill-data.papermc.io/v1/objects/${paperSHA}/paper-1.21.11-132.jar`;
const port = Number(process.env.GENECRAFT_TEST_PORT || 25575);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const results = [];
let server, bridge, bot, dir, log = '', allLogs = '', messages = [], serverLog;
const state = () => {
  try { return YAML.parse(fs.readFileSync(path.join(dir, 'plugins/GeneCraft/config.yml'), 'utf8')) || {}; }
  catch { return {}; }
};
// Agent state is keyed by persistent entity UUID. Resolve names from actual entity NBT.
const ids = {};
const work = name => state().agents?.[ids[name]]?.job;
const supplies = () => state().owners?.[bot.player.uuid]?.supplies || {};
function record(name) { results.push(name); console.log(`PASS ${name}`); }
async function until(label, predicate, timeout = 45000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (server && server.exitCode !== null) throw Error(`Server exited (${server.exitCode}): ${log.slice(-3000)}`);
    const result = predicate();
    if (result) return result;
    await sleep(100);
  }
  throw Error(`Timed out: ${label}\nChat: ${messages.slice(-12).join('\n')}\nState: ${JSON.stringify(state())}`);
}
async function consoleCommand(command) {
  server.stdin.write(command + '\n');
  await sleep(150);
}
async function chat(command) { bot.chat(command); await sleep(350); }
async function connect() {
  bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'GeneCraftTest', auth: 'offline', version: '1.21.11' });
  bot.on('messagestr', text => { messages.push(text); console.log('CHAT ' + text); });
  bot.on('error', e => console.log('BOT ' + e.message));
  bot.on('kicked', reason => console.log('KICK ' + JSON.stringify(reason)));
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Simulated player did not spawn')), 45000);
    bot.once('spawn', () => { clearTimeout(timer); resolve(); });
  });
  await consoleCommand('op GeneCraftTest');
  await consoleCommand('gamemode creative GeneCraftTest');
}
async function start() {
  log = '';
  server = spawn(process.env.JAVA || 'java', ['-Xms512M', '-Xmx1536M', '-jar', 'paper.jar', '--nogui'], {
    cwd: dir, env: { ...process.env, GENECRAFT_HOME: path.join(dir, 'bridge-home') },
  });
  for (const stream of [server.stdout, server.stderr]) stream.on('data', chunk => {
    log += chunk.toString(); allLogs += chunk.toString(); serverLog.write(chunk);
  });
  server.on('error', err => { log += String(err); });
  await until('Paper ready', () => log.includes('Done (') && log.includes('GeneCraft ready'), 180000);
  await connect();
}
async function stop() {
  if (bot) { bot.quit(); bot = null; await sleep(500); }
  if (server && server.exitCode === null) {
    server.stdin.write('stop\n');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { server.kill('SIGTERM'); reject(Error('Paper shutdown timed out')); }, 30000);
      server.once('exit', () => { clearTimeout(timer); resolve(); });
    });
  }
  server = null;
}
async function spawnAgent(name) {
  await chat(`/genecraft spawn ${name} villager`);
  // Query the real server entity, including its UUID. Config itself need not store a display name.
  // Use bot-visible UUIDs, excluding previously spawned agents; no production test hooks.
  ids[name] = await until(`${name} entity visible`, () => Object.values(bot.entities)
    .find(e => e.name === 'villager' && e.uuid && !Object.values(ids).includes(e.uuid))?.uuid);
}
async function teleport(x, z, y = 65) {
  await consoleCommand(`tp GeneCraftTest ${x} ${y} ${z} 0 0`);
  await until('player teleport', () => bot.entity.position.distanceTo({ x, y, z }) < 1);
}
async function tree(x, z) {
  await consoleCommand(`fill ${x} 65 ${z} ${x} 68 ${z} oak_log`);
  await consoleCommand(`fill ${x - 1} 69 ${z - 1} ${x + 1} 70 ${z + 1} oak_leaves[persistent=true]`);
}
async function main() {
  if (process.env.MINECRAFT_EULA !== 'true') throw Error('Read https://www.minecraft.net/eula and set MINECRAFT_EULA=true only if you accept it.');
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'genecraft-integration-'));
  console.log('Disposable server and evidence: ' + dir);
  serverLog = fs.createWriteStream(path.join(dir, 'integration-server.log'));
  let bridgeLog = '';
  bridge = spawn(process.env.PYTHON || 'python3', ['-u', path.join(root, 'bridge/genecraft_bridge.py')], {
    env: { ...process.env, GENECRAFT_HOME: path.join(dir, 'bridge-home') },
  });
  bridge.on('error', err => { bridgeLog += String(err); });
  for (const stream of [bridge.stdout, bridge.stderr]) stream.on('data', chunk => { bridgeLog += chunk.toString(); });
  await until('isolated bridge ready (port 8765 must be free)', () => {
    if (bridge.exitCode !== null) throw Error(bridgeLog);
    return bridgeLog.includes('listening');
  }, 10000);
  const version = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8').match(/<version>([^<]+)<\/version>/)[1];
  fs.mkdirSync(path.join(dir, 'plugins'));
  fs.copyFileSync(path.join(root, `target/genecraft-paper-${version}.jar`), path.join(dir, 'plugins/GeneCraft.jar'));
  const paper = process.env.GENECRAFT_TEST_PAPER;
  const bytes = paper ? fs.readFileSync(paper) : Buffer.from(await (await fetch(paperURL)).arrayBuffer());
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), paperSHA, 'Paper checksum');
  fs.writeFileSync(path.join(dir, 'paper.jar'), bytes);
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${port}`, 'online-mode=false', 'enforce-secure-profile=false',
    'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'max-players=2',
    'view-distance=3', 'simulation-distance=3', 'sync-chunk-writes=true', 'allow-flight=true',
    'difficulty=peaceful', 'gamemode=creative', 'enable-rcon=false', 'pause-when-empty-seconds=-1',
  ].join('\n'));
  await start();
  record('Plugin boots on Paper 1.21.11 and simulated Java player connects');
  await consoleCommand('time set day');
  await consoleCommand('fill -30 63 -30 30 64 30 grass_block');
  await teleport(0.5, 0.5);
  await spawnAgent('Woodcutter');
  await chat('/genecraft work Woodcutter gather 0');
  assert(!work('Woodcutter'), 'Invalid count must not create a job');
  await chat('/genecraft work Woodcutter gather 24');
  await until('waiting for real trees', () => work('Woodcutter')?.status === 'waiting_for_logs');
  record('Offline order persists, rejects invalid counts and waits when no trees exist');
  for (const x of [8, 12, 16]) for (const z of [-8, -12]) await tree(x, z);
  await until('woodcutting progress', () => work('Woodcutter')?.progress >= 2);
  await teleport(80, 0);
  await until('owner-distance pause', () => work('Woodcutter')?.status === 'waiting_for_owner');
  const paused = work('Woodcutter').progress;
  await sleep(1600);
  assert.equal(work('Woodcutter').progress, paused);
  record('Gathering changes real blocks and pauses when owner leaves');
  await stop();
  await start();
  assert.equal(work('Woodcutter').progress, paused);
  await teleport(0.5, 0.5);
  await until('gather completes after server restart', () => work('Woodcutter')?.status === 'completed', 90000);
  assert.equal(work('Woodcutter').progress, 24);
  assert.equal(supplies().OAK_PLANKS, 96);
  for (const x of [8, 12, 16]) for (const z of [-8, -12]) for (let y = 65; y <= 68; y++) {
    const block = bot.blockAt(new (require('vec3').Vec3)(x, y, z));
    assert.equal(block?.name, 'air', 'Harvested trunk must be absent in the actual world');
  }
  record('Job and entity survive restart; 24 real logs yield exactly 96 saved planks');
  const woodcutterAtRest = Object.values(bot.entities).find(e => e.uuid === ids.Woodcutter).position.clone();
  await chat('/genecraft supplies withdraw OAK_PLANKS 8');
  await until('withdrawal inventory', () => bot.inventory.items().filter(i => i.name === 'oak_planks').reduce((n, i) => n + i.count, 0) === 8);
  assert.equal(supplies().OAK_PLANKS, 88);
  record('Shared supplies can be withdrawn into the player inventory without duplication');
  await consoleCommand('give GeneCraftTest oak_planks 56');
  await consoleCommand('give GeneCraftTest stone 2240');
  await until('full inventory', () => bot.inventory.emptySlotCount() === 0);
  await chat('/genecraft supplies withdraw OAK_PLANKS 8');
  assert.equal(supplies().OAK_PLANKS, 88, 'Full inventory must not lose saved supplies');
  await consoleCommand('clear GeneCraftTest stone');
  record('Full inventory leaves unclaimed supplies intact');
  await spawnAgent('Builder');
  await chat('/genecraft work Builder house starter');
  await until('starter house completed', () => work('Builder')?.status === 'completed', 90000);
  const house = work('Builder');
  assert(house.blocks.length >= 60, 'House must contain substantial structure');
  for (const cell of house.blocks) {
    const block = bot.blockAt(new (require('vec3').Vec3)(cell.x, cell.y, cell.z));
    assert.equal(block?.name, cell.material.toLowerCase(), `Built block ${cell.x},${cell.y},${cell.z}`);
  }
  assert.equal(Math.min(...house.blocks.map(b => b.y)), 65, 'Floor rests directly on terrain');
  assert.equal(supplies().OAK_PLANKS, 2, 'House must consume exactly 86 saved planks');
  record('Builder consumes gathered supplies and completes a real grounded starter house');
  const restingWoodcutter = Object.values(bot.entities).find(e => e.uuid === ids.Woodcutter);
  assert(restingWoodcutter && restingWoodcutter.position.distanceTo(woodcutterAtRest) < 3,
    'Completed worker must stay at its work site instead of wandering out of range');
  record('Completed worker remains at its work site while another agent builds');
  await teleport(-12.5, 12.5);
  await spawnAgent('Miner');
  await consoleCommand('fill -13 65 15 -13 66 18 stone');
  await consoleCommand(`tp ${ids.Miner} -12.5 65 14.5`);
  await chat('/genecraft work Miner mine 4 65');
  await until('mine completed', () => work('Miner')?.status === 'completed');
  assert.equal(supplies().COBBLESTONE, 8);
  record('Miner clears four two-block-high tunnel slices and saves eight cobblestone');
  await consoleCommand('setblock -13 65 21 chest');
  await consoleCommand(`tp ${ids.Miner} -12.5 65 18.5`);
  await chat('/genecraft work Miner mine 4 65');
  await until('mine stops at container', () => work('Miner')?.status === 'stopped_safe');
  assert.equal(bot.blockAt(new (require('vec3').Vec3)(-13, 65, 21))?.name, 'chest');
  record('Mining stops before protected containers');
  await teleport(0.5, 0.5);
  await consoleCommand(`tp ${ids.Woodcutter} 8.5 65 -8.5`);
  const messageStart = messages.length;
  await chat('@Woodcutter gather 8 logs');
  await until('natural language creates saved job', () => work('Woodcutter')?.target === 8);
  await chat('@Woodcutter status');
  await until('local status response', () => messages.slice(messageStart).some(m => m.includes('Saved job:')));
  assert(!messages.slice(messageStart).some(m => m.includes('Using your ChatGPT plan')), 'Local actions must not claim model usage');
  assert(!fs.existsSync(path.join(dir, 'bridge-home/accounts.json')), 'Test must not require an AI account');
  record('Natural-language gather and status work through the real bridge without an AI account');
  await chat('@Woodcutter cancel job');
  await until('cancel recorded', () => work('Woodcutter')?.status === 'cancelled');
  record('Cancellation persists and stops scheduled work');
  await chat('@Woodcutter follow me');
  await until('idle worker resumes movement', () => Object.values(bot.entities)
    .find(e => e.uuid === ids.Woodcutter)?.position.distanceTo(bot.entity.position) < 4);
  await chat('@Woodcutter stop');
  record('An idle worker can resume following on a new natural-language order');
  assert(!/Could not pass event|generated an exception|Error occurred while enabling/.test(allLogs), 'No plugin runtime errors across both server starts');
  await stop();
  fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify({ version, paper: '1.21.11-132', results }, null, 2));
  console.log(`PASS all ${results.length} acceptance checks. Evidence: ${dir}`);
}
main().catch(async err => {
  console.error(err.stack || err);
  if (dir) fs.writeFileSync(path.join(dir, 'failure.txt'), String(err.stack || err));
  try { await stop(); } catch (shutdown) { console.error(shutdown); }
  process.exitCode = 1;
}).finally(() => { bridge?.kill('SIGTERM'); serverLog?.end(); });
