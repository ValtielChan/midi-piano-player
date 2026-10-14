// ---------- Réglages ----------
const DEFAULTS = {
  keyboardPreset: 0,   // 0 = auto (adapté au morceau), sinon nombre de touches
  windowSec: 3,        // durée visible de chute
  keyboardFrac: 0.28,  // hauteur du clavier (fraction de l'écran)
  palette: 0,
  handMode: 0,         // 0 auto, 1 pistes, 2 canaux, 3 hauteur
  splitNote: 60,
  swapHands: false,
  showLabels: true,
  noteNames: 0,        // nom sur les notes qui tombent : 0 off, 1 ABC, 2 Do Ré Mi
};
const store = {
  get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } },
};
const S = { ...DEFAULTS, ...store.get('settings') };

const hex = (h) => [(h >> 16) & 255, (h >> 8) & 255, h & 255];
const PALETTES = [
  ['Ciel / Orange', 0x38BDF8, 0xFB923C],
  ['Vert / Bleu', 0x4ADE80, 0x60A5FA],
  ['Fuchsia / Jaune', 0xE879F9, 0xFACC15],
  ['Rose / Turquoise', 0xF472B6, 0x2DD4BF],
].map(([name, r, l]) => ({ name, right: hex(r), left: hex(l) }));
const KEYBOARD_PRESETS = [0, 25, 49, 61, 76, 88];

const NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const SOLFEGE = ['Do', 'Do#', 'Ré', 'Ré#', 'Mi', 'Fa', 'Fa#', 'Sol', 'Sol#', 'La', 'La#', 'Si'];
const pitchClass = (p, solfege) => (solfege ? SOLFEGE : NAMES)[p % 12];
const noteName = (p, solfege = false) => pitchClass(p, solfege) + (Math.floor(p / 12) - 1);
const isBlack = (p) => NAMES[p % 12].length === 2;

/** Plage de touches [lo, hi] (bornes blanches) selon le preset ou le morceau. */
function keyRange(song, preset) {
  let [lo, hi] = { 88: [21, 108], 76: [28, 103], 61: [36, 96], 49: [36, 84], 25: [48, 72] }[preset]
    || (song ? [Math.max(song.minPitch - 3, 21), Math.min(song.maxPitch + 3, 108)] : [36, 96]);
  while (isBlack(lo)) lo--;
  while (isBlack(hi)) hi++;
  return [lo, hi];
}

// ---------- Rendu ----------
const WHITE = [255, 255, 255], BLACK = [0, 0, 0];
const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
const rgba = (c, a = 1) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;
const vgrad = (ctx, y0, y1, stops) => { const g = ctx.createLinearGradient(0, y0, 0, y1); stops.forEach((s, i) => g.addColorStop(i / (stops.length - 1), s)); return g; };

const cv = document.getElementById('cv');
const ctx = cv.getContext('2d');
const active = new Int8Array(128);
let rangeCache = { key: null };

