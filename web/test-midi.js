// node test-midi.js — même scénario que MidiParserTest.kt côté Android.
const assert = require('assert');
const { parseMidi } = require('./midi.js');

const vlq = (n) => { const out = [n & 0x7f]; while ((n >>= 7) > 0) out.unshift((n & 0x7f) | 0x80); return out; };
const track = (...ev) => {
  const data = ev.flatMap(([d, m]) => [...vlq(d), ...m]).concat([0, 0xff, 0x2f, 0]);
  return [...Buffer.from('MTrk'), 0, 0, 0, data.length, ...data];
};

const ppq = 480;
const header = [...Buffer.from('MThd'), 0, 0, 0, 6, 0, 1, 0, 3, ppq >> 8, ppq & 0xff];
// Tempo 120 bpm, puis 60 bpm au tick 480
const tempo = track([0, [0xff, 0x51, 3, 0x07, 0xa1, 0x20]], [ppq, [0xff, 0x51, 3, 0x0f, 0x42, 0x40]]);
// Main droite : C5 noire à 0, puis (running status) E5 noire à 480
const rh = track([0, [0x90, 72, 100]], [ppq, [72, 0]], [0, [76, 100]], [ppq, [76, 0]]);
// Main gauche : C3 blanche canal 2
const lh = track([0, [0x91, 48, 80]], [2 * ppq, [0x81, 48, 0]]);

const song = parseMidi(new Uint8Array([...header, ...tempo, ...rh, ...lh]));
const by = (p) => song.notes.find((n) => n.pitch === p);
const [c3, c5, e5] = [by(48), by(72), by(76)];
assert.strictEqual(song.notes.length, 3);
assert.deepStrictEqual([c5.start, c5.end, e5.start, e5.end, c3.end].map(Math.round), [0, 500, 500, 1500, 1500]);
assert.strictEqual(Math.round(song.duration), 1500);
assert.strictEqual(song.autoHandMode, 1);
assert.strictEqual(song.hand(c5, 0, 60, false), 0);
assert.strictEqual(song.hand(c3, 0, 60, false), 1);
assert.strictEqual(song.hand(c3, 0, 60, true), 0);
assert.strictEqual(song.hand(c3, 3, 60, false), 1);
assert.throws(() => parseMidi(new Uint8Array(20)));
console.log('OK');
