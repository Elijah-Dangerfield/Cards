// Renders dashboard.json (built by ../build.py). No dependencies, no build step.

const PLATFORM_LABELS = { tiktok: "TikTok", reddit: "Reddit", instagram: "Instagram" };
const VERDICT_LABELS = { keep: "Keep", iterate: "Change it", kill: "Stop", wait: "Too early" };
const VERDICT_ICONS = {
  keep: '<path d="M3 8.5l3 3 7-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  iterate: '<path d="M13 8a5 5 0 1 1-1.5-3.5M13 2.5V5h-2.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  kill: '<path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
  wait: '<circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 5v3.2l2 1.3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
};

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const platformLabel = (p) => PLATFORM_LABELS[p] || p;
const platformColor = (p) => css(`--${p}`) || css("--other");
const escapeHtml = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const fmt = {
  money: (v) => (v == null ? "—" : `$${v.toLocaleString(undefined, { minimumFractionDigits: v < 100 ? 2 : 0, maximumFractionDigits: 2 })}`),
  int: (v) => (v == null ? "—" : Math.round(v).toLocaleString()),
  pct: (v) => (v == null ? "—" : `${(v * 100).toFixed(v < 0.1 ? 1 : 0)}%`),
  date: (iso) => (iso ? new Date(`${iso.slice(0, 10)}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" }) : "—"),
};

async function load() {
  const params = new URLSearchParams(location.search);
  const example = params.has("example");
  const url = example ? "../example-data/dashboard.json" : "../data/dashboard.json";
  if (params.get("theme") === "light") document.documentElement.dataset.theme = "light";
  $("source-badge").hidden = !example;
  try {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) throw new Error(res.status);
    render(await res.json());
  } catch {
    $("empty").hidden = false;
  }
}

function render(data) {
  $("content").hidden = false;
  $("meta").textContent = data.lastPull
    ? `Numbers as of ${fmt.date(data.lastPull)} · ${data.ads.length} ad${data.ads.length === 1 ? "" : "s"}`
    : "No pulls yet";

  const report = data.report || {};
  if (report.nextAction) {
    $("next-title").textContent = report.nextAction.title;
    $("next-why").textContent = report.nextAction.why || "";
  } else {
    $("next").hidden = true;
  }
  $("headline").textContent = report.headline || "";

  renderTiles(data.totals);
  renderSpendChart(data);
  renderCpiChart(data.ads);
  renderTable(data.ads);
  renderCreativeNotes(data.ads);
  renderExperiments(report.experiments || []);
}

function renderTiles(t) {
  const used = t.budget ? Math.min(t.spend / t.budget, 1) : 0;
  $("tiles").innerHTML = `
    <div class="tile">
      <span class="label">Spent</span>
      <span class="value">${fmt.money(t.spend)}</span>
      <span class="detail">of ${fmt.money(t.budget)} budgeted</span>
      <div class="meter" role="img" aria-label="${Math.round(used * 100)}% of budget spent"><span style="width:${used * 100}%"></span></div>
    </div>
    <div class="tile">
      <span class="label">Installs</span>
      <span class="value">${fmt.int(t.installs)}</span>
      <span class="detail">from App Store campaign links</span>
    </div>
    <div class="tile">
      <span class="label">Cost per install</span>
      <span class="value">${fmt.money(t.cpi)}</span>
      <span class="detail">across every platform</span>
    </div>
    <div class="tile">
      <span class="label">Clicks</span>
      <span class="value">${fmt.int(t.clicks)}</span>
      <span class="detail">${t.clicks && t.spend ? `${fmt.money(t.spend / t.clicks)} each` : "&nbsp;"}</span>
    </div>`;
}

function legend(el, platforms) {
  el.innerHTML = platforms
    .map((p) => `<span><i class="swatch" style="background:${platformColor(p)}"></i>${escapeHtml(platformLabel(p))}</span>`)
    .join("");
}

function niceMax(v) {
  if (!v) return 1;
  const pow = 10 ** Math.floor(Math.log10(v));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * pow >= v / 4) * pow;
  return Math.ceil(v / step) * step;
}

// Lifetime spend per platform over time. Line chart, crosshair + tooltip on hover.
function renderSpendChart(data) {
  const { dates, byPlatform } = data.series;
  const platforms = Object.keys(byPlatform).filter((p) => byPlatform[p].some((v) => v > 0));
  const el = $("spend-chart");
  legend($("spend-legend"), platforms.length > 1 ? platforms : []);
  const W = 520, H = 240, m = { t: 12, r: 84, b: 28, l: 48 };
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  if (dates.length === 0 || platforms.length === 0) {
    el.innerHTML = `<svg viewBox="0 0 ${W} 120"><text class="empty-note" x="0" y="60">Nothing spent yet.</text></svg>`;
    return;
  }
  const max = niceMax(Math.max(...platforms.flatMap((p) => byPlatform[p])));
  const x = (i) => m.l + (dates.length === 1 ? iw / 2 : (i / (dates.length - 1)) * iw);
  const y = (v) => m.t + ih - (v / max) * ih;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * max);

  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Lifetime spend per platform over time">`;
  for (const t of ticks) {
    svg += `<line class="${t === 0 ? "baseline" : "grid"}" x1="${m.l}" x2="${m.l + iw}" y1="${y(t)}" y2="${y(t)}"/>`;
    svg += `<text x="${m.l - 8}" y="${y(t) + 4}" text-anchor="end">${fmt.money(t).replace(".00", "")}</text>`;
  }
  const labelEvery = Math.ceil(dates.length / 6);
  dates.forEach((d, i) => {
    if (i % labelEvery === 0 || i === dates.length - 1) {
      svg += `<text x="${x(i)}" y="${H - 8}" text-anchor="middle">${fmt.date(d)}</text>`;
    }
  });
  // Direct end labels, nudged apart so they never overlap.
  const ends = platforms
    .map((p) => ({ p, v: byPlatform[p].at(-1), y: y(byPlatform[p].at(-1)) }))
    .sort((a, b) => a.y - b.y);
  for (let i = 1; i < ends.length; i++) ends[i].y = Math.max(ends[i].y, ends[i - 1].y + 15);
  for (const p of platforms) {
    const pts = byPlatform[p].map((v, i) => `${x(i)},${y(v)}`).join(" ");
    svg += `<polyline points="${pts}" fill="none" stroke="${platformColor(p)}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
    svg += `<circle cx="${x(dates.length - 1)}" cy="${y(byPlatform[p].at(-1))}" r="4" fill="${platformColor(p)}" stroke="var(--surface)" stroke-width="2"/>`;
  }
  for (const e of ends) {
    svg += `<text class="label-strong" x="${m.l + iw + 10}" y="${e.y + 4}">${escapeHtml(platformLabel(e.p))} ${fmt.money(e.v)}</text>`;
  }
  svg += `<line class="crosshair" id="spend-cross" y1="${m.t}" y2="${m.t + ih}" visibility="hidden"/>`;
  svg += `<rect id="spend-hit" x="${m.l}" y="${m.t}" width="${iw}" height="${ih}" fill="transparent"/>`;
  svg += "</svg>";
  el.innerHTML = svg;

  const svgEl = el.querySelector("svg");
  const cross = el.querySelector("#spend-cross");
  el.querySelector("#spend-hit").addEventListener("mousemove", (ev) => {
    const pt = svgEl.createSVGPoint();
    pt.x = ev.clientX; pt.y = ev.clientY;
    const local = pt.matrixTransform(svgEl.getScreenCTM().inverse());
    const i = Math.max(0, Math.min(dates.length - 1, Math.round(((local.x - m.l) / iw) * (dates.length - 1))));
    cross.setAttribute("x1", x(i)); cross.setAttribute("x2", x(i));
    cross.setAttribute("visibility", "visible");
    const rows = platforms
      .map((p) => [p, byPlatform[p][i]])
      .sort((a, b) => b[1] - a[1])
      .map(([p, v]) => row(platformLabel(p), fmt.money(v), platformColor(p)))
      .join("");
    showTooltip(ev, fmt.date(dates[i]), rows);
  });
  el.querySelector("#spend-hit").addEventListener("mouseleave", () => {
    cross.setAttribute("visibility", "hidden");
    hideTooltip();
  });
}

// Cost per install per ad. Horizontal bars, colored by platform, hover tooltip.
function renderCpiChart(ads) {
  const el = $("cpi-chart");
  const rows = ads.filter((a) => a.derived.cpi != null).sort((a, b) => a.derived.cpi - b.derived.cpi);
  const platforms = [...new Set(rows.map((a) => a.platform))];
  legend($("cpi-legend"), platforms.length > 1 ? platforms : []);
  if (rows.length === 0) {
    el.innerHTML = `<svg viewBox="0 0 520 120"><text class="empty-note" x="0" y="60">No installs counted yet. Installs show up once an ad's link carries a campaign token.</text></svg>`;
    return;
  }
  const W = 520, barH = 18, gap = 22, m = { t: 4, r: 64, l: 190 };
  const H = m.t + rows.length * (barH + gap);
  const iw = W - m.l - m.r;
  const max = Math.max(...rows.map((a) => a.derived.cpi));
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Cost per install by ad">`;
  svg += `<line class="baseline" x1="${m.l}" x2="${m.l}" y1="0" y2="${H - gap + 6}"/>`;
  rows.forEach((a, i) => {
    const y = m.t + i * (barH + gap);
    const w = Math.max(2, (a.derived.cpi / max) * iw);
    const r = Math.min(4, w / 2);
    // Square at the baseline, 4px round at the data end.
    const path = `M${m.l},${y} h${w - r} a${r},${r} 0 0 1 ${r},${r} v${barH - 2 * r} a${r},${r} 0 0 1 -${r},${r} h-${w - r} z`;
    const name = a.name.length > 26 ? `${a.name.slice(0, 25)}…` : a.name;
    svg += `<text x="${m.l - 10}" y="${y + barH / 2 + 4}" text-anchor="end" class="label-strong">${escapeHtml(name)}</text>`;
    svg += `<path d="${path}" fill="${platformColor(a.platform)}"/>`;
    svg += `<text x="${m.l + w + 8}" y="${y + barH / 2 + 4}">${fmt.money(a.derived.cpi)}</text>`;
    svg += `<rect class="bar-hit" data-i="${i}" x="0" y="${y - gap / 2}" width="${W}" height="${barH + gap}" fill="transparent"/>`;
  });
  svg += "</svg>";
  el.innerHTML = svg;
  el.querySelectorAll(".bar-hit").forEach((hit) => {
    const a = rows[Number(hit.dataset.i)];
    hit.addEventListener("mousemove", (ev) =>
      showTooltip(ev, a.name,
        row(platformLabel(a.platform), "", platformColor(a.platform)) +
        row("Spent", fmt.money(a.metrics.spend)) +
        row("Installs", fmt.int(a.metrics.installs)) +
        row("Per install", fmt.money(a.derived.cpi))));
    hit.addEventListener("mouseleave", hideTooltip);
  });
}

function verdictChip(v) {
  if (!v) return '<span class="muted">—</span>';
  const key = VERDICT_LABELS[v.verdict] ? v.verdict : "wait";
  return `<span class="verdict ${key}"><svg viewBox="0 0 16 16" aria-hidden="true">${VERDICT_ICONS[key]}</svg>${VERDICT_LABELS[key]}</span>`;
}

function renderTable(ads) {
  const order = { live: 0, draft: 1, paused: 2, ended: 3 };
  const sorted = [...ads].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));
  $("ads-table").querySelector("tbody").innerHTML = sorted.map((a) => {
    const m = a.metrics, d = a.derived;
    const budget = a.budget?.amount ? ` <span class="muted">/ ${fmt.money(a.budget.amount)}</span>` : "";
    return `<tr>
      <td>
        <div class="ad-name">${escapeHtml(a.name)}</div>
        <div class="ad-sub"><i class="swatch" style="background:${platformColor(a.platform)}"></i>${escapeHtml(platformLabel(a.platform))} · ${escapeHtml(a.status || "")} · ${fmt.date(a.start)}–${fmt.date(a.end)}</div>
        ${a.verdict?.why ? `<div class="why">${escapeHtml(a.verdict.why)}</div>` : ""}
      </td>
      <td>${verdictChip(a.verdict)}</td>
      <td class="num">${fmt.money(m.spend)}${budget}</td>
      <td class="num">${fmt.int(m.clicks)}</td>
      <td class="num">${fmt.pct(d.ctr)}</td>
      <td class="num">${fmt.money(d.cpc)}</td>
      <td class="num">${fmt.int(m.installs)}</td>
      <td class="num">${fmt.money(d.cpi)}</td>
      <td class="num">${fmt.pct(d.hookRate)}</td>
      <td class="num">${fmt.pct(d.holdRate)}</td>
    </tr>`;
  }).join("");
}

