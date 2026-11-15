/*
 * Sesli Kitap (web): Google Drive'daki kitapları tarayıcıda seslendirir.
 * Kaldığın yer, telefondaki uygulamanın Drive yedeğiyle (appDataFolder) eşitlenir.
 */
(function () {
  'use strict';

  const SCOPES = 'https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.appdata';
  const API = 'https://www.googleapis.com/drive/v3';
  const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
  const YEDEK = 'seslikitap-yedek.json';
  const $ = (id) => document.getElementById(id);
  const RENKLER = ['#1F4E5F', '#6B3E26', '#3D5A40', '#5B3A6B', '#7A2E3A', '#2E4A7A', '#6B5B2E', '#34495E'];
  const renk = (s) => RENKLER[Math.abs([...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)) % RENKLER.length];
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---------------------------------------------------------------- Google girişi

  let token = null;
  let tokenBitis = 0;
  let tokenIstemci = null;
  let tokenBekleyen = null;

  function girisHazirla() {
    const id = window.SK_CLIENT_ID;
    if (!id) {
      $('kurulum').textContent = 'Kurulum tamamlanmamış: Google OAuth web istemci kimliği (config.js) eklenmemiş.';
      $('kurulum').classList.remove('gizli');
      $('giris').disabled = true;
      return;
    }
    tokenIstemci = google.accounts.oauth2.initTokenClient({
      client_id: id,
      scope: SCOPES,
      callback: (r) => {
        if (r.error) { tokenBekleyen?.reject(new Error(r.error)); tokenBekleyen = null; return; }
        token = r.access_token;
        tokenBitis = Date.now() + (r.expires_in - 60) * 1000;
        tokenBekleyen?.resolve(token);
        tokenBekleyen = null;
      },
    });
  }

  function tokenAl(etkilesimli) {
    if (token && Date.now() < tokenBitis) return Promise.resolve(token);
    return new Promise((resolve, reject) => {
      tokenBekleyen = { resolve, reject };
      tokenIstemci.requestAccessToken({ prompt: etkilesimli ? 'consent' : '' });
    });
  }

  async function istek(url, secenek = {}, tekrar = true) {
    const t = await tokenAl(false);
    const r = await fetch(url, { ...secenek, headers: { ...(secenek.headers || {}), Authorization: `Bearer ${t}` } });
    if (r.status === 401 && tekrar) { token = null; return istek(url, secenek, false); }
    if (!r.ok) throw new Error(`Drive hatası (${r.status})`);
    return r;
  }

  // ---------------------------------------------------------------- Drive

  async function cocuklar(klasorId) {
    const out = [];
    let sayfa = '';
    do {
      const q = encodeURIComponent(`'${klasorId}' in parents and trashed = false`);
      const f = encodeURIComponent('nextPageToken,files(id,name,mimeType,size,shortcutDetails(targetId,targetMimeType))');
      const r = await (await istek(`${API}/files?q=${q}&fields=${f}&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true${sayfa ? `&pageToken=${sayfa}` : ''}`)).json();
      for (const x of r.files || []) {
        if (x.mimeType === 'application/vnd.google-apps.shortcut' && x.shortcutDetails) {
          out.push({ id: x.shortcutDetails.targetId, name: x.name, mimeType: x.shortcutDetails.targetMimeType });
        } else out.push(x);
      }
      sayfa = r.nextPageToken || '';
    } while (sayfa);
    return out;
  }
  const klasorMu = (x) => x.mimeType === 'application/vnd.google-apps.folder';
  const epubMu = (x) => x.mimeType === 'application/epub+zip' || /\.epub$/i.test(x.name);
  const indir = async (id) => (await istek(`${API}/files/${id}?alt=media&supportsAllDrives=true`)).arrayBuffer();

  // ---------------------------------------------------------------- Yedek (telefonla ortak)

  let yedek = null;
  let yedekId = null;

  async function yedekYukle() {
    const q = encodeURIComponent(`name = '${YEDEK}' and trashed = false`);
    const r = await (await istek(`${API}/files?spaces=appDataFolder&q=${q}&fields=files(id)`)).json();
    yedekId = r.files && r.files[0] ? r.files[0].id : null;
    yedek = yedekId ? await (await istek(`${API}/files/${yedekId}?alt=media`)).json() : { surum: 1, kitaplar: [], konumlar: [], klasorler: [] };
    return yedek;
  }

  /** Bu kitabın konumunu yedeğe yazar (önce en güncel yedeği alıp birleştirir). */
  async function konumKaydet(uri, konum) {
    try {
      await yedekYukle();
      yedek.konumlar = (yedek.konumlar || []).filter((k) => k.uri !== uri);
      yedek.konumlar.push({ uri, alt: '', ...konum, t: Date.now() });
      const kitap = (yedek.kitaplar || []).find((k) => k.uri === uri);
      if (kitap) kitap.lastOpenedAt = Date.now();
      const govde = JSON.stringify(yedek);
      if (yedekId) {
        await istek(`${UPLOAD}/files/${yedekId}?uploadType=media`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: govde });
      } else {
        const sinir = 'sk' + Date.now();
        const meta = JSON.stringify({ name: YEDEK, parents: ['appDataFolder'] });
        const cok = `--${sinir}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${sinir}\r\nContent-Type: application/json\r\n\r\n${govde}\r\n--${sinir}--`;
        await istek(`${UPLOAD}/files?uploadType=multipart`, { method: 'POST', headers: { 'Content-Type': `multipart/related; boundary=${sinir}` }, body: cok });
      }
      $('esitleme').textContent = `Eşitlendi ${new Date().toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })}`;
    } catch (e) {
      $('esitleme').textContent = 'Eşitlenemedi: ' + e.message;
    }
  }

  // ---------------------------------------------------------------- Kitaplık

  const durum = { kok: [], yol: [], liste: null, arama: '' };

  function kitapKart(ad, alt, ilerleme, tikla) {
    const d = document.createElement('div');
    d.className = 'kitap';
    d.innerHTML = `<div class="kapak" style="background:${renk(ad)}">${esc(ad)}</div>
      ${ilerleme > 0 ? `<div class="ilerleme"><div style="width:${Math.round(ilerleme * 100)}%"></div></div>` : ''}
      <div class="ad">${esc(ad)}</div>${alt ? `<div class="ince">${esc(alt)}</div>` : ''}`;
    d.onclick = tikla;
    return d;
  }

  function devamCiz() {
    const kutu = $('devam');
    kutu.innerHTML = '';
    const kitaplar = (yedek.kitaplar || [])
      .filter((k) => k.uri.startsWith('gdrive://file/') && !k.finished && k.progress > 0)
      .sort((a, b) => (b.lastOpenedAt || 0) - (a.lastOpenedAt || 0)).slice(0, 15);
    $('devam-bolum').classList.toggle('gizli', !kitaplar.length);
    for (const k of kitaplar) {
      const id = k.uri.slice('gdrive://file/'.length);
      kutu.appendChild(kitapKart(k.title || 'Kitap', k.author, k.progress, () => kitapAc({ id, name: k.title || 'Kitap', uri: k.uri })));
    }
  }

  async function klasorListesi() {
    // Tüm kitaplar (bağlantıyla eklenen Drive klasörleri, alt klasörler dahil), bellekte saklanır
    if (durum.liste) return durum.liste;
    $('durum').textContent = 'Drive klasörleri okunuyor…';
    const kitaplar = [];
    for (const kl of yedek.klasorler || []) {
      const kuyruk = [{ id: kl.id, yol: [kl.name] }];
      const gorulen = new Set();
      while (kuyruk.length) {
        const grup = kuyruk.splice(0, 8);
        const sonuclar = await Promise.all(grup.map(async (g) => {
          if (gorulen.has(g.id)) return [];
          gorulen.add(g.id);
          try { return (await cocuklar(g.id)).map((x) => ({ x, yol: g.yol })); } catch (e) { return []; }
        }));
        for (const { x, yol } of sonuclar.flat()) {
          if (klasorMu(x)) kuyruk.push({ id: x.id, yol: [...yol, x.name] });
          else if (epubMu(x)) kitaplar.push({ id: x.id, name: x.name.replace(/\.epub$/i, ''), yol, uri: `gdrive://file/${x.id}` });
        }
        $('durum').textContent = `${kitaplar.length} kitap bulundu…`;
      }
    }
    $('durum').textContent = kitaplar.length ? '' : 'Telefondaki uygulamada Drive klasörü eklenmemiş ya da yedek alınmamış.';
    durum.liste = kitaplar;
    return kitaplar;
  }

  async function izgaraCiz() {
    const kutu = $('izgara');
    kutu.innerHTML = '';
    const liste = await klasorListesi();
    const ilerlemeler = Object.fromEntries((yedek.kitaplar || []).map((k) => [k.uri, k.progress || 0]));
    const yolCubugu = $('yol');
    yolCubugu.innerHTML = '';
    if (durum.arama) {
      $('klasor-baslik').textContent = 'Arama sonuçları';
      const a = Metin.aramaAnahtari(durum.arama);
      liste.filter((k) => Metin.aramaAnahtari(k.name + ' ' + k.yol.join(' ')).includes(a)).slice(0, 200)
        .forEach((k) => kutu.appendChild(kitapKart(k.name, k.yol.join(' › '), ilerlemeler[k.uri], () => kitapAc(k))));
      return;
    }
    $('klasor-baslik').textContent = 'Klasörler';
    const p = durum.yol;
    [['Kitaplığım', -1], ...p.map((ad, i) => [ad, i])].forEach(([ad, i], n) => {
      if (n > 0) yolCubugu.append(' › ');
      const a = document.createElement('a');
      a.textContent = ad;
      a.onclick = () => { durum.yol = p.slice(0, i + 1); izgaraCiz(); };
      yolCubugu.appendChild(a);
    });
    const alt = new Map();
    const burada = [];
    for (const k of liste) {
      if (k.yol.length < p.length || p.some((s, i) => k.yol[i] !== s)) continue;
      if (k.yol.length === p.length) burada.push(k);
      else alt.set(k.yol[p.length], (alt.get(k.yol[p.length]) || 0) + 1);
    }
    [...alt.entries()].sort((a, b) => a[0].localeCompare(b[0], 'tr')).forEach(([ad, n]) => {
      const d = document.createElement('div');
      d.className = 'klasor';
      d.innerHTML = `<div class="kapak">📁</div><div class="ad">${esc(ad)}</div><div class="ince">${n} kitap</div>`;
      d.onclick = () => { durum.yol = [...p, ad]; izgaraCiz(); };
      kutu.appendChild(d);
    });
    burada.sort((a, b) => a.name.localeCompare(b.name, 'tr'))
      .forEach((k) => kutu.appendChild(kitapKart(k.name, '', ilerlemeler[k.uri], () => kitapAc(k))));
  }

  // ---------------------------------------------------------------- Okuyucu ve seslendirme

  const oku = { kitap: null, epub: null, bolum: null, cumle: 0, caliyor: false, nesil: 0, sonKayit: 0 };
  const synth = window.speechSynthesis;

  function sesleriDoldur() {
    const sesler = synth.getVoices().filter((v) => v.lang && v.lang.toLowerCase().startsWith('tr'));
    // Doğal sesler (Edge: "Online (Natural)") öne
    sesler.sort((a, b) => (/natural|online/i.test(b.name) - /natural|online/i.test(a.name)));
    const sec = $('ses');
    const kayitli = localStorage.getItem('sk_ses');
    sec.innerHTML = sesler.length ? '' : '<option>Türkçe ses bulunamadı</option>';
    for (const v of sesler) {
      const o = document.createElement('option');
      o.value = v.name;
      o.textContent = v.name.replace('Microsoft ', '').replace(' - Turkish (Turkey)', '');
      if (v.name === kayitli) o.selected = true;
      sec.appendChild(o);
    }
  }

  function sesBul() {
    const ad = $('ses').value;
    return synth.getVoices().find((v) => v.name === ad) || synth.getVoices().find((v) => v.lang.toLowerCase().startsWith('tr'));
  }

  async function kitapAc(k) {
    durdur();
    oku.kitap = k;
    $('kitaplik').classList.add('gizli');
    $('okuyucu').classList.remove('gizli');
    $('kontroller').classList.remove('gizli');
    $('geri').classList.remove('gizli');
    $('ara').classList.add('gizli');
    $('baslik').textContent = k.name;
    $('metin').innerHTML = '<p class="ince">Kitap Drive\'dan indiriliyor…</p>';
    try {
      oku.epub = await Metin.epubAc(await indir(k.id), k.name);
      $('baslik').textContent = oku.epub.baslik;
      const sec = $('bolumler');
      sec.innerHTML = '';
      for (let i = 0; i < oku.epub.bolumSayisi; i++) {
        const o = document.createElement('option');
        o.value = i;
        o.textContent = oku.epub.bolumAdi(i);
        sec.appendChild(o);
      }
      // Kaldığın yer: önce Drive yedeği (telefon), sonra bu tarayıcıdaki kayıt — hangisi yeniyse
      await yedekYukle().catch(() => {});
      const uzak = (yedek.konumlar || []).find((x) => x.uri === k.uri);
      let yerelK = null;
      try { yerelK = JSON.parse(localStorage.getItem('sk_konum_' + k.uri) || 'null'); } catch (e) { /* yok */ }
      const konum = [uzak, yerelK].filter(Boolean).sort((a, b) => (b.t || 0) - (a.t || 0))[0];
      await bolumGoster(konum ? konum.ch : 0, konum);
    } catch (e) {
      $('metin').innerHTML = `<p class="uyari">Kitap açılamadı: ${esc(e.message)}</p>`;
    }
  }

  async function bolumGoster(i, konum) {
    i = Math.max(0, Math.min(oku.epub.bolumSayisi - 1, i || 0));
    let b = await oku.epub.bolum(i);
    // Boş bölümleri (resim sayfaları) atla
    while (!b.cumleler.length && i + 1 < oku.epub.bolumSayisi) b = await oku.epub.bolum(++i);
    oku.bolum = b;
    $('bolumler').value = i;
    let c = 0;
    if (konum) {
      const ex = (konum.excerpt || '').slice(0, 40);
      const byText = ex.length >= 10 ? b.cumleler.findIndex((s) => s.metin.startsWith(ex)) : -1;
      if (byText >= 0) c = byText;
      else {
        const ilk = b.cumleler.findIndex((s) => s.blok >= (konum.bl || 0));
        c = ilk >= 0 ? Math.min(b.cumleler.length - 1, ilk + (konum.se || 0)) : 0;
      }
    }
    oku.cumle = c;
    ciz();
    isaretle(true);
  }

  function ciz() {
    const b = oku.bolum;
    const kutu = $('metin');
    kutu.innerHTML = '';
    b.bloklar.forEach((blok, bi) => {
      const el = document.createElement(blok.baslik ? 'h3' : 'p');
      let son = 0;
      b.cumleler.forEach((s, si) => {
        if (s.blok !== bi) return;
        if (s.bas > son) el.append(blok.metin.slice(son, s.bas));
        const sp = document.createElement('span');
        sp.className = 's';
        sp.dataset.i = si;
        sp.textContent = s.metin;
        sp.onclick = () => { oku.cumle = si; isaretle(true); if (oku.caliyor) baslat(); };
        el.appendChild(sp);
        son = s.son;
      });
      if (son < blok.metin.length) el.append(blok.metin.slice(son));
      kutu.appendChild(el);
    });
  }

  function isaretle(kaydir) {
    document.querySelectorAll('.s.aktif').forEach((e) => e.classList.remove('aktif'));
    const el = document.querySelector(`.s[data-i="${oku.cumle}"]`);
    if (el) {
      el.classList.add('aktif');
      if (kaydir) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
    const b = oku.bolum;
    $('konum').textContent = `${b.baslik} · Bölüm ${b.index + 1}/${oku.epub.bolumSayisi} · Cümle ${oku.cumle + 1}/${b.cumleler.length}`;
    yerelKaydet();
  }

  function konumNesnesi() {
    const s = oku.bolum.cumleler[oku.cumle];
    let ilk = oku.cumle;
    while (ilk > 0 && oku.bolum.cumleler[ilk - 1].blok === s.blok) ilk--;
    return { ch: oku.bolum.index, bl: s.blok, se: oku.cumle - ilk, excerpt: s.metin.slice(0, 80) };
  }

  function yerelKaydet() {
    if (!oku.kitap || !oku.bolum || !oku.bolum.cumleler.length) return;
    localStorage.setItem('sk_konum_' + oku.kitap.uri, JSON.stringify({ ...konumNesnesi(), t: Date.now() }));
    // Çalarken 2 dakikada bir Drive'a da yaz
    if (oku.caliyor && Date.now() - oku.sonKayit > 120000) { oku.sonKayit = Date.now(); konumKaydet(oku.kitap.uri, konumNesnesi()); }
  }

  function baslat() {
    synth.cancel();
    oku.caliyor = true;
    $('oynat').textContent = '⏸';
    const nesil = ++oku.nesil;
    const konus = () => {
      if (nesil !== oku.nesil) return;
      const s = oku.bolum.cumleler[oku.cumle];
      if (!s) return;
      const u = new SpeechSynthesisUtterance(s.metin);
      const v = sesBul();
      if (v) u.voice = v;
      u.lang = 'tr-TR';
      u.rate = parseFloat($('hiz').value) || 1;
      u.onstart = () => isaretle(true);
      u.onend = async () => {
        if (nesil !== oku.nesil) return;
        if (oku.cumle + 1 < oku.bolum.cumleler.length) { oku.cumle++; konus(); return; }
        if (oku.bolum.index + 1 < oku.epub.bolumSayisi) { await bolumGoster(oku.bolum.index + 1); if (nesil === oku.nesil) konus(); }
        else durdur();
      };
      u.onerror = (e) => { if (e.error !== 'interrupted' && e.error !== 'canceled') u.onend(); };
      synth.speak(u);
    };
    konus();
  }

  function durdur() {
    oku.nesil++;
    synth.cancel();
    if (oku.caliyor && oku.kitap && oku.bolum) konumKaydet(oku.kitap.uri, konumNesnesi());
    oku.caliyor = false;
    $('oynat').textContent = '▶';
  }

  function git(delta) {
    const n = oku.cumle + delta;
    if (n >= 0 && n < oku.bolum.cumleler.length) { oku.cumle = n; isaretle(true); if (oku.caliyor) baslat(); }
  }

  async function bolumGit(delta) {
    const calarken = oku.caliyor;
    durdur();
    await bolumGoster(oku.bolum.index + delta);
    if (calarken) baslat();
  }

  // ---------------------------------------------------------------- Olaylar

  function kitapligaDon() {
    durdur();
    $('okuyucu').classList.add('gizli');
    $('kontroller').classList.add('gizli');
    $('geri').classList.add('gizli');
    $('kitaplik').classList.remove('gizli');
    $('ara').classList.remove('gizli');
    $('baslik').textContent = 'Sesli Kitap';
    yedekYukle().then(devamCiz).catch(() => {});
  }

  $('giris').onclick = async () => {
    try {
      await tokenAl(true);
      $('giris').classList.add('gizli');
      $('karsilama').classList.add('gizli');
      $('kitaplik').classList.remove('gizli');
      $('ara').classList.remove('gizli');
      await yedekYukle();
      devamCiz();
      await izgaraCiz();
    } catch (e) {
      $('kurulum').textContent = 'Giriş yapılamadı: ' + e.message;
      $('kurulum').classList.remove('gizli');
    }
  };
  $('geri').onclick = kitapligaDon;
  $('oynat').onclick = () => (oku.caliyor ? durdur() : baslat());
  $('onceki').onclick = () => git(-1);
  $('sonraki').onclick = () => git(1);
  $('onceki-bolum').onclick = () => bolumGit(-1);
  $('sonraki-bolum').onclick = () => bolumGit(1);
  $('bolumler').onchange = async (e) => { const c = oku.caliyor; durdur(); await bolumGoster(+e.target.value); if (c) baslat(); };
  $('ses').onchange = () => { localStorage.setItem('sk_ses', $('ses').value); if (oku.caliyor) baslat(); };
  $('hiz').value = localStorage.getItem('sk_hiz') || '1';
  $('hiz').onchange = () => { localStorage.setItem('sk_hiz', $('hiz').value); if (oku.caliyor) baslat(); };
  let aramaZaman = 0;
  $('ara').oninput = (e) => { clearTimeout(aramaZaman); aramaZaman = setTimeout(() => { durum.arama = e.target.value.trim(); izgaraCiz(); }, 300); };
  document.addEventListener('keydown', (e) => {
    if ($('okuyucu').classList.contains('gizli') || e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
    if (e.code === 'Space') { e.preventDefault(); oku.caliyor ? durdur() : baslat(); }
    if (e.code === 'ArrowRight') git(1);
    if (e.code === 'ArrowLeft') git(-1);
  });
  // Sekme kapanırken / gizlenirken kaldığın yeri Drive'a yaz
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && oku.kitap && oku.bolum) konumKaydet(oku.kitap.uri, konumNesnesi());
  });

  synth.onvoiceschanged = sesleriDoldur;
  sesleriDoldur();
  window.addEventListener('load', () => {
    const bekle = setInterval(() => {
      if (window.google && google.accounts && google.accounts.oauth2) { clearInterval(bekle); girisHazirla(); }
    }, 200);
  });
})();
