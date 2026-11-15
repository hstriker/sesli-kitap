/*
 * Sesli Kitap (web) — EPUB okuma, bölüm metni çıkarma ve Türkçe cümle bölme.
 * Telefondaki Kotlin kodunun (core/) karşılığıdır; paragraf ve cümle bölünmesi aynı olsun ki
 * cihazlar arası "kaldığın yer" aynı cümleye denk gelsin.
 */
(function (global) {
  'use strict';

  // ---------------------------------------------------------------- Yardımcılar

  const TR = 'tr-TR';
  const temizle = (s) => (s || '')
    .replace(/[­​‌‍⁠﻿]/g, '')
    .replace(/[\s   　]+/g, ' ')
    .trim();

  function aramaAnahtari(s) {
    return temizle((s || '').toLocaleLowerCase(TR)
      .replace(/ı/g, 'i').replace(/ş/g, 's').replace(/ğ/g, 'g').replace(/ü/g, 'u').replace(/ö/g, 'o').replace(/ç/g, 'c')
      .normalize('NFD').replace(/\p{M}+/gu, ''));
  }

  /** Baytları metne çevirir: geçerli UTF-8 değilse Türkçe Windows-1254 varsayılır. */
  function cozBayt(bytes) {
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^﻿/, '');
    } catch (e) {
      return new TextDecoder('windows-1254').decode(bytes);
    }
  }

  function yolCoz(dir, href) {
    let h = (href || '').split('#')[0].split('?')[0];
    try { h = decodeURIComponent(h); } catch (e) { /* olduğu gibi */ }
    const parts = h.startsWith('/') || !dir ? [] : dir.split('/');
    for (const seg of h.split('/')) {
      if (seg === '' || seg === '.') continue;
      if (seg === '..') parts.pop(); else parts.push(seg);
    }
    return parts.filter(Boolean).join('/');
  }
  const dirOf = (p) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '');
  const yerel = (el) => (el.localName || el.nodeName || '').toLowerCase().replace(/^.*:/, '');

  // ---------------------------------------------------------------- Cümle bölücü (SentenceSplitter.kt)

  const KISALTMA = new Set(('dr prof doç doc yrd av op uzm sn bkz bk bknz vb vs vd örn orn sf ss no nu nr st mah cad sok sk apt blv ' +
    'böl md mad alb yzb bnb tğm ütğm org gen korg tümg tuğg ltd şti hz yy çev haz ed yay krş karş tel fak üniv ünv ' +
    'cilt sy ms mö mr mrs jr sr vol pp fig ibid etc ca km kg gr cm mm lt ml sa dk tah ort max min şek tab ' +
    'hk tic müd gnl bşk öğr arş müh ecz dt vet').split(' '));
  const KAPANIS = new Set(['"', "'", '”', '’', '»', ')', ']', '}', '›']);
  const ACILIS = new Set(['"', "'", '“', '‘', '«', '(', '[', '—', '–', '-', '‹', '¿', '¡']);
  const MAX = 350;
  const buyukMu = (c) => c !== c.toLocaleLowerCase(TR) && c === c.toLocaleUpperCase(TR);
  const rakamMi = (c) => c >= '0' && c <= '9';
  const harfMi = (c) => /\p{L}/u.test(c);
  const bosluk = (c) => /\s/.test(c);

  function sinirMi(t, i, j, k) {
    const next = t[k];
    const p = t[i];
    if (!(buyukMu(next) || rakamMi(next) || ACILIS.has(next))) return false;
    if (p === '!' || p === '?') return true;
    if (p === '…' || t[i + 1] === '.') return true;
    let w = i;
    while (w > 0 && !bosluk(t[w - 1]) && !ACILIS.has(t[w - 1])) w--;
    const kelime = t.slice(w, i);
    if (!kelime) return true;
    const kucuk = kelime.toLocaleLowerCase(TR).replace(/^[(["']+|[(["']+$/g, '');
    if (KISALTMA.has(kucuk)) return false;
    if (kelime.length === 1 && harfMi(kelime)) return false;
    if (kelime.includes('.') && kelime.replace(/\./g, '').length <= 4) return false;
    if (/^[IVXLCDM]{1,4}$/.test(kelime)) return false;
    if (/^\d{1,3}$/.test(kelime)) return false;
    return true;
  }

  function kirp(t, a, b) {
    while (a < b && bosluk(t[a])) a++;
    while (b > a && bosluk(t[b - 1])) b--;
    if (a >= b || !/[\p{L}\p{N}]/u.test(t.slice(a, b))) return null;
    return [a, b];
  }

  function uzunBol(t, s) {
    if (s[1] - s[0] <= MAX) return [s];
    const out = [];
    let a = s[0];
    while (s[1] - a > MAX) {
      const limit = a + MAX;
      let cut = -1;
      for (const isaretler of [';:', ',', '—–']) {
        for (let p = limit; p >= a + MAX / 3; p--) {
          if (isaretler.includes(t[p - 1]) && p < s[1] && bosluk(t[p])) { cut = p; break; }
        }
        if (cut > 0) break;
      }
      if (cut < 0) {
        cut = limit;
        for (let p = limit; p >= a + MAX / 3; p--) if (bosluk(t[p])) { cut = p; break; }
      }
      const k = kirp(t, a, cut);
      if (k) out.push(k);
      a = cut;
    }
    const k = kirp(t, a, s[1]);
    if (k) out.push(k);
    return out;
  }

  /** Paragrafı cümlelere böler: [[başlangıç, bitiş], ...] */
  function cumleler(t) {
    const ham = [];
    let start = 0;
    let i = 0;
    const n = t.length;
    while (i < n) {
      const c = t[i];
      if (c === '.' || c === '!' || c === '?' || c === '…') {
        let j = i;
        while (j < n && '.!?…'.includes(t[j])) j++;
        while (j < n && KAPANIS.has(t[j])) j++;
        if (j >= n) break;
        if (!bosluk(t[j])) { i = j; continue; }
        let k = j;
        while (k < n && bosluk(t[k])) k++;
        if (k >= n) break;
        if (sinirMi(t, i, j, k)) { ham.push([start, j]); start = k; }
        i = k;
        continue;
      }
      i++;
    }
    if (start < n) ham.push([start, n]);
    const out = [];
    for (const s of ham) {
      const k = kirp(t, s[0], s[1]);
      if (k) out.push(...uzunBol(t, k));
    }
    return out;
  }

  // ---------------------------------------------------------------- Bölüm metni (ChapterTextExtractor.kt)

  const BLOK = new Set(('p div section article aside header footer main nav h1 h2 h3 h4 h5 h6 li ul ol dl dt dd ' +
    'blockquote pre figure figcaption table tr td th caption hr body center address details summary').split(' '));
  const BASLIK = new Set(['h1', 'h2', 'h3', 'h4', 'h5', 'h6']);
  const AT = new Set(('script style noscript svg math img audio video iframe object embed rt rp head template button input select').split(' '));
  const NOT_ISARETI = /^[[(]?(\d{1,3}|[*†‡]{1,3}|[ivxlc]{1,5})[\])]?$/i;
  const SAYFA_NO = /^[[(]?(s\.|sayfa)?\s*\d{1,4}[\])]?$/i;

  function temizleGovde(body) {
    const sil = [];
    for (const el of body.querySelectorAll('*')) {
      const tag = el.localName;
      if (AT.has(tag)) { sil.push(el); continue; }
      const type = (el.getAttribute('epub:type') || '').toLowerCase();
      const role = (el.getAttribute('role') || '').toLowerCase();
      const cls = (el.getAttribute('class') || '').toLowerCase();
      const yazi = el.textContent.trim();
      if (el.getAttribute('aria-hidden') === 'true' || el.hasAttribute('hidden')) { sil.push(el); continue; }
      if (type.includes('pagebreak') || role === 'doc-pagebreak') { sil.push(el); continue; }
      if (/pagenum|page-number|pagebreak|sayfa/.test(cls) && SAYFA_NO.test(yazi)) { sil.push(el); continue; }
      if (type.includes('noteref') || role === 'doc-noteref') { sil.push(el); continue; }
      if (/\b(footnotes?|endnotes?|rearnotes?|note)\b/.test(type) || ['doc-footnote', 'doc-endnote', 'doc-endnotes'].includes(role)) { sil.push(el); continue; }
      if (tag === 'sup' && NOT_ISARETI.test(yazi)) { sil.push(el); continue; }
      if (tag === 'a' && (el.getAttribute('href') || '').includes('#') && NOT_ISARETI.test(yazi)) { sil.push(el); continue; }
      if (cls.split(/\s+/).some((c) => ['footnote', 'footnotes', 'dipnot', 'endnote'].includes(c))) { sil.push(el); continue; }
    }
    sil.forEach((e) => e.remove());
  }

  /** XHTML -> { baslik, bloklar: [{ metin, baslik: bool }] } */
  function bolumMetni(html) {
    const duzeltilmis = html.replace(/<(title|script|style|textarea|a|span|div|p|iframe)(\s[^<>]*?)?\/>/gi, '<$1$2></$1>');
    const doc = new DOMParser().parseFromString(duzeltilmis, 'text/html');
    const body = doc.body;
    if (!body) return { baslik: null, bloklar: [] };
    temizleGovde(body);
    const bloklar = [];
    let tampon = '';
    let baslikDerinlik = 0;
    const bosalt = () => {
      const t = temizle(tampon);
      tampon = '';
      if (t && /[\p{L}\p{N}]/u.test(t)) bloklar.push({ metin: t, baslik: baslikDerinlik > 0 });
    };
    const gez = (node) => {
      if (node.nodeType === 3) { tampon += node.nodeValue; return; }
      if (node.nodeType !== 1) return;
      const tag = node.localName;
      if (BLOK.has(tag) || tag === 'br') bosalt();
      if (BASLIK.has(tag)) baslikDerinlik++;
      for (const c of node.childNodes) gez(c);
      if (BLOK.has(tag)) bosalt();
      if (BASLIK.has(tag)) baslikDerinlik--;
    };
    gez(body);
    bosalt();
    const ilkBaslik = bloklar.find((b) => b.baslik);
    return { baslik: ilkBaslik ? ilkBaslik.metin : temizle(doc.title) || null, bloklar };
  }

  // ---------------------------------------------------------------- EPUB (EpubParser.kt)

  async function epubAc(arrayBuffer, dosyaAdi) {
    const zip = await JSZip.loadAsync(arrayBuffer);
    const isimler = Object.keys(zip.files);
    const bul = (p) => {
      if (zip.files[p]) return p;
      const k = p.toLowerCase();
      return isimler.find((n) => n.toLowerCase() === k) || null;
    };
    const oku = async (p) => {
      const n = bul(p);
      if (!n) return null;
      return cozBayt(await zip.files[n].async('uint8array'));
    };
    const xml = (t) => new DOMParser().parseFromString(t, 'application/xml');
    const elemanlar = (doc, ad) => Array.from(doc.getElementsByTagName('*')).filter((e) => yerel(e) === ad);

    let opfYol = null;
    const cont = await oku('META-INF/container.xml');
    if (cont) {
      const rf = elemanlar(xml(cont), 'rootfile')[0];
      if (rf) opfYol = bul(yolCoz('', rf.getAttribute('full-path')));
    }
    if (!opfYol) opfYol = isimler.find((n) => n.toLowerCase().endsWith('.opf'));
    if (!opfYol) throw new Error('Kitap içerik dosyası bulunamadı');
    const opf = xml(await oku(opfYol));
    const taban = dirOf(opfYol);

    const baslik = temizle((elemanlar(opf, 'title')[0] || {}).textContent) || dosyaAdi.replace(/\.[^.]+$/, '');
    const yazar = temizle((elemanlar(opf, 'creator')[0] || {}).textContent);

    const manifest = {};
    for (const it of elemanlar(opf, 'item')) {
      manifest[it.getAttribute('id')] = { yol: bul(yolCoz(taban, it.getAttribute('href'))) || yolCoz(taban, it.getAttribute('href')),
        tur: (it.getAttribute('media-type') || '').toLowerCase(), ozellik: it.getAttribute('properties') || '' };
    }
    const icerikMi = (m) => m && (m.tur.includes('html') || /\.(x?html?|xml)$/i.test(m.yol)) && !/\.(opf|ncx)$/i.test(m.yol);
    let spine = elemanlar(opf, 'itemref')
      .filter((r) => r.getAttribute('linear') !== 'no')
      .map((r) => manifest[r.getAttribute('idref')])
      .filter((m) => icerikMi(m) && bul(m.yol))
      .map((m) => bul(m.yol));
    if (!spine.length) spine = Object.values(manifest).filter(icerikMi).map((m) => m.yol).filter(bul);
    spine = [...new Set(spine)];
    if (!spine.length) throw new Error('Kitapta okunabilir bölüm bulunamadı');

    // İçindekiler: NCX'ten bölüm adları (yoksa "Bölüm n")
    const adlar = {};
    const ncx = Object.values(manifest).find((m) => m.tur.includes('ncx'));
    if (ncx) {
      const t = await oku(ncx.yol);
      if (t) {
        const d = xml(t);
        for (const np of elemanlar(d, 'navpoint')) {
          const label = temizle((elemanlar(np, 'text')[0] || {}).textContent);
          const src = (elemanlar(np, 'content')[0] || { getAttribute: () => '' }).getAttribute('src');
          const p = bul(yolCoz(dirOf(ncx.yol), src));
          if (p && label && !adlar[p]) adlar[p] = label;
        }
      }
    }
    const bolumAdi = (i) => adlar[spine[i]] || `Bölüm ${i + 1}`;

    const onbellek = new Map();
    async function bolum(i) {
      if (onbellek.has(i)) return onbellek.get(i);
      const html = await oku(spine[i]);
      const m = html ? bolumMetni(html) : { baslik: null, bloklar: [] };
      // Cümleler: { blok, bas, son, metin }
      const cs = [];
      m.bloklar.forEach((b, bi) => {
        for (const [a, z] of cumleler(b.metin)) cs.push({ blok: bi, bas: a, son: z, metin: b.metin.slice(a, z) });
      });
      const sonuc = { index: i, baslik: adlar[spine[i]] || m.baslik || bolumAdi(i), bloklar: m.bloklar, cumleler: cs };
      onbellek.set(i, sonuc);
      if (onbellek.size > 4) onbellek.delete(onbellek.keys().next().value);
      return sonuc;
    }

    return { baslik, yazar, bolumSayisi: spine.length, bolumAdi, bolum };
  }

  const api = { temizle, aramaAnahtari, cumleler, bolumMetni, epubAc, cozBayt };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else global.Metin = api;
})(typeof window !== 'undefined' ? window : globalThis);
