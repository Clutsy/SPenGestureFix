(() => {
  'use strict';

  const $  = (s, c = document) => c.querySelector(s);
  const $$ = (s, c = document) => [...c.querySelectorAll(s)];

  /* ---- footer year ---- */
  const yearEl = $('#year');
  if (yearEl) yearEl.textContent = new Date().getFullYear();

  /* ---- nav scroll state ---- */
  const nav = $('.nav');
  const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 12);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---- mobile menu ---- */
  const burger = $('.nav-burger');
  burger.addEventListener('click', () => {
    const open = document.body.classList.toggle('menu-open');
    burger.setAttribute('aria-expanded', String(open));
  });
  $$('.nav-links a').forEach(a =>
    a.addEventListener('click', () => {
      document.body.classList.remove('menu-open');
      burger.setAttribute('aria-expanded', 'false');
    })
  );

  /* ---- reveal on scroll ---- */
  const io = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        e.target.classList.add('visible');
        io.unobserve(e.target);
      }
    });
  }, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });
  $$('.reveal').forEach(el => io.observe(el));

  /* ---- active nav link ---- */
  const navLinks = $$('.nav-links a');
  const sectionIo = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        navLinks.forEach(a =>
          a.classList.toggle('active', a.getAttribute('href') === '#' + e.target.id)
        );
      }
    });
  }, { rootMargin: '-40% 0px -55% 0px' });
  $$('main section[id]').forEach(s => sectionIo.observe(s));

  /* ---- hero counters ---- */
  const animateCount = el => {
    const target = +el.dataset.count;
    const dur = 1100;
    const t0 = performance.now();
    const tick = now => {
      const p = Math.min((now - t0) / dur, 1);
      el.textContent = Math.round(target * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  };
  const cntIo = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        e.target.querySelectorAll('[data-count]').forEach(animateCount);
        cntIo.unobserve(e.target);
      }
    });
  }, { threshold: 0.4 });
  const stats = $('.hero-stats');
  if (stats) cntIo.observe(stats);

  /* ---- Air Command wheel demo ---- */
  const wheel = $('.wheel');
  const core = $('.wheel-core');
  if (wheel && core) {
    core.addEventListener('click', () => wheel.classList.toggle('open'));
    // auto-open the first time it scrolls into view
    const wIo = new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          setTimeout(() => wheel.classList.add('open'), 650);
          wIo.disconnect();
        }
      });
    }, { threshold: 0.35 });
    wIo.observe(wheel);
    $$('.wheel-slot').forEach(slot =>
      slot.addEventListener('click', () => wheel.classList.remove('open'))
    );
  }

  /* ---- gesture mapping demo ---- */
  const GESTURES = {
    single: { label: 'Single click', action: 'Screenshot',       desc: 'Captured into Pictures/SPenScreenshots' },
    double: { label: 'Double click', action: 'Quick Note',       desc: 'Offline editor opens instantly' },
    long:   { label: 'Long press',   action: 'Open S Pen Wheel', desc: 'Air Command fan appears lower-right' }
  };
  const gOut = $('#gesture-out');
  if (gOut) {
    const gGesture = $('#go-gesture'), gAction = $('#go-action'), gDesc = $('#go-desc');
    $$('.g-btn').forEach(btn =>
      btn.addEventListener('click', () => {
        $$('.g-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        const g = GESTURES[btn.dataset.g];
        if (!g) return;
        gGesture.textContent = g.label;
        gAction.textContent = g.action;
        gDesc.textContent = g.desc;
        gOut.classList.remove('flash');
        void gOut.offsetWidth; // restart animation
        gOut.classList.add('flash');
      })
    );
  }

  /* ---- copy buttons ---- */
  const copyText = async text => {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch { ok = false; }
      ta.remove();
      return ok;
    }
  };
  $$('.copy-btn').forEach(btn =>
    btn.addEventListener('click', async () => {
      const code = btn.closest('.codeblock').querySelector('pre code');
      const ok = await copyText(code.innerText);
      btn.classList.add('copied');
      btn.textContent = ok ? 'Copied' : 'Failed';
      setTimeout(() => {
        btn.classList.remove('copied');
        btn.textContent = 'Copy';
      }, 1500);
    })
  );

  /* ---- GitHub releases ---- */
  const REPO = 'Clutsy/SPenGestureFix';
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
  );
  const fmtDate = s => {
    try {
      return new Date(s).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    } catch { return ''; }
  };
  const fmtSize = b => {
    if (typeof b !== 'number') return '';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0, n = b;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(1)) + ' ' + units[i];
  };
  const cleanMd = t =>
    String(t || '')
      .replace(/<[^>]*>/g, '')
      .replace(/!?\[([^\]]*)\]\(([^)]*)\)/g, '$1')
      .replace(/[#*`>_~]/g, '')
      .replace(/\r/g, '')
      .split('\n').map(l => l.trim()).filter(Boolean).slice(0, 3).join(' · ')
      .slice(0, 260);

  const downloadSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/></svg>';

  const fmtNum = n => {
    if (typeof n !== 'number') return '—';
    if (n >= 1000) return (n / 1000).toFixed(n >= 10000 ? 0 : 1).replace(/\.0$/, '') + 'k';
    return String(n);
  };
  const setCounters = (id, value) => {
    $$(id).forEach(el => { el.textContent = value; });
  };

  const loadStars = async () => {
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}`);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      setCounters('.js-stars', fmtNum(data.stargazers_count));
    } catch {
      setCounters('.js-stars', '—');
    }
  };
  loadStars();

  const assetLabel = name =>
    String(name || '').replace(/SPenGestureFix\.apk$/i, 'SPGF.apk');

  const releaseCard = r => {
    const buttons = (r.assets || []).map(a => `
      <a class="asset-btn" href="${esc(a.browser_download_url)}" target="_blank" rel="noopener">
        ${downloadSvg}${esc(assetLabel(a.name))}
        ${a.size ? `<span class="asset-size">${fmtSize(a.size)}</span>` : ''}
      </a>`).join('');
    return `
      <article class="release card">
        <div class="release-head">
          <span class="release-tag">${esc(r.tag_name)}</span>
          ${r.name ? `<span class="release-name">${esc(r.name)}</span>` : ''}
          ${r.prerelease ? '<span class="badge badge-warn">Pre-release</span>' : ''}
          <span class="release-date">${fmtDate(r.published_at)}</span>
        </div>
        ${r.body ? `<p class="release-notes">${esc(cleanMd(r.body))}</p>` : ''}
        ${buttons ? `<div class="release-assets">${buttons}</div>` : ''}
      </article>`;
  };

  const loadReleases = async () => {
    const list = $('#release-list');
    if (!list) return;
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=8`, {
        headers: { 'Accept': 'application/vnd.github+json' }
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const releases = await res.json();
      if (!Array.isArray(releases)) throw new Error('Unexpected response');

      if (releases.length === 0) {
        list.innerHTML = `
          <div class="release-state">
            No releases published yet — watch the
            <a href="https://github.com/${REPO}/releases" target="_blank" rel="noopener">repository</a>
            or build the APK from source.
          </div>`;
        return;
      }

      const latest = releases[0];
      const vBadge = $('#dl-version');
      if (vBadge && latest.tag_name) vBadge.textContent = latest.tag_name;
      const apk = (latest.assets || []).find(a => /\.apk$/i.test(a.name));
      const dlBtn = $('#dl-apk');
      if (dlBtn && apk) dlBtn.href = apk.browser_download_url;

      const totalDownloads = releases.reduce((sum, r) =>
        sum + (r.assets || []).reduce((s, a) => s + (a.download_count || 0), 0), 0);
      setCounters('.js-downloads', fmtNum(totalDownloads));

      list.innerHTML = releases.map(releaseCard).join('');
    } catch {
      setCounters('.js-downloads', '—');
      list.innerHTML = `
        <div class="release-state">
          Couldn't load releases right now (offline or GitHub rate limit). Open
          <a href="https://github.com/${REPO}/releases" target="_blank" rel="noopener">releases on GitHub</a> directly.
        </div>`;
    }
  };
  loadReleases();
})();

/* ---- photo slots: mark loaded images, keep placeholders for missing ones ---- */
document.querySelectorAll('.photo-slot img').forEach(img => {
  const slot = img.closest('.photo-slot');
  const mark = ok => {
    if (ok && slot) slot.classList.add('filled');
    else if (slot) slot.classList.remove('filled');
  };
  if (img.complete && img.naturalWidth > 0) mark(true);
  else {
    img.addEventListener('load', () => mark(true));
    img.addEventListener('error', () => mark(false));
  }
});
