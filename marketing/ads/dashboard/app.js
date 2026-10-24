// Renders dashboard.json (built by ../build.py) on the Results and Links pages.
// No dependencies, no build step.

const PLATFORM_LABELS = { tiktok: "TikTok", reddit: "Reddit", instagram: "Instagram" };
const VERDICTS = {
  keep: { label: "Keep it", icon: '<path d="M3 8.5l3 3 7-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>' },
  iterate: { label: "Change it", icon: '<path d="M13 8a5 5 0 1 1-1.5-3.5M13 2.5V5h-2.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>' },
  kill: { label: "Stop it", icon: '<path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' },
  blocked: { label: "Rejected", icon: '<circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M4.2 11.8l7.6-7.6" stroke="currentColor" stroke-width="2"/>' },
  wait: { label: "Too early", icon: '<circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 5v3.2l2 1.3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' },
};

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const platformLabel = (p) => PLATFORM_LABELS[p] || p;
const platformColor = (p) => css(`--${p}`) || css("--other");
const escapeHtml = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const fmt = {
  money: (v) => (v == null ? "—" : `$${v.toLocaleString(undefined, { minimumFractionDigits: v < 100 ? 2 : 0, maximumFractionDigits: 2 })}`),
  int: (v) => (v == null ? "—" : Math.round(v).toLocaleString()),
  pct: (v) => (v == null ? "—" : `${(v * 100).toFixed(v < 0.01 ? 2 : v < 0.1 ? 1 : 0)}%`),
  date: (iso) => (iso ? new Date(`${iso.slice(0, 10)}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" }) : "—"),
};

// How each compared metric reads, in words a non-marketer would use.
const METRICS = {
  cpc: { label: "Cost per tap", hint: "What each tap on the link cost", fmt: fmt.money, better: "Cheaper than most", worse: "Pricier than most" },
  ctr: { label: "Tap rate", hint: "Of everyone who saw it, how many tapped the link", fmt: fmt.pct, better: "Better than most", worse: "Below most" },
  hookRate: { label: "Watched past the opening", hint: "Still watching after the first seconds", fmt: fmt.pct, better: "Better than most", worse: "Below most" },
  installRate: { label: "Installed after tapping", hint: "Of the people who tapped, how many installed", fmt: fmt.pct, better: "Better than most", worse: "Below most" },
  cpi: { label: "Cost per install", hint: "What each new player cost", fmt: fmt.money, better: "Cheaper than most", worse: "Pricier than most" },
};

async function load() {
  const params = new URLSearchParams(location.search);
  const example = params.has("example");
  if (params.get("theme") === "light") document.documentElement.dataset.theme = "light";
  $("source-badge").hidden = !example;
  document.querySelectorAll("a[data-keep-query]").forEach((a) => (a.href += location.search));
  document.querySelectorAll("button[data-copy]").forEach((b) => wireCopy(b, () => b.dataset.copy));

  let data;
  try {
    const res = await fetch(example ? "../example-data/dashboard.json" : "../data/dashboard.json", { cache: "no-store" });
    if (!res.ok) throw new Error(res.status);
    data = await res.json();
  } catch {
    $("empty").hidden = false;
    return;
  }
  $("content").hidden = false;
  if (document.body.dataset.page === "links") renderLinksPage(data);
  else renderResults(data);
}

// ---------- Results ----------

function renderResults(data) {
  const report = data.report || {};
  $("meta").textContent = data.lastPull ? `Last checked ${fmt.date(data.lastPull)}` : "Not checked yet";
  $("headline").textContent = report.headline || "";
  if (report.nextAction) {
    $("next-title").textContent = report.nextAction.title;
    $("next-why").textContent = report.nextAction.why || "";
  } else {
    $("next").hidden = true;
  }
  renderGaps(report.gaps || []);
  renderTiles(data.totals);
  $("bench-note").textContent = data.benchmarksChecked
    ? "Each number sits against what's normal for game ads on that platform: the grey band is the normal range, the line is typical, the dot is you. The ranges come from 2025 and 2026 industry reports, so treat them as rough."
    : "";
  renderAdCards(data.ads);
  renderSpendChart(data);
  renderCreativeNotes(data.ads);
  renderExperiments(report.experiments || []);
  renderTable(data.ads);
}

// Each gap is { what, fix }: something the totals can't see, and how to close it.
function renderGaps(gaps) {
  $("gaps").hidden = gaps.length === 0;
  $("gaps-list").innerHTML = gaps
    .map((g) => `<li><strong>${escapeHtml(g.what)}</strong>${g.fix ? ` ${escapeHtml(g.fix)}` : ""}</li>`)
    .join("");
}

function renderTiles(t) {
  const partial = (t.spendUnknown || []).length > 0;
  const noInstalls = !t.installs && (t.untracked || []).length > 0;
  $("tiles").innerHTML = `
    <div class="tile">
      <span class="label">Spent</span>
      <span class="value">${fmt.money(t.spend)}${partial ? "+" : ""}</span>
      <span class="detail">${partial ? `plus ${t.spendUnknown.length} ad we can't see the cost of` : t.budget != null ? `of ${fmt.money(t.budget)} budgeted` : "across every platform"}</span>
    </div>
    <div class="tile">
      <span class="label">Taps on the link</span>
      <span class="value">${fmt.int(t.clicks)}</span>
      <span class="detail">${t.clicks && t.spend && !partial ? `${fmt.money(t.spend / t.clicks)} each on average` : "&nbsp;"}</span>
    </div>
    <div class="tile">
      <span class="label">Installs</span>
      <span class="value">${noInstalls ? "—" : fmt.int(t.installs)}</span>
      <span class="detail">${noInstalls ? "not counted yet: ads need the links on the Links page" : "counted by the App Store and Google Play"}</span>
    </div>
    <div class="tile">
      <span class="label">Cost per install</span>
      <span class="value">${fmt.money(t.cpi)}</span>
      <span class="detail">${t.cpi == null ? "shows up once installs are counted" : "across every platform"}</span>
    </div>`;
}

function verdictChip(v) {
  if (!v) return "";
  const key = VERDICTS[v.verdict] ? v.verdict : "wait";
  return `<span class="verdict ${key}"><svg viewBox="0 0 16 16" aria-hidden="true">${VERDICTS[key].icon}</svg>${VERDICTS[key].label}</span>`;
}

function renderAdCards(ads) {
  const order = { live: 0, "in review": 1, rejected: 2, draft: 3, paused: 4, ended: 5 };
  const sorted = [...ads].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));
  $("ad-cards").innerHTML = sorted.map((a) => {
    const dates = a.start || a.end ? `${fmt.date(a.start)} to ${fmt.date(a.end)}` : "";
    const sub = [platformLabel(a.platform), a.status, dates, a.metrics.spend != null ? `${fmt.money(a.metrics.spend)} spent` : null]
      .filter(Boolean).map(escapeHtml).join(" · ");
    const rows = (a.comparisons || []).map((c) => comparisonRow(c, a.platform)).join("");
    return `<article class="card ad-card">
      <header>
        <div>
          <h3><i class="swatch" style="background:${platformColor(a.platform)}"></i>${escapeHtml(a.name)}</h3>
          <p class="sub">${sub}</p>
        </div>
        ${verdictChip(a.verdict)}
      </header>
      ${a.verdict?.why ? `<p class="why">${escapeHtml(a.verdict.why)}</p>` : ""}
      ${rows || a.funnel ? `<div class="ad-body">${rows ? `<div class="compare">${rows}</div>` : "<div></div>"}${funnelBlock(a)}</div>` : ""}
    </article>`;
  }).join("");
  wireTooltips($("ad-cards"));
}

function comparisonRow(c, platform) {
  const meta = METRICS[c.metric];
  const b = c.benchmark;
  const words = c.rating === "better" ? meta.better : c.rating === "worse" ? meta.worse : "About normal";
  const rating = c.rating || "typical";
  const W = 300, H = 26, pad = 8;
  const max = Math.max(b.high * 1.5, c.value * 1.15);
  const x = (v) => pad + (Math.min(v, max) / max) * (W - pad * 2);
  const source = b.sources?.[0];
  const hint = c.metric === "hookRate" && platform === "instagram" ? "Still watching after 3 seconds" : c.metric === "hookRate" ? "Still watching after 2 seconds" : meta.hint;
  return `<div class="compare-row">
    <div class="compare-head">
      <span class="compare-label" title="${escapeHtml(hint)}">${meta.label}</span>
      <span class="compare-value">${meta.fmt(c.value)}</span>
      <span class="rating ${rating}">${escapeHtml(words)}</span>
    </div>
    <svg class="bullet" viewBox="0 0 ${W} ${H}" role="img" aria-label="${escapeHtml(`${meta.label} ${meta.fmt(c.value)}. Normal range ${meta.fmt(b.low)} to ${meta.fmt(b.high)}.`)}">
      <line class="track" x1="${pad}" x2="${W - pad}" y1="${H / 2}" y2="${H / 2}"/>
      <rect class="band" x="${x(b.low)}" y="${H / 2 - 6}" width="${Math.max(2, x(b.high) - x(b.low))}" height="12" rx="4"/>
      ${b.typical != null ? `<line class="typical" x1="${x(b.typical)}" x2="${x(b.typical)}" y1="${H / 2 - 9}" y2="${H / 2 + 9}"/>` : ""}
      <circle cx="${x(c.value)}" cy="${H / 2}" r="6" fill="${platformColor(platform)}" stroke="var(--surface)" stroke-width="2"/>
    </svg>
    <p class="compare-foot">Normal for ${escapeHtml(platformLabel(platform))}: ${meta.fmt(b.low)} to ${meta.fmt(b.high)}${b.typical != null ? `, typical ${meta.fmt(b.typical)}` : ""}.
      ${source ? `<a href="${escapeHtml(source.url)}" target="_blank" rel="noopener" title="${escapeHtml(b.note || "")}">Source</a>` : ""}</p>
  </div>`;
}

function funnelBlock(a) {
  const steps = a.funnel;
  if (!steps) return "";
  const top = steps[0].count;
  const hooked = steps[1].count;
  const seconds = a.derived.hookSeconds || 3;
  const callout = hooked != null
    ? `<p class="callout"><strong>${fmt.pct(1 - hooked / top)}</strong> scrolled past in the first ${seconds} seconds.</p>`
    : "";
  const rows = steps.map((s, i) => {
    if (s.count == null) {
      const why = i === steps.length - 1 ? "Not counted yet" : `${platformLabel(a.platform)} doesn't report this`;
      return `<div class="funnel-row missing"><span class="f-label">${escapeHtml(s.label)}</span><span class="f-bar"><span class="f-empty">${escapeHtml(why)}</span></span><span class="f-num">—</span></div>`;
    }
    const share = s.count / top;
    return `<div class="funnel-row"><span class="f-label">${escapeHtml(s.label)}</span>
      <span class="f-bar"><span class="f-fill" style="width:${Math.max(share * 100, 0.6)}%;background:${platformColor(a.platform)}"></span></span>
      <span class="f-num">${fmt.int(s.count)} <span class="muted">${fmt.pct(share)}</span></span></div>`;
  }).join("");
  return `<div class="funnel"><p class="funnel-title">Where people drop off</p>${callout}${rows}</div>`;
}

