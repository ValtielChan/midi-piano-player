// Synthé General MIDI par échantillons (soundfonts FluidR3_GM, chargées à la demande depuis le CDN) + séquenceur.
// ponytail: pas de batterie ni de pitch bend (les soundfonts gleitz n'ont pas de kit) ; ajouter un SF2 complet si besoin.

const SF_URL = 'https://gleitz.github.io/midi-js-soundfonts/FluidR3_GM/';
const GM = ('acoustic_grand_piano bright_acoustic_piano electric_grand_piano honkytonk_piano electric_piano_1 electric_piano_2 harpsichord clavinet '
  + 'celesta glockenspiel music_box vibraphone marimba xylophone tubular_bells dulcimer drawbar_organ percussive_organ rock_organ church_organ '
  + 'reed_organ accordion harmonica tango_accordion acoustic_guitar_nylon acoustic_guitar_steel electric_guitar_jazz electric_guitar_clean '
  + 'electric_guitar_muted overdriven_guitar distortion_guitar guitar_harmonics acoustic_bass electric_bass_finger electric_bass_pick fretless_bass '
  + 'slap_bass_1 slap_bass_2 synth_bass_1 synth_bass_2 violin viola cello contrabass tremolo_strings pizzicato_strings orchestral_harp timpani '
  + 'string_ensemble_1 string_ensemble_2 synth_strings_1 synth_strings_2 choir_aahs voice_oohs synth_choir orchestra_hit trumpet trombone tuba '
  + 'muted_trumpet french_horn brass_section synth_brass_1 synth_brass_2 soprano_sax alto_sax tenor_sax baritone_sax oboe english_horn bassoon '
  + 'clarinet piccolo flute recorder pan_flute blown_bottle shakuhachi whistle ocarina lead_1_square lead_2_sawtooth lead_3_calliope lead_4_chiff '
  + 'lead_5_charang lead_6_voice lead_7_fifths lead_8_bass__lead pad_1_new_age pad_2_warm pad_3_polysynth pad_4_choir pad_5_bowed pad_6_metallic '
  + 'pad_7_halo pad_8_sweep fx_1_rain fx_2_soundtrack fx_3_crystal fx_4_atmosphere fx_5_brightness fx_6_goblins fx_7_echoes fx_8_scifi sitar banjo '
  + 'shamisen koto kalimba bagpipe fiddle shanai tinkle_bell agogo steel_drums woodblock taiko_drum melodic_tom synth_drum reverse_cymbal '
  + 'guitar_fret_noise breath_noise seashore bird_tweet telephone_ring helicopter applause gunshot').split(' ');
const NOTE_IDX = { C: 0, Db: 1, D: 2, Eb: 3, E: 4, F: 5, Gb: 6, G: 7, Ab: 8, A: 9, Bb: 10, B: 11 };

class Synth {
  constructor() {
    this.ctx = new (window.AudioContext || window.webkitAudioContext)();
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.7;
    this.master.connect(this.ctx.createDynamicsCompressor()).connect(this.ctx.destination);
    this.banks = [];          // programme → AudioBuffer[] indexé par hauteur MIDI
    this.loading = new Map(); // programme → Promise
    this.voices = new Set();
    this.on = new Map();      // canal*128+hauteur → voix tenue
    this.ch = Array.from({ length: 16 }, () => {
      const g = this.ctx.createGain(); g.connect(this.master);
      return { g, program: 0, vol: 100, expr: 127, sustain: false, held: [] };
    });
    this.reset();
  }

