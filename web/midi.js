// Parseur Standard MIDI File. Temps en ms. Les notes de batterie (canal 10) sont exclues de l'affichage.

/** Premier index i tel que key(arr[i]) >= v (arr croissant). */
function lowerBound(arr, v, key = (x) => x) {
  let lo = 0, hi = arr.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (key(arr[mid]) < v) lo = mid + 1; else hi = mid; }
  return lo;
}

function makeSong(notes, events, duration) {
  const prefixMaxEnd = new Float64Array(notes.length);
  let m = 0;
  notes.forEach((n, i) => { m = Math.max(m, n.end); prefixMaxEnd[i] = m; });
  const avg = (l) => l.reduce((a, n) => a + n.pitch, 0) / l.length;
  const meanPitch = avg(notes);
  const sideOf = (field) => {
    const groups = new Map();
    for (const n of notes) { if (!groups.has(n[field])) groups.set(n[field], []); groups.get(n[field]).push(n); }
    return new Map([...groups].map(([k, l]) => [k, avg(l) >= meanPitch]));
  };
  const trackIsRight = sideOf('track'), channelIsRight = sideOf('channel');
  const autoHandMode = trackIsRight.size >= 2 ? 1 : channelIsRight.size >= 2 ? 2 : 3;
  return {
    notes, events, duration, prefixMaxEnd, autoHandMode,
    minPitch: notes.length ? Math.min(...notes.map((n) => n.pitch)) : 48,
    maxPitch: notes.length ? Math.max(...notes.map((n) => n.pitch)) : 72,
    /** 0 = main droite, 1 = main gauche. mode : 0 auto, 1 pistes, 2 canaux, 3 hauteur. */
    hand(n, mode, splitNote, swap) {
      const md = mode === 0 ? autoHandMode : mode;
      const right = md === 1 ? trackIsRight.get(n.track) ?? true
        : md === 2 ? channelIsRight.get(n.channel) ?? true
        : n.pitch >= splitNote;
      return right !== swap ? 0 : 1;
    },
  };
}

function parseMidi(buf) {
  const b = new Uint8Array(buf);
  let p = 0;
  const u8 = () => { if (p >= b.length) throw new Error('Fichier MIDI tronqué'); return b[p++]; };
  const u16 = () => (u8() << 8) | u8();
  const u32 = () => u16() * 65536 + u16();
  const vlq = () => { let v = 0, c; do { c = u8(); v = v * 128 + (c & 0x7f); } while (c & 0x80); return v; };
  const id4 = (o) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);

  if (b.length <= 14 || id4(0) !== 'MThd') throw new Error("Ce n'est pas un fichier MIDI");
  p = 4;
  const hlen = u32(); u16(); /* format */ const ntracks = u16(), division = u16();
  p = 8 + hlen;

  const raws = [];
  for (let t = 0; t < ntracks && p + 8 <= b.length; t++) {
    const id = id4(p); p += 4;
    const len = u32();
    const end = Math.min(b.length, p + len);
    if (id !== 'MTrk') { p = end; continue; }
    let tick = 0, status = 0;
    while (p < end) {
      tick += vlq();
      let s = u8();
      if (s < 0x80) { p--; s = status; } else if (s < 0xf0) status = s;
      if (s === 0xff) {
        const type = u8(), l = vlq();
        if (type === 0x51 && l === 3) raws.push({ tick, order: raws.length, tempo: (u8() << 16) | (u8() << 8) | u8() });
        else p += l;
      } else if (s === 0xf0 || s === 0xf7) p += vlq();
      else if (s < 0xf8) {
        const n = (s & 0xe0) === 0xc0 ? 1 : 2; // Cx / Dx : 1 octet de données
        const msg = [s];
        for (let i = 0; i < n; i++) msg.push(u8());
        raws.push({ tick, order: raws.length, track: t, msg });
      }
    }
    p = end;
  }
  raws.sort((a, b) => a.tick - b.tick || a.order - b.order);

  const smpte = (division & 0x8000) !== 0;
  let tempo = 500000, lastTick = 0, lastMs = 0;
  const msPerTick = () => smpte ? 1000 / ((256 - (division >> 8)) * (division & 0xff)) : tempo / 1000 / division;
  const events = [], notes = [], open = new Map();
  for (const r of raws) {
    const ms = lastMs + (r.tick - lastTick) * msPerTick();
    lastMs = ms; lastTick = r.tick;
    if (!r.msg) { tempo = r.tempo; continue; }
    events.push({ time: ms, msg: r.msg });
    const cmd = r.msg[0] & 0xf0, ch = r.msg[0] & 0x0f;
    if (ch === 9 || (cmd !== 0x80 && cmd !== 0x90)) continue;
    const [, pitch, vel] = r.msg;
    const key = (r.track * 16 + ch) * 128 + pitch;
    const o = open.get(key);
    if (o) { open.delete(key); notes.push({ start: o.start, end: ms, pitch, velocity: o.vel, track: r.track, channel: ch }); }
    if (cmd === 0x90 && vel > 0) open.set(key, { start: ms, vel });
  }
  for (const [key, o] of open) {
    notes.push({ start: o.start, end: lastMs, pitch: key % 128, velocity: o.vel, track: Math.floor(key / 2048), channel: Math.floor(key / 128) % 16 });
  }
  notes.sort((a, b) => a.start - b.start);
  return makeSong(notes, events, lastMs);
}

if (typeof module === 'object') module.exports = { parseMidi };