function renderCreativeNotes(ads) {
  const withNotes = ads.filter((a) => a.creativeNotes);
  $("creative-section").hidden = withNotes.length === 0;
  $("creative-notes").innerHTML = withNotes.map((a) => {
    const n = a.creativeNotes;
    return `<article class="card">
      <h3>${escapeHtml(a.name)}</h3>
      ${n.strip ? `<img class="strip" src="../data/${escapeHtml(n.strip)}" alt="Frames from ${escapeHtml(a.name)}">` : ""}
      ${n.dropOff ? `<p class="sub">${escapeHtml(n.dropOff)}</p>` : ""}
      <ul>${(n.notes || []).map((t) => `<li>${escapeHtml(t)}</li>`).join("")}</ul>
    </article>`;
  }).join("");
}

function renderExperiments(experiments) {
  $("experiments-section").hidden = experiments.length === 0;
  $("experiments").innerHTML = experiments.map((e, i) => `
    <article class="card">
      <h3>${escapeHtml(e.title)}</h3>
      <p class="sub">${escapeHtml(e.hypothesis)}</p>
      <div class="prompt" id="prompt-${i}">${escapeHtml(e.designPrompt)}</div>
      <button class="copy" data-i="${i}">Copy prompt for Claude Design</button>
    </article>`).join("");
  $("experiments").querySelectorAll("button.copy").forEach((btn) => {
    btn.addEventListener("click", async () => {
      await navigator.clipboard.writeText(experiments[Number(btn.dataset.i)].designPrompt);
      btn.textContent = "Copied";
      setTimeout(() => (btn.textContent = "Copy prompt for Claude Design"), 1500);
    });
  });
}

function row(label, value, color) {
  const dot = color ? `<i class="swatch" style="background:${color}"></i>` : "";
  return `<div class="t-row"><span>${dot}${escapeHtml(label)}</span><span>${escapeHtml(value)}</span></div>`;
}

function showTooltip(ev, title, rowsHtml) {
  const tip = $("tooltip");
  tip.innerHTML = `<div class="t-title">${escapeHtml(title)}</div>${rowsHtml}`;
  tip.hidden = false;
  const pad = 14, { innerWidth: vw } = window;
  const w = tip.offsetWidth;
  tip.style.left = `${ev.clientX + pad + w > vw ? ev.clientX - w - pad : ev.clientX + pad}px`;
  tip.style.top = `${ev.clientY + pad}px`;
}

function hideTooltip() {
  $("tooltip").hidden = true;
}

load();