  load(program) {
    if (!this.loading.has(program)) {
      this.loading.set(program, fetch(SF_URL + GM[program] + '-mp3.js')
        .then((r) => { if (!r.ok) throw new Error(r.status); return r.text(); })
        .then(async (txt) => {
          const bufs = [];
          const jobs = [...txt.matchAll(/"([A-G]b?)(-?\d)":\s*"data:audio\/\w+;base64,([^"]+)"/g)].map((m) => {
            const bytes = Uint8Array.from(atob(m[3]), (c) => c.charCodeAt(0));
            return this.ctx.decodeAudioData(bytes.buffer).then((buf) => { bufs[NOTE_IDX[m[1]] + (+m[2] + 1) * 12] = buf; }, () => {});
          });
          await Promise.all(jobs);
          this.banks[program] = bufs;
        })
        .catch((e) => { this.loading.delete(program); throw e; }));
    }
    return this.loading.get(program);
  }

  chGain(c, t) { c.g.gain.setValueAtTime(((c.vol * c.expr) / 16129) ** 2, t); }

  release(v, t, tau = 0.12) {
    v.g.gain.setTargetAtTime(0, t, tau);
    try { v.s.stop(t + tau * 8); } catch {}
  }

  /** Coupe tout et remet les canaux à l'état par défaut. */
  reset() {
    const now = this.ctx.currentTime;
    for (const v of this.voices) { v.g.gain.cancelScheduledValues(now); this.release(v, now, 0.015); }
    this.voices.clear(); this.on.clear();
    for (const c of this.ch) {
      Object.assign(c, { program: 0, vol: 100, expr: 127, sustain: false, held: [] });
      c.g.gain.cancelScheduledValues(now); this.chGain(c, now);
    }
  }

  /** Traite un message MIDI à l'instant audio t (les appels doivent être chronologiques). */
  handle(msg, t) {
    const cmd = msg[0] & 0xf0, n = msg[0] & 0x0f, c = this.ch[n];
    if (n === 9) return;
    const key = n * 128 + msg[1];
    if (cmd === 0x90 && msg[2] > 0) {
      const prev = this.on.get(key);
      if (prev) this.release(prev, t);
      const bufs = this.banks[c.program] || this.banks[0];
      if (!bufs) return;
      let src = -1;
      for (let d = 0; d < 128 && src < 0; d++) src = bufs[msg[1] - d] ? msg[1] - d : bufs[msg[1] + d] ? msg[1] + d : -1;
      if (src < 0) return;
      const s = this.ctx.createBufferSource(), g = this.ctx.createGain();
      s.buffer = bufs[src];
      s.playbackRate.value = 2 ** ((msg[1] - src) / 12);
      g.gain.value = (msg[2] / 127) ** 1.6;
      s.connect(g).connect(c.g);
      const v = { s, g };
      s.onended = () => this.voices.delete(v);
      s.start(t);
      this.voices.add(v); this.on.set(key, v);
    } else if (cmd === 0x80 || cmd === 0x90) {
      const v = this.on.get(key);
      if (!v) return;
      this.on.delete(key);
      if (c.sustain) c.held.push(v); else this.release(v, t);
    } else if (cmd === 0xb0) {
      const [, cc, val] = msg;
      if (cc === 7) { c.vol = val; this.chGain(c, t); }
      else if (cc === 11) { c.expr = val; this.chGain(c, t); }
      else if (cc === 64) {
        c.sustain = val >= 64;
        if (!c.sustain) { c.held.forEach((v) => this.release(v, t)); c.held = []; }
      }
    } else if (cmd === 0xc0) c.program = msg[1];
  }
}

/** Séquenceur : programme les événements ~200 ms à l'avance sur l'horloge audio. */
class Player {
  constructor(synth) {
    this.synth = synth;
    this.song = null; this.playing = false; this.speed = 1;
    this.base = 0;    // position (ms) au moment de l'ancrage
    this.anchor = 0;  // ctx.currentTime de l'ancrage
    this.next = 0;    // prochain événement à programmer
    setInterval(() => this.tick(), 25);
  }

  get now() { return this.synth.ctx.currentTime; }
  pos() { return this.playing ? this.base + (this.now - this.anchor) * 1000 * this.speed : this.base; }

  load(song) { this.song = song; this.playing = false; this.seek(0); }

  async play() {
    if (!this.song || this.playing) return;
    await this.synth.ctx.resume();
    if (this.base >= this.song.duration) this.seek(0);
    this.anchor = this.now; this.playing = true;
  }

  pause() {
    if (!this.playing) return;
    const p = this.pos();
    this.playing = false; this.seek(p);
  }

  toggle() { return this.playing ? this.pause() : this.play(); }
  stop() { this.playing = false; this.seek(0); }

  seek(ms) {
    const s = this.song;
    if (!s) return;
    this.base = Math.min(Math.max(ms, 0), s.duration); this.anchor = this.now;
    this.synth.reset();
    this.next = lowerBound(s.events, this.base, (e) => e.time);
    // Rejoue l'état (programmes, volumes) tel qu'il serait à cette position.
    for (let i = 0; i < this.next; i++) {
      const m = s.events[i].msg, cmd = m[0] & 0xf0;
      if (cmd === 0xc0 || (cmd === 0xb0 && m[1] !== 64)) this.synth.handle(m, this.now);
    }
  }

  changeSpeed(v) { this.base = this.pos(); this.anchor = this.now; this.speed = v; }

  tick() {
    const s = this.song;
    if (!this.playing || !s) return;
    const p = this.pos(), horizon = p + 200 * this.speed;
    while (this.next < s.events.length && s.events[this.next].time <= horizon) {
      const e = s.events[this.next++];
      this.synth.handle(e.msg, Math.max(this.now, this.anchor + (e.time - this.base) / 1000 / this.speed));
    }
    if (p >= s.duration + 500) { this.playing = false; this.base = s.duration; this.synth.reset(); }
  }
}
