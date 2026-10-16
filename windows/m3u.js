'use strict';

/** M3U / M3U8 playlist parser (same rules as the Android TV app). */

const ATTR = /([\w-]+)\s*=\s*"([^"]*)"/g;

function titleOf(line) {
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') inQuotes = !inQuotes;
    else if (c === ',' && !inQuotes) return line.slice(i + 1).trim();
  }
  return '';
}

function decode(s) {
  try { return decodeURIComponent(s); } catch (_) { return s; }
}

function parseM3U(text) {
  const out = [];
  let name = null, logo = null, group = null, extGroup = null, ua = null, ref = null;

  for (const raw of String(text).split(/\r?\n/)) {
    const line = raw.trim().replace(/^﻿/, '');
    if (!line) continue;

    if (/^#EXTINF/i.test(line)) {
      const attrs = {};
      for (const m of line.matchAll(ATTR)) attrs[m[1].toLowerCase()] = m[2];
      name = titleOf(line) || attrs['tvg-name'] || '';
      logo = attrs['tvg-logo'] || null;
      group = attrs['group-title'] || null;
      ua = attrs['user-agent'] || attrs['http-user-agent'] || null;
      ref = attrs['http-referrer'] || attrs['referrer'] || attrs['referer'] || null;
    } else if (/^#EXTGRP:/i.test(line)) {
      extGroup = line.slice(8).trim() || null;
    } else if (/^#EXTVLCOPT:/i.test(line)) {
      const [k, ...rest] = line.slice(11).trim().split('=');
      const v = rest.join('=').trim();
      const key = k.trim().toLowerCase();
      if (key === 'http-user-agent') ua = v;
      else if (key === 'http-referrer' || key === 'http-referer') ref = v;
    } else if (line.startsWith('#')) {
      // other directives ignored
    } else {
      let url = line;
      const headers = {};
      const pipe = url.indexOf('|');
      if (pipe > 0) {
        for (const kv of url.slice(pipe + 1).split('&')) {
          const i = kv.indexOf('=');
          if (i > 0) headers[kv.slice(0, i).trim()] = decode(kv.slice(i + 1));
        }
        url = url.slice(0, pipe);
      }
      for (const k of Object.keys(headers)) {
        const lk = k.toLowerCase();
        if (lk === 'user-agent') { if (!ua) ua = headers[k]; delete headers[k]; }
        else if (lk === 'referer' || lk === 'referrer') { if (!ref) ref = headers[k]; delete headers[k]; }
      }
      const finalName = (name && name.trim()) ||
        url.split('?')[0].split('/').pop() || url;
      const g = group || extGroup || 'Other';
      out.push({
        name: finalName,
        url,
        logo,
        group: g,
        ua,
        referrer: ref,
        headers,
        key: g + '|' + finalName,
      });
      name = logo = group = extGroup = ua = ref = null;
    }
  }
  return out;
}

module.exports = { parseM3U };
