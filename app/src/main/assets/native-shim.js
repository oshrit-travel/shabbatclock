/* Connects the unchanged Shabbat clock page to the native Android layer (alarms + background audio). */
(function () {
  if (window.__nativeShim) return;
  var B = window.AndroidBridge;
  if (!B) return;
  window.__nativeShim = true;

  try {
    var st = document.createElement('style');
    st.textContent = '#audioStatusBtn{display:none !important}';
    document.head.appendChild(st);
  } catch (e) {}

  // While the page's own scheduler runs, stay silent: the native alarms already handle the real sound.
  var inSched = false;
  var origCheck = window.checkScheduledEvents;
  window.checkScheduledEvents = function () {
    inSched = true;
    try { return origCheck.apply(this, arguments); } finally { inSched = false; }
  };

  var origPlaySong = window.playSongSlot;
  window.playSongSlot = function (v) {
    if (!v) return;
    if (v.source === 'local') {
      if (inSched) return;
      try { B.playSlot(String(v.dbSlot || v.id), 0); } catch (e) {}
      return;
    }
    return origPlaySong.apply(this, arguments); // YouTube: foreground only
  };

  window.playSiren = function (done, force) {
    var dur = force || settings.sirenDuration || 15;
    if (!inSched) {
      try { B.playSiren(settings.sirenType === 'local' ? 'siren' : '', dur, force || 0); } catch (e) {}
    }
    setTimeout(function () { if (done) done(); }, dur * 1000 + 300);
  };

  var origStop = window.stopAllPlayback;
  window.stopAllPlayback = function () {
    try { origStop(); } catch (e) {}
    if (!inSched) { try { B.stopAll(); } catch (e) {} }
  };

  window.playReminderChimeWithDucking = function () {
    if (inSched) return;
    try { B.chimeNow(); } catch (e) {}
  };

  // ---- plan: every future event, computed with the page's own rules ----
  var warned = false;
  function buildPlan() {
    var t = times;
    if (!t || !t.candleLighting) return null;
    var ev = [], now = Date.now(), unsupported = false;
    var c = t.candleLighting.getTime();
    var off = (settings.fridaySongOffset || 0) * 60000;
    function add(ts, o) { if (ts && ts > now + 1500) { o.t = ts; ev.push(o); } }

    if (settings.reminders && settings.reminders.enabled) {
      ['hour', 'half'].forEach(function (k) {
        var items = settings.reminders[k].selected.concat(settings.reminders[k].custom);
        if (!items.length) return;
        add(c - (k === 'hour' ? 60 : 30) * 60000, {
          kind: 'chime',
          title: (k === 'hour' ? 'שעה' : 'חצי שעה') + ' לפני כניסת שבת',
          body: items.join(' • ')
        });
      });
    }

    var fr = getSlotValue(SONG_SLOTS[0]);
    if (fr && fr.source === 'local') add(c - off, { kind: 'song', slot: 'friday', title: 'שיר כניסת שבת', body: 'שבת שלום' });
    else if (fr && fr.id) unsupported = true;

    if (settings.sirenEnabled && !t.fridayIsYomTov) {
      var dur = settings.sirenDuration || 15, isLocal = settings.sirenType === 'local';
      if (settings.sirenType === 'url') unsupported = true;
      add(c, { kind: 'siren', slot: isLocal ? 'siren' : '', dur: dur, cap: isLocal ? dur : 0, title: 'כניסת שבת', body: 'שבת שלום' });
    }

    var mo = getSlotValue(SONG_SLOTS[1]);
    if (mo && mo.source === 'local') {
      var mz = function (ts) { add(ts, { kind: 'song', slot: 'motzash', title: 'מוצאי שבת', body: 'שבוע טוב' }); };
      if (!t.chagExitTime) {
        var tim = settings.motzashTiming;
        if (t.havdalah && (tim === 'regular' || tim === 'both')) mz(t.havdalah.getTime());
        if (t.rabbeinuTam && (tim === 'rt' || tim === 'both')) mz(t.rabbeinuTam.getTime());
      } else mz(t.chagExitTime.getTime());
    } else if (mo && mo.id) unsupported = true;

    if (settings.erevChagEnabled && t.nextErevChag && t.nextErevChag.holidayKey) {
      var key = t.nextErevChag.holidayKey, cs = settings.chagSongs[key];
      if (cs && cs.source === 'local') add(t.nextErevChag.date.getTime() - off, { kind: 'song', slot: 'chag_' + key, title: 'ערב חג', body: 'חג שמח' });
      else if (cs && cs.id) unsupported = true;
    }

    if (unsupported && !warned) {
      warned = true;
      try { B.toast('שיר/צפירה מיוטיוב או מקישור לא יתנגנו כשהמסך כבוי. כדאי להעלות קובץ מהמכשיר.'); } catch (e) {}
    }
    return ev;
  }

  var lastHash = '';
  function push() {
    try {
      var plan = buildPlan();
      if (plan === null) return;
      var js = JSON.stringify(plan);
      if (js === lastHash) return;
      lastHash = js;
      B.setPlan(js);
    } catch (e) {}
  }

  // ---- audio files: copy from the page's IndexedDB into native storage ----
  var slots = ['friday', 'motzash', 'siren'].concat(CHAG_KEYS.map(function (k) { return 'chag_' + k; }));
  var syncing = false;
  function toB64(u8) {
    var s = '', ch = 0x8000;
    for (var i = 0; i < u8.length; i += ch) s += String.fromCharCode.apply(null, u8.subarray(i, i + ch));
    return btoa(s);
  }
  async function syncAudio() {
    if (syncing) return;
    syncing = true;
    try {
      for (var i = 0; i < slots.length; i++) {
        var rec = await loadAudioFile(slots[i]);
        if (!rec || !rec.blob) continue;
        var size = rec.blob.size, name = rec.filename || slots[i];
        if (B.hasAudio(slots[i], name, size)) continue;
        var CH = 384 * 1024, n = Math.max(1, Math.ceil(size / CH));
        for (var k = 0; k < n; k++) {
          var buf = await rec.blob.slice(k * CH, (k + 1) * CH).arrayBuffer();
          if (!B.audioChunk(slots[i], k, toB64(new Uint8Array(buf)), k === n - 1, name, size)) break;
        }
      }
    } catch (e) {} finally { syncing = false; }
  }

  // ---- built-in songs: imported once, only into slots the user hasn't configured ----
  var SEED_FLAG = 'shabbatClock_defaultSongsSeeded_v2';
  async function seedDefaultSongs() {
    try {
      if (localStorage.getItem(SEED_FLAG) === '1') return;
      if (typeof importBundledSong !== 'function') return;
      var allDone = true;
      for (var i = 0; i < 2; i++) {
        var slot = SONG_SLOTS[i];
        var val = getSlotValue(slot) || {};
        var rec = await loadAudioFile(slot.dbSlot);
        if (rec || val.source === 'local' || val.id) continue; // user already chose something: never override
        var okImport = await importBundledSong(slot); // same code path as the "שיר מובנה" button
        if (!okImport) allDone = false;
      }
      if (allDone) localStorage.setItem(SEED_FLAG, '1');
    } catch (e) {}
  }

  function cycle() { seedDefaultSongs().then(syncAudio).then(push); }
  setTimeout(cycle, 4000);
  setInterval(cycle, 15000);
})();