function niceMax(v) {
  if (!v) return 1;
  const pow = 10 ** Math.floor(Math.log10(v));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * pow >= v / 4) * pow;
  return Math.ceil(v / step) * step;
}

// Only worth drawing once there are two or more check-ins to connect.
function renderSpendChart(data) {
  const { dates, byPlatform } = data.series;
  const platforms = Object.keys(byPlatform).filter((p) => byPlatform[p].some((v) => v > 0));
  $("trend-section").hidden = dates.length < 2 || platforms.length === 0;
  if ($("trend-section").hidden) return;
  const el = $("spend-chart");
  legend($("spend-legend"), platforms.length > 1 ? platforms : []);
  const W = 720, H = 240, m = { t: 12, r: 120, b: 28, l: 52 };
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  const max = niceMax(Math.max(...platforms.flatMap((p) => byPlatform[p])));
  const x = (i) => m.l + (i / (dates.length - 1)) * iw;
  const y = (v) => m.t + ih - (v / max) * ih;
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Money spent per platform over time">`;
  for (const f of [0, 0.25, 0.5, 0.75, 1]) {
    const t = f * max;
    svg += `<line class="${t === 0 ? "baseline" : "grid"}" x1="${m.l}" x2="${m.l + iw}" y1="${y(t)}" y2="${y(t)}"/>`;
    svg += `<text x="${m.l - 8}" y="${y(t) + 4}" text-anchor="end">${fmt.money(t).replace(".00", "")}</text>`;
  }
  const every = Math.ceil(dates.length / 6);
  dates.forEach((d, i) => {
    if (i % every === 0 || i === dates.length - 1) svg += `<text x="${x(i)}" y="${H - 8}" text-anchor="middle">${fmt.date(d)}</text>`;
  });
  const ends = platforms.map((p) => ({ p, v: byPlatform[p].at(-1), y: y(byPlatform[p].at(-1)) })).sort((a, b) => a.y - b.y);
  for (let i = 1; i < ends.length; i++) ends[i].y = Math.max(ends[i].y, ends[i - 1].y + 15);
  for (const p of platforms) {
    svg += `<polyline points="${byPlatform[p].map((v, i) => `${x(i)},${y(v)}`).join(" ")}" fill="none" stroke="${platformColor(p)}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
    svg += `<circle cx="${x(dates.length - 1)}" cy="${y(byPlatform[p].at(-1))}" r="4" fill="${platformColor(p)}" stroke="var(--surface)" stroke-width="2"/>`;
  }
  for (const e of ends) svg += `<text class="label-strong" x="${m.l + iw + 10}" y="${e.y + 4}">${escapeHtml(platformLabel(e.p))} ${fmt.money(e.v)}</text>`;
  svg += `<line class="crosshair" id="spend-cross" y1="${m.t}" y2="${m.t + ih}" visibility="hidden"/><rect id="spend-hit" x="${m.l}" y="${m.t}" width="${iw}" height="${ih}" fill="transparent"/></svg>`;
  el.innerHTML = svg;
  const svgEl = el.querySelector("svg");
  const cross = el.querySelector("#spend-cross");
  const hit = el.querySelector("#spend-hit");
  hit.addEventListener("mousemove", (ev) => {
    const pt = svgEl.createSVGPoint();
    pt.x = ev.clientX; pt.y = ev.clientY;
    const local = pt.matrixTransform(svgEl.getScreenCTM().inverse());
    const i = Math.max(0, Math.min(dates.length - 1, Math.round(((local.x - m.l) / iw) * (dates.length - 1))));
    cross.setAttribute("x1", x(i)); cross.setAttribute("x2", x(i)); cross.setAttribute("visibility", "visible");
    showTooltip(ev, fmt.date(dates[i]), platforms.map((p) => [p, byPlatform[p][i]]).sort((a, b) => b[1] - a[1])
      .map(([p, v]) => tipRow(platformLabel(p), fmt.money(v), platformColor(p))).join(""));
  });
  hit.addEventListener("mouseleave", () => { cross.setAttribute("visibility", "hidden"); hideTooltip(); });
}

function legend(el, platforms) {
  el.innerHTML = platforms.map((p) => `<span><i class="swatch" style="background:${platformColor(p)}"></i>${escapeHtml(platformLabel(p))}</span>`).join("");
}

function renderCreativeNotes(ads) {
  const withNotes = ads.filter((a) => a.creativeNotes);
  $("creative-section").hidden = withNotes.length === 0;
  $("creative-notes").innerHTML = withNotes.map((a) => {
    const n = a.creativeNotes;
    return `<article class="card">
      <h3>${escapeHtml(n.title || a.name)}</h3>
      ${n.strip ? `<img class="strip" src="../data/${escapeHtml(n.strip)}" alt="One frame per second from ${escapeHtml(n.title || a.name)}">` : ""}
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
      <div class="prompt">${escapeHtml(e.designPrompt)}</div>
      <button class="copy" data-i="${i}">Copy prompt</button>
    </article>`).join("");
  $("experiments").querySelectorAll("button.copy").forEach((b) => wireCopy(b, () => experiments[Number(b.dataset.i)].designPrompt));
}

function renderTable(ads) {
  $("ads-table").querySelector("tbody").innerHTML = ads.map((a) => {
    const m = a.metrics, d = a.derived;
    return `<tr>
      <td><div class="ad-name">${escapeHtml(a.name)}</div><div class="ad-sub">${escapeHtml(platformLabel(a.platform))} · ${escapeHtml(a.status || "")}</div></td>
      <td class="num">${fmt.money(m.spend)}</td>
      <td class="num">${fmt.int(m.impressions)}</td>
      <td class="num">${fmt.int(m.clicks)}</td>
      <td class="num">${fmt.pct(d.ctr)}</td>
      <td class="num">${fmt.money(d.cpc)}</td>
      <td class="num">${fmt.pct(d.hookRate)}${d.hookRate != null ? ` <span class="muted">${d.hookSeconds}s</span>` : ""}</td>
      <td class="num">${fmt.int(m.installs)}</td>
      <td class="num">${fmt.money(d.cpi)}</td>
    </tr>`;
  }).join("");
}

// ---------- Links ----------

function renderLinksPage(data) {
  $("ad-links").innerHTML = data.ads.filter((a) => a.trackedLink).map((a) => {
    const live = Boolean(a.campaignToken);
    const status = a.status === "rejected"
      ? '<span class="pill warn">Ad is rejected. Use this link when you resubmit.</span>'
      : a.status === "ended"
        ? '<span class="pill">Ad has ended. Use this link next time.</span>'
        : live ? '<span class="pill ok">In the ad</span>' : '<span class="pill warn">Not in the ad yet</span>';
    return linkRow(`${a.name}`, status, a.trackedLink);
  }).join("") || '<p class="sub">No ads in the ledger yet.</p>';
  $("placement-links").innerHTML = (data.placements || []).map((p) =>
    linkRow(p.where, p.placed ? '<span class="pill ok">In place</span>' : '<span class="pill warn">Not added yet</span>', p.link)
  ).join("") || '<p class="sub">None yet.</p>';
  document.querySelectorAll(".link-list button.copy[data-link]").forEach((b) => wireCopy(b, () => b.dataset.link));
  wireBuilder();
}

function linkRow(where, status, link) {
  return `<div class="link-row">
    <div class="link-where"><strong>${escapeHtml(where)}</strong>${status}</div>
    <code class="link">${escapeHtml(link)}</code>
    <button class="copy" data-link="${escapeHtml(link)}">Copy</button>
  </div>`;
}

function wireBuilder() {
  const name = $("build-name"), paid = $("build-paid"), friends = $("build-friends"), out = $("build-link"), copy = $("build-copy");
  const update = () => {
    const value = name.value.trim().toLowerCase();
    const valid = /^[a-z0-9][a-z0-9_-]{0,29}$/.test(value);
    copy.disabled = !valid;
    if (!value) { out.textContent = "Type a name to make a link"; return; }
    if (!valid) { out.textContent = "Use letters, numbers and dashes, starting with a letter or number"; return; }
    out.textContent = `https://downcard.app/get/?c=${value}${paid.checked ? "" : "&m=social"}${friends.checked ? "&p=friends" : ""}`;
  };
  [name, paid, friends].forEach((el) => el.addEventListener("input", update));
  wireCopy(copy, () => out.textContent);
}