function draw(song, pos) {
  const dpr = window.devicePixelRatio || 1;
  const w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const rk = `${S.keyboardPreset}|${song ? song.minPitch + ':' + song.maxPitch : ''}`;
  if (rangeCache.key !== rk) {
    const [lo, hi] = keyRange(song, S.keyboardPreset);
    const whitesBefore = new Int32Array(hi + 2);
    for (let p = lo, c = 0; p <= hi + 1; p++) { whitesBefore[p] = c; if (!isBlack(p)) c++; }
    rangeCache = { key: rk, lo, hi, whitesBefore, nWhite: whitesBefore[hi + 1] };
  }
  const { lo, hi, whitesBefore, nWhite } = rangeCache;
  const pal = PALETTES[S.palette] || PALETTES[0];
  const solfege = S.noteNames === 2;
  const kbH = h * S.keyboardFrac, hitY = h - kbH;
  const ww = w / nWhite, bw = ww * 0.6;
  const radius = Math.min(6, ww * 0.25);
  const keyX = (p) => isBlack(p) ? whitesBefore[p] * ww - bw / 2 : whitesBefore[p] * ww;
  const handColor = (hand) => hand === 0 ? pal.right : pal.left;
  const rr = (x, y, ww_, hh, r) => { ctx.beginPath(); ctx.roundRect(x, y, ww_, hh, r); ctx.fill(); };

  // Fond
  ctx.fillStyle = vgrad(ctx, 0, hitY, ['#07080F', '#141829']);
  ctx.fillRect(0, 0, w, hitY);
  ctx.fillStyle = '#07080F';
  ctx.fillRect(0, hitY, w, kbH);
  ctx.fillStyle = 'rgba(0,0,0,0.16)';
  for (let p = lo; p <= hi; p++) if (isBlack(p)) ctx.fillRect(keyX(p), 0, bw, hitY);
  ctx.fillStyle = 'rgba(255,255,255,0.07)';
  for (let p = lo; p <= hi; p++) if (p % 12 === 0 && p > lo) ctx.fillRect(keyX(p), 0, 1, hitY);

  // Notes
  active.fill(-1);
  if (song) {
    const windowMs = S.windowSec * 1000, pxPerMs = hitY / windowMs;
    const notes = song.notes;
    const fontPx = Math.min(13, ww * (solfege ? 0.36 : 0.48));
    ctx.font = `700 ${fontPx}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    for (let i = lowerBound(song.prefixMaxEnd, pos); i < notes.length; i++) {
      const n = notes[i];
      if (n.start > pos + windowMs) break;
      if (n.end < pos || n.pitch < lo || n.pitch > hi) continue;
      const hand = song.hand(n, S.handMode, S.splitNote, S.swapHands);
      const isOn = n.start <= pos;
      if (isOn) active[n.pitch] = hand;
      const black = isBlack(n.pitch);
      const x = keyX(n.pitch) + (black ? bw * 0.1 : 1);
      const nw = black ? bw * 0.8 : ww - 2;
      const top = Math.max(hitY - (n.end - pos) * pxPerMs, -radius);
      const bottom = Math.min(hitY - (n.start - pos) * pxPerMs, hitY);
      const nh = Math.max(bottom - top, 2);
      let c = handColor(hand);
      if (black) c = mix(c, BLACK, 0.25);
      if (isOn) {
        ctx.fillStyle = rgba(c, 0.35);
        rr(x - 2, top - 2, nw + 4, nh + 4, radius + 2);
        const cx = x + nw / 2, r = ww * 1.3;
        const g = ctx.createRadialGradient(cx, hitY, 0, cx, hitY, r);
        g.addColorStop(0, rgba(c, 0.55)); g.addColorStop(1, rgba(c, 0));
        ctx.fillStyle = g;
        ctx.fillRect(cx - r, hitY - r, 2 * r, 2 * r);
      }
      const g = ctx.createLinearGradient(x, 0, x + nw, 0);
      g.addColorStop(0, rgba(mix(c, WHITE, 0.35))); g.addColorStop(0.5, rgba(c)); g.addColorStop(1, rgba(mix(c, BLACK, 0.3)));
      ctx.fillStyle = g;
      rr(x, top, nw, nh, radius);
      if (isOn) { ctx.fillStyle = 'rgba(255,255,255,0.22)'; rr(x, top, nw, nh, radius); }
      if (S.noteNames && nh > fontPx + 4) {
        ctx.fillStyle = 'rgba(0,0,0,0.85)';
        ctx.fillText(pitchClass(n.pitch, solfege), x + nw / 2, bottom - fontPx * 0.4);
      }
    }
  }

  // Ligne d'impact
  ctx.fillStyle = vgrad(ctx, hitY - 12, hitY, ['rgba(255,255,255,0)', 'rgba(255,255,255,0.12)']);
  ctx.fillRect(0, hitY - 12, w, 12);
  ctx.fillStyle = 'rgba(255,255,255,0.6)';
  ctx.fillRect(0, hitY - 0.75, w, 1.5);

  // Touches blanches
  const kr = ww * 0.12;
  ctx.font = '500 11px system-ui, sans-serif';
  ctx.textAlign = 'center';
  for (let p = lo; p <= hi; p++) if (!isBlack(p)) {
    const x = keyX(p), a = active[p];
    ctx.fillStyle = a < 0 ? vgrad(ctx, hitY, h, ['#F7F7FA', '#D7D9E2']) : vgrad(ctx, hitY, h, [rgba(mix(handColor(a), WHITE, 0.45)), rgba(handColor(a))]);
    ctx.beginPath(); ctx.roundRect(x + 0.5, hitY, ww - 1, kbH, [0, 0, kr, kr]); ctx.fill();
    if (S.showLabels && p % 12 === 0) {
      ctx.fillStyle = a < 0 ? '#70758A' : '#FFFFFF';
      ctx.fillText(noteName(p, solfege), x + ww / 2, h - 6);
    }
  }
  ctx.fillStyle = vgrad(ctx, hitY, hitY + 6, ['rgba(0,0,0,0.45)', 'rgba(0,0,0,0)']);
  ctx.fillRect(0, hitY, w, 6);

  // Touches noires
  const bh = kbH * 0.62;
  for (let p = lo; p <= hi; p++) if (isBlack(p)) {
    const x = keyX(p), a = active[p];
    ctx.fillStyle = 'rgba(0,0,0,0.4)';
    ctx.fillRect(x - 0.75, hitY, bw + 1.5, bh + 1);
    ctx.fillStyle = a < 0 ? vgrad(ctx, hitY, hitY + bh, ['#2B2D3A', '#0D0E15'])
      : vgrad(ctx, hitY, hitY + bh, [rgba(mix(handColor(a), WHITE, 0.2)), rgba(mix(handColor(a), BLACK, 0.25))]);
    ctx.beginPath(); ctx.roundRect(x, hitY, bw, bh, [0, 0, kr, kr]); ctx.fill();
  }
}

// ---------- Application ----------
const $ = (id) => document.getElementById(id);
const synth = new Synth();
const player = new Player(synth);
let drag = null, wakeLock = null, wasPlaying = false, toastTimer = 0;

function toast(msg) {
  $('toast').textContent = msg; $('toast').classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.remove('show'), 4000);
}
const fmt = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

function openBytes(bytes, name, persist) {
  let song;
  try { song = parseMidi(bytes); } catch (e) { toast(`Impossible d'ouvrir ce fichier : ${e.message}`); return; }
  player.load(song);
  $('name').textContent = name;
  $('empty').hidden = true;
  $('seek').max = Math.max(song.duration, 1);
  $('dur').textContent = fmt(song.duration);
  for (const id of ['rewind', 'play', 'stop', 'seek']) $(id).disabled = false;
  syncSettings();
  if (persist) {
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    store.set('lastMidi', { name, data: btoa(bin) });
  }
  // Charge les instruments utilisés (piano toujours, sert aussi de secours)
  const programs = new Set([0]);
  for (const e of song.events) if ((e.msg[0] & 0xf0) === 0xc0 && (e.msg[0] & 0x0f) !== 9) programs.add(e.msg[1]);
  $('status').textContent = '· chargement des sons…';
  Promise.allSettled([...programs].map((p) => synth.load(p))).then((res) => {
    if (player.song !== song) return;
    $('status').textContent = '';
    if (res.some((r) => r.status === 'rejected')) toast('Certains sons n\'ont pas pu être chargés (connexion ?)');
  });
}

async function openFile(file) {
  if (!file) return;
  try { openBytes(new Uint8Array(await file.arrayBuffer()), file.name, true); }
  catch (e) { toast(`Impossible d'ouvrir ce fichier : ${e.message}`); }
}

function setWakeLock(on) {
  if (on && navigator.wakeLock) navigator.wakeLock.request('screen').then((l) => { if (player.playing) wakeLock = l; else l.release(); }).catch(() => {});
  if (!on && wakeLock) { wakeLock.release().catch(() => {}); wakeLock = null; }
}

function frame() {
  requestAnimationFrame(frame);
  player.tick();
  const pos = drag ?? player.pos();
  draw(player.song, pos);
  $('time').textContent = fmt(pos);
  if (drag === null) $('seek').value = pos;
  $('ico-play').style.display = player.playing ? 'none' : '';
  $('ico-pause').style.display = player.playing ? '' : 'none';
  if (wasPlaying !== player.playing) setWakeLock(wasPlaying = player.playing);
}

function toggleFullscreen() {
  if (document.fullscreenElement) { document.exitFullscreen?.(); return; }
  const r = document.documentElement.requestFullscreen?.();
  r?.then(() => screen.orientation?.lock?.('landscape')).catch(() => {});
}

// Transport
$('rewind').onclick = () => player.stop();
$('play').onclick = () => player.toggle();
$('stop').onclick = () => { player.pause(); player.seek(0); };
$('seek').oninput = (e) => { drag = +e.target.value; };
$('seek').onchange = (e) => { player.seek(+e.target.value); drag = null; };
$('speed').onchange = (e) => player.changeSpeed(+e.target.value);
$('fs').onclick = toggleFullscreen;
$('demo').onclick = () => fetch('demo.mid').then((r) => r.arrayBuffer())
  .then((b) => openBytes(new Uint8Array(b), 'Bach – Prélude en do majeur, BWV 846', false))
  .catch(() => toast('Démo indisponible (ouvre la page via un serveur web)'));
$('gear').onclick = () => $('settings').showModal();
document.querySelectorAll('[data-open]').forEach((b) => { b.onclick = () => $('file').click(); });
$('file').onchange = (e) => { openFile(e.target.files[0]); e.target.value = ''; };
cv.onclick = () => document.body.classList.toggle('hide-ui');
$('settings').onclick = (e) => { if (e.target === e.currentTarget) e.currentTarget.close(); }; // clic sur le fond

// Glisser-déposer
addEventListener('dragover', (e) => e.preventDefault());
addEventListener('drop', (e) => { e.preventDefault(); openFile(e.dataTransfer.files[0]); });

// Raccourcis clavier
addEventListener('keydown', (e) => {
  if ($('settings').open || e.target.tagName === 'SELECT' || e.ctrlKey || e.metaKey || e.altKey) return;
  const k = e.key;
  if (k === ' ') player.toggle();
  else if (k === 'Home') player.stop();
  else if (k === 'ArrowLeft') player.seek(player.pos() - 5000);
  else if (k === 'ArrowRight') player.seek(player.pos() + 5000);
  else if (k === 'f' || k === 'F') toggleFullscreen();
  else if (k === 'o' || k === 'O') $('file').click();
  else return;
  e.preventDefault();
  if (e.target.blur) e.target.blur();
});

// ---------- Panneau de réglages ----------
const chips = {
  keyboardPreset: KEYBOARD_PRESETS.map((k) => [k, k === 0 ? 'Auto' : `${k} touches`]),
  palette: PALETTES.map((p, i) => [i, `<span class="dot" style="background:${rgba(p.right)}"></span><span class="dot" style="background:${rgba(p.left)}"></span>${p.name}`]),
  handMode: ['Auto', 'Pistes', 'Canaux', 'Hauteur'].map((t, i) => [i, t]),
  noteNames: ['Aucun', 'C D E F G A B', 'Do Ré Mi Fa Sol La Si'].map((t, i) => [i, t]),
};
document.querySelectorAll('.chips').forEach((box) => {
  const key = box.dataset.key;
  box.innerHTML = chips[key].map(([v, html]) => `<button class="chip" data-v="${v}">${html}</button>`).join('');
  box.onclick = (e) => { const b = e.target.closest('.chip'); if (b) update(key, +b.dataset.v); };
});
document.querySelectorAll('#settings input').forEach((el) => {
  el.oninput = () => update(el.dataset.key, el.type === 'checkbox' ? el.checked : +el.value);
});

function update(key, v) { S[key] = v; store.set('settings', S); syncSettings(); }

function syncSettings() {
  const song = player.song, solfege = S.noteNames === 2;
  document.querySelectorAll('.chips').forEach((box) => {
    for (const b of box.children) b.classList.toggle('on', +b.dataset.v === S[box.dataset.key]);
  });
  document.querySelectorAll('#settings input').forEach((el) => {
    if (el.type === 'checkbox') el.checked = S[el.dataset.key]; else el.value = S[el.dataset.key];
  });
  const auto = { 1: ' (pistes)', 2: ' (canaux)', 3: ' (hauteur)' }[song?.autoHandMode] || '';
  document.querySelector('[data-key=handMode] .chip').textContent = 'Auto' + auto;
  $('l-window').textContent = `Durée de chute : ${S.windowSec.toFixed(1)} s`;
  $('l-kb').textContent = `Hauteur du clavier : ${Math.round(S.keyboardFrac * 100)} %`;
  $('l-split').textContent = `Main droite à partir de ${noteName(S.splitNote, solfege)}`;
  $('split-box').hidden = (S.handMode === 0 ? song?.autoHandMode ?? 3 : S.handMode) !== 3;
  $('l-labels').textContent = `Afficher le nom des ${solfege ? 'Do' : 'C'} sur le clavier`;
  document.documentElement.style.setProperty('--accent', rgba((PALETTES[S.palette] || PALETTES[0]).right));
}

// ---------- Démarrage ----------
syncSettings();
const last = store.get('lastMidi');
if (last) { try { openBytes(Uint8Array.from(atob(last.data), (c) => c.charCodeAt(0)), last.name, false); } catch {} }
requestAnimationFrame(frame);
