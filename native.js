// 英語學習筆記本：在 Android App（原生外殼）裡執行時，把聲音與語音辨識交給原生程式。
// 一般瀏覽器裡沒有 window.NativeNB，這個檔案什麼都不做。
(function () {
    'use strict';
    const N = window.NativeNB;
    if (!N || typeof N.version !== 'function') return;
    const call = (fn, ...a) => { try { return typeof N[fn] === 'function' ? N[fn](...a) : undefined; } catch (e) { console.warn('NativeNB.' + fn, e); } };
    const handlers = {};
    // 原生 → 網頁的事件入口
    window.__nbEvt = (ch, id, type, data) => { const h = handlers[ch + id]; if (h) { try { h(type, data || {}); } catch (e) { console.error(e); } } };
    let seq = 0;

    const MIX_KEY = 'eng_mixMode';
    const mixNow = () => { try { const m = localStorage.getItem(MIX_KEY); return ['mix', 'duck', 'exclusive'].includes(m) ? m : 'mix'; } catch (e) { return 'mix'; } };
    call('setMix', mixNow());

    window.nativeApp = {
        version: Number(call('version')) || 0,
        getMix: mixNow,
        setMix(m) { try { localStorage.setItem(MIX_KEY, m); } catch (e) {} call('setMix', m); },
        media(info) { call('media', info ? JSON.stringify(info) : ''); },
        // share=true：存進「下載」後跳出分享選單（選 Google Drive 就會存到雲端）
        saveFile(name, mime, text, share) { return call('saveFile', name, mime, text, !!share) === true; },
        // 大檔（例如 AI 語音包）：分段傳給原生寫入，避免一次塞爆記憶體
        async saveBlob(name, mime, blob, share) {
            if (call('fileOpen', name, mime) !== true) return false;
            let ok = true;
            try {
                const CH = 512 * 1024;
                for (let i = 0; i < blob.size; i += CH) {
                    const u8 = new Uint8Array(await blob.slice(i, i + CH).arrayBuffer());
                    if (call('fileWrite', toB64(u8)) !== true) { ok = false; break; }
                    if (i % (CH * 8) === 0) await new Promise(r => setTimeout(r, 0));
                }
            } catch (e) { console.warn('saveBlob', e); ok = false; }
            return call('fileClose', ok, !!share) === true && ok;
        },
        // 「被打斷就停」模式下，手機語音被其他 App 打斷：和網頁版一樣記下來，回到 App 時接著播
        _interrupted() {
            try {
                if (typeof player === 'undefined' || !player.playing || player.mode !== 'phone') return;
                markInterrupted();
                player.playing = false; player.token++;
                window.speechSynthesis.cancel();
                updatePlayerView();
            } catch (e) {}
        }
    };

    // ---------- 播放器：介面和 <audio> 一樣，實際由原生 MediaPlayer 播放 ----------
    function toB64(u8) {
        let s = '';
        for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
        return btoa(s);
    }
    class NativeAudio {
        constructor() {
            this.id = ++seq; this._gen = 0; this._l = {};
            this.paused = true; this.ended = false; this.duration = NaN; this.readyState = 0;
            this._t = 0; this._tAt = 0; this._rate = 1; this.defaultPlaybackRate = 1;
            this.preload = 'auto'; this.preservesPitch = true; this._src = '';
            handlers['a' + this.id] = (type, d) => this._evt(type, d);
        }
        addEventListener(t, fn, o) { (this._l[t] = this._l[t] || []).push({ fn, once: !!(o && o.once) }); }
        removeEventListener(t, fn) { this._l[t] = (this._l[t] || []).filter(x => x.fn !== fn); }
        _emit(t) {
            const ls = (this._l[t] || []).slice();
            this._l[t] = (this._l[t] || []).filter(x => !x.once);
            const ev = { type: t, target: this };
            ls.forEach(x => { try { x.fn.call(this, ev); } catch (e) { console.error(e); } });
            const p = this['on' + t]; if (typeof p === 'function') { try { p.call(this, ev); } catch (e) { console.error(e); } }
        }
        get src() { return this._src; }
        set src(u) {
            this._src = u || '';
            this.paused = true; this.ended = false; this.readyState = 0; this.duration = NaN; this._t = 0;
            const gen = ++this._gen;
            call('audioBegin', this.id, gen);
            if (u) this._load(u, gen);
        }
        async _load(u, gen) {
            try {
                const buf = new Uint8Array(await (await fetch(u)).arrayBuffer());
                const CH = 512 * 1024;
                for (let i = 0; i < buf.length; i += CH) {
                    if (gen !== this._gen) return;   // 已經換了別的音檔
                    call('audioChunk', this.id, gen, toB64(buf.subarray(i, i + CH)));
                    if (i % (CH * 8) === 0) await new Promise(r => setTimeout(r, 0));   // 讓畫面有機會更新
                }
                if (gen === this._gen) call('audioLoad', this.id, gen);
            } catch (e) { console.warn('NativeAudio load', e); if (gen === this._gen) this._emit('error'); }
        }
        get currentTime() {
            if (!this.paused && this._tAt) return Math.min(this._t + (Date.now() - this._tAt) / 1000 * this._rate, isNaN(this.duration) ? Infinity : this.duration);
            return this._t;
        }
        set currentTime(s) {
            s = Math.max(0, Number(s) || 0);
            this._t = s; this._tAt = this.paused ? 0 : Date.now(); this.ended = false;
            call('audioSeek', this.id, s);
        }
        get playbackRate() { return this._rate; }
        set playbackRate(r) { this._rate = Number(r) || 1; call('audioRate', this.id, this._rate); }
        play() {
            if (this.ended) { this._t = 0; this.ended = false; call('audioSeek', this.id, 0); }
            const was = this.paused;
            this.paused = false; this._tAt = Date.now();
            call('audioPlay', this.id);
            if (was) this._emit('play');
            return Promise.resolve();
        }
        pause() {
            if (this.paused) return;
            this._t = this.currentTime; this._tAt = 0;
            this.paused = true;
            call('audioPause', this.id);
            this._emit('pause');
        }
        load() {}
        _evt(type, d) {
            if (type === 'meta') {
                this.duration = d.dur; this.readyState = 4;
                this._emit('loadedmetadata'); this._emit('durationchange'); this._emit('canplay'); this._emit('canplaythrough');
            } else if (type === 'time') {
                this._t = d.t; this._tAt = this.paused ? 0 : Date.now();
                this._emit('timeupdate');
            } else if (type === 'pause') {   // 原生那邊自己停的（被其他 App 打斷）
                this._t = d.t; this._tAt = 0;
                if (!this.paused) { this.paused = true; this._emit('pause'); }
            } else if (type === 'ended') {
                this._t = d.t; this._tAt = 0; this.ended = true;
                if (!this.paused) { this.paused = true; this._emit('pause'); }
                this._emit('timeupdate'); this._emit('ended');
            } else if (type === 'error') {
                this._emit('error');
            }
        }
    }
    window.NativeAudio = NativeAudio;

    // ---------- 朗讀：代替 speechSynthesis ----------
    class Utterance {
        constructor(text) {
            this.text = text || ''; this.lang = 'en-US'; this.rate = 1; this.pitch = 1; this.volume = 1; this.voice = null;
            this.onstart = this.onend = this.onerror = this.onboundary = null; this._l = {};
        }
        addEventListener(t, fn) { (this._l[t] = this._l[t] || []).push(fn); }
        _fire(t, ev) { ev = Object.assign({ type: t, utterance: this }, ev); const p = this['on' + t]; if (typeof p === 'function') { try { p.call(this, ev); } catch (e) { console.error(e); } } (this._l[t] || []).forEach(fn => { try { fn.call(this, ev); } catch (e) { console.error(e); } }); }
    }
    const VOICES = [{ name: 'Android（英文）', lang: 'en-US', default: true, localService: true, voiceURI: 'android-tts-en' }];
    const live = new Map();
    const synth = {
        get speaking() { return live.size > 0; },
        get pending() { return live.size > 1; },
        paused: false, onvoiceschanged: null,
        getVoices() { return VOICES.slice(); },
        addEventListener() {}, removeEventListener() {},
        speak(u) {
            if (!u) return;
            const id = ++seq;
            live.set(id, u);
            handlers['t' + id] = (type, d) => {
                if (type === 'start') u._fire('start');
                else { live.delete(id); delete handlers['t' + id]; u._fire(type === 'end' ? 'end' : 'error', type === 'end' ? {} : { error: d.error || 'synthesis-failed' }); }
            };
            const lang = (u.voice && u.voice.lang) || u.lang || 'en-US';
            call('ttsSpeak', id, String(u.text || ''), lang, Number(u.rate) || 1);
        },
        cancel() {
            const list = [...live.entries()];
            live.clear();
            call('ttsStop');
            list.forEach(([id, u]) => { delete handlers['t' + id]; setTimeout(() => u._fire('error', { error: 'interrupted' }), 0); });
        },
        pause() {}, resume() {}
    };
    try { Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true, writable: true }); } catch (e) { window.speechSynthesis = synth; }
    window.SpeechSynthesisUtterance = Utterance;

    // ---------- 語音辨識：代替 SpeechRecognition ----------
    class Recognition {
        constructor() {
            this.id = 0; this.lang = 'en-US'; this.interimResults = false; this.continuous = false; this.maxAlternatives = 1;
            this.onstart = this.onresult = this.onerror = this.onend = this.onspeechstart = this.onaudiostart = null;
            this._on = false; this._l = {};
        }
        addEventListener(t, fn) { (this._l[t] = this._l[t] || []).push(fn); }
        removeEventListener(t, fn) { this._l[t] = (this._l[t] || []).filter(f => f !== fn); }
        _fire(t, ev) { ev = Object.assign({ type: t, target: this }, ev); const p = this['on' + t]; if (typeof p === 'function') { try { p.call(this, ev); } catch (e) { console.error(e); } } (this._l[t] || []).forEach(fn => { try { fn.call(this, ev); } catch (e) { console.error(e); } }); }
        start() {
            if (this._on) throw new DOMException('recognition has already started', 'InvalidStateError');
            this._on = true;
            if (this.id) delete handlers['r' + this.id];
            this.id = ++seq;
            handlers['r' + this.id] = (type, d) => this._evt(type, d);
            call('srStart', this.id, String(this.lang || 'en-US'), !!this.interimResults, Number(this.maxAlternatives) || 1);
        }
        stop() { if (this._on) call('srStop', this.id); }
        abort() { if (this._on) call('srAbort', this.id); }
        _evt(type, d) {
            if (type === 'start') { this._fire('start'); this._fire('audiostart'); }
            else if (type === 'speechstart') this._fire('speechstart');
            else if (type === 'result') {
                const res = (d.alts || []).map(t => ({ transcript: t, confidence: 0 }));
                res.isFinal = !!d.final;
                res.item = i => res[i];
                const results = [res]; results.item = i => results[i];
                this._fire('result', { results, resultIndex: 0 });
            } else if (type === 'error') this._fire('error', { error: d.error, message: '' });
            else if (type === 'end') { this._on = false; this._fire('end'); }
        }
    }
    window.SpeechRecognition = window.webkitSpeechRecognition = Recognition;
})();