// ---------- Shared ----------

function wireCopy(button, getText) {
  const original = button.textContent;
  button.addEventListener("click", async () => {
    await navigator.clipboard.writeText(getText());
    button.textContent = "Copied";
    setTimeout(() => (button.textContent = original), 1500);
  });
}

function wireTooltips(root) {
  root.querySelectorAll(".compare-label[title]").forEach((el) => {
    const text = el.getAttribute("title");
    el.removeAttribute("title");
    el.addEventListener("mousemove", (ev) => showTooltip(ev, text, ""));
    el.addEventListener("mouseleave", hideTooltip);
  });
}

function tipRow(label, value, color) {
  const dot = color ? `<i class="swatch" style="background:${color}"></i>` : "";
  return `<div class="t-row"><span>${dot}${escapeHtml(label)}</span><span>${escapeHtml(value)}</span></div>`;
}

function showTooltip(ev, title, rowsHtml) {
  const tip = $("tooltip");
  if (!tip) return;
  tip.innerHTML = `<div class="t-title">${escapeHtml(title)}</div>${rowsHtml}`;
  tip.hidden = false;
  const pad = 14, w = tip.offsetWidth;
  tip.style.left = `${ev.clientX + pad + w > window.innerWidth ? ev.clientX - w - pad : ev.clientX + pad}px`;
  tip.style.top = `${ev.clientY + pad}px`;
}

function hideTooltip() {
  const tip = $("tooltip");
  if (tip) tip.hidden = true;
}

load();
