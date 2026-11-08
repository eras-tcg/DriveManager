// The npm package @eras/engine from Node, as eras-web's Web Worker will use it: it imports the built package, installs
// the card bundle and the bot's resources shipped in it, makes a LocalMatch against the bot with a starter deck, and plays
// a whole game seen only through the messages it sends (the JSON the server would push to the player's queue), choosing
// the first legal move of each moment. It fails unless the game reaches its end.
//
//   node js-smoke/smoke.mjs build/dist/js/productionLibrary      (or ./gradlew jsSmoke)
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const dir = resolve(process.argv[2] ?? 'build/dist/js/productionLibrary');
const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
if (pkg.name !== '@eras/engine' || pkg.type !== 'module') throw new Error(`Not the ES module package @eras/engine: ${pkg.name}`);
const { EngineApi, LocalMatch } = await import(pathToFileURL(join(dir, pkg.exports['.'].import)).href);
const asset = (name) => readFileSync(join(dir, pkg.exports[`./${name}`]), 'utf8');

const api = EngineApi.getInstance();
const cards = api.installCards(asset('card-bundle.json'));
const botFiles = api.installBotResources(asset('bot-resources.json'));
const starters = JSON.parse(asset('starter-decks.json')).decks;
console.log(`@eras/engine ${api.VERSION}: ${cards} cards, ${botFiles} bot resource files, decks ${Object.keys(starters).join(' ')}`);

const seed = process.env.SMOKE_SEED ?? '7';
const match = new LocalMatch(JSON.stringify({ seed, decks: starters, botLevel: 'easy', time: 0, tickNanos: '20000000' }));
const me = match.userId;
let state = null;
let messages = 0;
let errors = 0;

/** Every message the player receives: an update replaces the board's state, an error is kept aside (the board shows it). */
function receive(texts) {
  for (const text of texts) {
    const update = JSON.parse(text);
    messages++;
    if (update.error) {
      if (update.actorId !== me) throw new Error(`An error for someone else: ${text.slice(0, 200)}`);
      errors++;
      continue;
    }
    if (update.roomId !== match.roomId) throw new Error(`Another room: ${update.roomId}`);
    state = update;
  }
}

/** The player's action, then the bot's moves (a worker would wait botPauseMs() before each step). */
function send(type, payload = {}) {
  const before = errors;
  receive(match.send(JSON.stringify({ type, payload })));
  if (match.lastError()) throw new Error(match.lastError());
  for (let step = match.step(); step !== null; step = match.step()) receive(step);
  if (match.lastError()) throw new Error(match.lastError());
  return errors === before;
}

/** What may be tried now, in order, from what the player was sent (the rival's hidden cards are backs in it). */
function candidates() {
  const mine = state.players[me];
  if (state.status === 'WAITING') return mine.ready ? [] : [['SET_READY', { ready: true }]];
  if (state.status === 'MULLIGAN') return (state.mulliganReady ?? []).includes(me) ? [] : [['CONFIRM_MULLIGAN', { toBottom: [] }]];
  if (state.status !== 'IN_PROGRESS') return [];
  const prompt = state.prompt;
  if (prompt) {
    if (prompt.playerId !== me) return [];
    const ids = [...prompt.validIds, ...(prompt.effects ?? []).map((e) => e.effectId)].filter((v, i, a) => a.indexOf(v) === i);
    const answers = [ids.slice(0, prompt.min)];
    for (const id of ids) if (prompt.min <= 1 && prompt.max >= 1) answers.push([id]);
    for (let n = prompt.min + 1; n <= Math.min(prompt.max, ids.length); n++) answers.push(ids.slice(0, n));
    return answers.map((chosen) => ['RESOLVE_PROMPT', { promptId: prompt.id, chosen, order: [] }]).concat([['SURRENDER', {}]]);
  }
  if (state.currentTurnUserId !== me || state.chain || state.combat) return [];
  const moves = [];
  for (const [id, can] of Object.entries(state.legalActions?.[me] ?? {})) {
    if (can.includes('summon')) moves.push(['NORMAL_SUMMON', { instanceId: id, x: 12.5, y: 75.0 }]);
    if (can.includes('attack')) moves.push(['ATTACK', { instanceId: id }]);
  }
  return moves.concat([['END_TURN', {}]]);
}

send('JOIN_ROOM', { deckId: null, username: 'Smoke' });
if (!send('SET_DECK', { deckId: 'deck-st1' })) throw new Error('The deck was refused');
const refused = new Set();
let actions = 0;
while (state.status !== 'POST_GAME' && actions < 3000) {
  const moment = `${state.status}|${state.currentTurnUserId}|${state.prompt?.id}|${state.timeline?.at(-1)?.seq}`;
  const next = candidates().find(([type, payload]) => !refused.has(moment + JSON.stringify([type, payload])));
  if (!next) throw new Error(`Nothing to do at ${moment}`);
  actions++;
  if (!send(...next)) refused.add(moment + JSON.stringify(next));
}
if (state.status !== 'POST_GAME') throw new Error(`The game did not end: ${state.status} after ${actions} actions`);
console.log(`seed ${seed}: the game ended after ${actions} actions of the player, ${messages} messages (${errors} refused), ` +
  `winner ${state.winnerId} (${state.endReason})`);
