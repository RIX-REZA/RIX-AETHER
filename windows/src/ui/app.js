/* RIX — the interface.
 *
 * The page owns the look; C# owns the tunnel. Everything here is either drawn
 * from the last snapshot the host sent, or is a message asking the host to do
 * something. Nothing decides on its own that the tunnel is up: that verdict
 * comes from a real request the host made through it, because a listener that
 * accepts is not the same thing as a tunnel that carries.
 *
 * The one thing drawn that is not a field of the snapshot is the throughput
 * chart, and it is built from the difference between two of the host's own
 * readings of the core's counters -- never from a number invented here.
 */

const host = window.chrome && window.chrome.webview ? window.chrome.webview : null;

function send(message) {
  if (host) host.postMessage(message);
}

/* ------------------------------------------------------------------ strings */

const T = {
  fa: {
    connect: 'اتصال', disconnect: 'قطع', cancel: 'لغو', retry: 'تلاش دوباره',
    ready: 'آماده', idle: 'آماده',
    down: 'دانلود', up: 'آپلود', time: 'زمان اتصال', chart: 'گذر لحظهای',
    exit: 'آیپی خروجی', you: 'اتصال شما', proto: 'پروتکل', route: 'مسیر خروج',
    check: 'بررسی', copy: 'کپی', copied: 'کپی شد',
    home: 'خانه', settings: 'تنظیمات', log: 'گزارش', about: 'درباره',
    saved: 'ذخیره شد', clear: 'پاک کردن', folder: 'پوشه',
    none: '—', unknown: 'نامشخص',
    running: 'متصل', connecting: 'در حال اتصال', starting: 'آمادهسازی',
    stopping: 'در حال قطع', error: 'خطا',
    proxyOn: 'پروکسی سیستم روشن', proxyOff: 'پروکسی سیستم خاموش',
    socks: 'پورت', beta: 'بتا',
    lang: 'زبان', theme: 'پوسته', dark: 'تیره', light: 'روشن',
    minTray: 'بستن به سینی سیستم',
    autostart: 'اجرا با ویندوز',
    openFolder: 'پوشه تنظیمات', reset: 'بازگردانی پیشفرض',
    resetAsk: 'همه تنظیمات به حالت پیشفرض برگردد؟',
    on: 'روشن', off: 'خاموش', auto: 'خودکار',
    core: 'هسته', listener: 'شنونده SOCKS5', tunnel: 'تونل',
    exitStage: 'خروج', internet: 'اینترنت',
    steps: 'مراحل', waiting: 'در انتظار',
    rate: 'نرخ', noAnswer: 'پاسخی نیامد / no answer',
    extra: 'اضافه', value: 'مقدار',
  },
  en: {
    connect: 'Connect', disconnect: 'Disconnect', cancel: 'Cancel', retry: 'Try again',
    ready: 'Ready', idle: 'Ready',
    down: 'Download', up: 'Upload', time: 'Connected for', chart: 'Live throughput',
    exit: 'Exit address', you: 'Your connection', proto: 'Transport', route: 'Exit route',
    check: 'Check', copy: 'Copy', copied: 'Copied',
    home: 'Home', settings: 'Settings', log: 'Log', about: 'About',
    saved: 'Saved', clear: 'Clear', folder: 'Folder',
    none: '—', unknown: 'unknown',
    running: 'Connected', connecting: 'Connecting', starting: 'Preparing',
    stopping: 'Disconnecting', error: 'Error',
    proxyOn: 'System proxy on', proxyOff: 'System proxy off',
    socks: 'Port', beta: 'BETA',
    lang: 'Language', theme: 'Theme', dark: 'Dark', light: 'Light',
    minTray: 'Close to the tray',
    autostart: 'Start with Windows',
    openFolder: 'Settings folder', reset: 'Restore defaults',
    resetAsk: 'Put every setting back to its default?',
    on: 'On', off: 'Off', auto: 'Automatic',
    core: 'Core', listener: 'SOCKS5 listener', tunnel: 'Tunnel',
    exitStage: 'Exit', internet: 'Internet',
    steps: 'Stages', waiting: 'waiting',
    rate: 'Rate', noAnswer: 'no answer',
    extra: 'Extra', value: 'Value',
  }
};

let lang = 'fa';
let theme = 'dark';
const t = (key) => (T[lang] && T[lang][key]) || key;
const L = (pair) => (pair && (pair[lang] || pair.en)) || '';

/* ------------------------------------------------------------------- values */

const TRANSPORTS = [
  { v: 'MASQUE_H3', fa: 'MASQUE / HTTP-3', en: 'MASQUE / HTTP-3' },
  { v: 'MASQUE_H2', fa: 'MASQUE / HTTP-2', en: 'MASQUE / HTTP-2' },
  { v: 'GOOL', fa: 'Gool — وایرگارد درون MASQUE', en: 'Gool — WireGuard inside MASQUE' },
  { v: 'GOOL_CLASSIC', fa: 'Gool کلاسیک', en: 'Gool classic' },
  { v: 'MIM', fa: 'MASQUE در MASQUE', en: 'MASQUE in MASQUE' },
  { v: 'WIREGUARD', fa: 'وایرگارد', en: 'WireGuard' },
];

const SCANS = [
  { v: 'DEFAULT', fa: 'پیشفرض هسته', en: 'Core default' },
  { v: 'TURBO', fa: 'Turbo — سریعترین', en: 'Turbo — fastest' },
  { v: 'BALANCED', fa: 'Balanced', en: 'Balanced' },
  { v: 'THOROUGH', fa: 'Thorough — کامل', en: 'Thorough' },
  { v: 'VERIFIED', fa: 'Verified', en: 'Verified' },
  { v: 'IRONCLAD', fa: 'Ironclad — تست واقعی', en: 'Ironclad — real test' },
];

const IP_FAMILY = [
  { v: 'V4', fa: 'IPv4', en: 'IPv4' },
  { v: 'V6', fa: 'IPv6', en: 'IPv6' },
  { v: 'BOTH', fa: 'هر دو', en: 'Both' },
];

const LOG_LEVELS = [
  { v: 'ERROR', fa: 'خطا', en: 'Error' },
  { v: 'WARN', fa: 'هشدار', en: 'Warn' },
  { v: 'INFO', fa: 'معمولی', en: 'Info' },
  { v: 'DEBUG', fa: 'اشکالزدایی', en: 'Debug' },
];

const PERF = [
  { v: '', fa: 'خودکار (تشخیص سیستم)', en: 'Automatic (detected)' },
  { v: 'LOW', fa: 'کم', en: 'Low' },
  { v: 'MEDIUM', fa: 'متوسط', en: 'Medium' },
  { v: 'HIGH', fa: 'زیاد', en: 'High' },
];

const REGIONS = [
  { v: '', fa: 'خودکار', en: 'Automatic' },
  { v: 'DE', fa: 'آلمان', en: 'Germany' },
  { v: 'NL', fa: 'هلند', en: 'Netherlands' },
  { v: 'FR', fa: 'فرانسه', en: 'France' },
  { v: 'GB', fa: 'بریتانیا', en: 'United Kingdom' },
  { v: 'SE', fa: 'سوئد', en: 'Sweden' },
  { v: 'CH', fa: 'سوئیس', en: 'Switzerland' },
  { v: 'AT', fa: 'اتریش', en: 'Austria' },
  { v: 'PL', fa: 'لهستان', en: 'Poland' },
  { v: 'RO', fa: 'رومانی', en: 'Romania' },
  { v: 'TR', fa: 'ترکیه', en: 'Türkiye' },
  { v: 'AE', fa: 'امارات', en: 'UAE' },
  { v: 'US', fa: 'آمریکا', en: 'United States' },
  { v: 'CA', fa: 'کانادا', en: 'Canada' },
  { v: 'SG', fa: 'سنگاپور', en: 'Singapore' },
  { v: 'JP', fa: 'ژاپن', en: 'Japan' },
];

const PSIPHON_MODES = [
  { v: 'auto', fa: 'خودکار — انتخاب سایفون', en: 'Automatic — Psiphon decides' },
  { v: 'direct', fa: 'مستقیم — سریعتر', en: 'Direct — faster' },
  { v: 'cdn', fa: 'از پشت CDN — مقاومتر', en: 'Behind a CDN — harder to block' },
];

/* ---------------------------------------------------------------- svg glyphs */

const ICON = {
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.6M12 18.6v2.6M4.5 12H2M22 12h-2.5M6.3 6.3 4.5 4.5M19.5 19.5l-1.8-1.8M6.3 17.7l-1.8 1.8M19.5 4.5l-1.8 1.8"/></svg>',
  list: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h16M4 18h10"/></svg>',
  info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.6v.2"/></svg>',
  bolt: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M13 3 5 14h6l-1 7 8-11h-6z"/></svg>',
  globe: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M3.5 9h17M3.5 15h17M12 3a15 15 0 0 1 0 18 15 15 0 0 1 0-18z"/></svg>',
  app: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="17" height="17" rx="4"/><path d="M3.5 9h17"/></svg>',
  net: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="14" width="19" height="6.5" rx="2"/><path d="M6 17.2h.01M9.5 17.2h.01M12 4v6M8.5 6.5 12 4l3.5 2.5"/></svg>',
};

/* ------------------------------------------------------------------- layout */

const el = (id) => document.getElementById(id);

/** Anything that came from the machine -- a path, a message -- goes in here
 *  first. A folder named `R&D` must not break the page it is printed on. */
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const NAV = [
  { id: 'home', icon: ICON.home },
  { id: 'settings', icon: ICON.gear },
  { id: 'log', icon: ICON.list },
  { id: 'about', icon: ICON.info },
];

function buildTabs() {
  const tabs = el('tabs');
  tabs.innerHTML = NAV.map((item) =>
    `<button data-view="${item.id}">${item.icon}<span>${t(item.id)}</span></button>`
  ).join('');

  tabs.querySelectorAll('button').forEach((button) => {
    button.onclick = () => show(button.dataset.view);
  });
}

let currentView = 'home';

function show(view) {
  currentView = view;
  document.querySelectorAll('.view').forEach((node) => node.classList.remove('on'));
  el('view-' + view).classList.add('on');
  document.querySelectorAll('.tabs button').forEach((button) => {
    button.classList.toggle('on', button.dataset.view === view);
  });
  if (view === 'home') drawChart();
}

/* ----------------------------------------------------------------- settings */

const GROUPS = [
  {
    id: 'connection',
    icon: ICON.bolt,
    title: { fa: 'اتصال', en: 'Connection' },
    sub: { fa: 'پروتکل و روش عبور از فیلتر', en: 'Transport and how it gets through' },
    fields: [
      { k: 'transport', t: 'chips', name: { fa: 'پروتکل', en: 'Transport' }, opts: TRANSPORTS },
      { k: 'autoFallback', t: 'switch', name: { fa: 'تلاش خودکار پروتکل‌های دیگر', en: 'Automatic fallback' } },
      { k: 'scanMode', t: 'chips', name: { fa: 'حالت اسکن', en: 'Scan mode' }, opts: SCANS },
      { k: 'fragment', t: 'switch', name: { fa: 'تکه‌تکه کردن بسته‌ها', en: 'Fragment packets' } },
      { k: 'exitLoc', t: 'text', name: { fa: 'کشور خروجی', en: 'Exit country' }, ph: '!IR,AZ,RU  یا  DE,SE' },
      { k: 'peer', t: 'text', name: { fa: 'پیر ثابت', en: 'Fixed peer' }, ph: 'ip:port' },
    ],
  },
  {
    id: 'network',
    icon: ICON.net,
    title: { fa: 'شبکه', en: 'Network' },
    sub: { fa: 'DNS، پورت‌ها و پروکسی', en: 'DNS, ports and proxying' },
    fields: [
      { k: 'dns', t: 'text', name: { fa: 'DNS', en: 'DNS' }, ph: '1.1.1.1,1.0.0.1' },
      { k: 'ipFamily', t: 'chips', name: { fa: 'نسخه IP', en: 'IP version' }, opts: IP_FAMILY },
      { k: 'bindPort', t: 'number', name: { fa: 'پورت SOCKS5', en: 'SOCKS5 port' } },
      { k: 'httpPort', t: 'number', name: { fa: 'پورت پروکسی سیستم', en: 'System proxy port' } },
      { k: 'upstreamProxy', t: 'text', name: { fa: 'پروکسی بالادست', en: 'Upstream proxy' }, ph: 'socks5://127.0.0.1:1080' },
      { k: 'reconnectSecs', t: 'number', name: { fa: 'فاصله اتصال مجدد', en: 'Reconnect after' } },
      { k: 'quickReconnect', t: 'switch', name: { fa: 'اتصال سریع مجدد', en: 'Quick reconnect' } },
      { k: 'keepalive', t: 'number', name: { fa: 'Keepalive', en: 'Keepalive' } },
      { k: 'routeDirect', t: 'text', name: { fa: 'مستقیم برود', en: 'Send direct' }, ph: '192.168.0.0/16,*.local' },
      { k: 'routeBlock', t: 'text', name: { fa: 'بلاک شود', en: 'Block' }, ph: 'ads.example.com' },
    ],
  },
  {
    id: 'psiphon',
    icon: ICON.globe,
    title: { fa: 'سایفون', en: 'Psiphon' },
    badge: 'BETA',
    sub: { fa: 'خروج از کشور دلخواه', en: 'Leave from the country you pick' },
    fields: [
      { k: 'psiphon', t: 'switch', name: { fa: 'روشن', en: 'Enabled' } },
      {
        k: 'chain', t: 'chain', name: { fa: 'زنجیره', en: 'Chain' },
        text: { fa: 'انتقال → WARP → سایفون → اینترنت', en: 'transport → WARP → Psiphon → internet' },
      },
      { k: 'psiphonRegion', t: 'chips+custom', name: { fa: 'کشور خروجی', en: 'Exit country' }, opts: REGIONS },
      { k: 'psiphonMode', t: 'chips', name: { fa: 'حالت سایفون', en: 'Psiphon mode' }, opts: PSIPHON_MODES },
      {
        k: 'psiphonNote', t: 'note',
        text: {
          fa: 'این نسخه در حالت بتا است. با روشن بودن سایفون، ترافیک از داخل تونل WARP به سایفون می‌رود و از کشور انتخابی خارج می‌شود، پس آی‌پی خروجی دیگر کلاودفلر نیست. اتصال در این حالت چند دقیقه بیشتر طول می‌کشد چون سایفون باید فهرست سرورهایش را بگیرد.',
          en: 'Beta. With Psiphon on, traffic goes through the WARP tunnel and then leaves from the country you chose, so the exit address is no longer Cloudflare\'s. It takes longer to connect, because Psiphon fetches its server list first.',
        },
      },
    ],
  },
  {
    id: 'app',
    icon: ICON.app,
    title: { fa: 'برنامه', en: 'App' },
    sub: { fa: 'پوسته، زبان و رفتار پنجره', en: 'Theme, language and window behaviour' },
    fields: [
      {
        k: 'theme', t: 'chips', name: { fa: 'پوسته', en: 'Theme' },
        opts: [{ v: 'dark', fa: 'تیره', en: 'Dark' }, { v: 'light', fa: 'روشن', en: 'Light' }],
        app: true,
      },
      {
        k: 'lang', t: 'chips', name: { fa: 'زبان', en: 'Language' },
        opts: [{ v: 'fa', fa: 'فارسی', en: 'فارسی' }, { v: 'en', fa: 'English', en: 'English' }],
        app: true,
      },
      { k: 'startWithWindows', t: 'switch-app', name: { fa: 'اجرا با ویندوز', en: 'Start with Windows' } },
      { k: 'minimizeToTray', t: 'switch', name: { fa: 'بستن به سینی سیستم', en: 'Close to the tray' } },
      { k: 'logLevel', t: 'chips', name: { fa: 'سطح گزارش', en: 'Log level' }, opts: LOG_LEVELS },
      { k: 'perf', t: 'chips', name: { fa: 'کارایی', en: 'Performance' }, opts: PERF },
      { k: 'extraArgs', t: 'area', name: { fa: 'آرگومان‌های اضافه', en: 'Extra arguments' }, ph: '--one --two=value' },
      { k: 'actions', t: 'actions', name: { fa: 'دیگر', en: 'Other' } },
    ],
  },
];

let currentGroup = 'connection';

function buildCat() {
  const node = el('cat');
  node.innerHTML = GROUPS.map((group) => `
    <button data-cat="${group.id}" class="${group.id === currentGroup ? 'on' : ''}">
      ${group.icon}<span>${L(group.title)}${group.badge ? ` <span class="pill beta">${group.badge}</span>` : ''}</span>
    </button>
  `).join('');

  node.querySelectorAll('button').forEach((button) => {
    button.onclick = () => {
      currentGroup = button.dataset.cat;
      buildCat();
      renderFields();
    };
  });
}

function renderFields() {
  const group = GROUPS.find((g) => g.id === currentGroup) || GROUPS[0];
  el('catTitle').innerHTML =
    `${L(group.title)}${group.badge ? ` <span class="pill beta">${group.badge}</span>` : ''}`;
  el('catSub').textContent = L(group.sub);

  const body = el('fields');
  body.innerHTML = '';

  group.fields.forEach((field) => {
    if (field.t === 'note') {
      const note = document.createElement('p');
      note.className = 'hint';
      note.textContent = L(field.text);
      body.appendChild(note);
      return;
    }

    if (field.t === 'chain') {
      const row = document.createElement('div');
      row.className = 'field';
      row.innerHTML = `<span class="name">${L(field.name)}</span>
        <span class="ctl"><span class="chain"><b>${esc(transportLabel(options.transport))}</b>
        <span class="dot">→</span> WARP <span class="dot">→</span> Psiphon
        <span class="dot">→</span> ${t('internet')}</span></span>`;
      body.appendChild(row);
      return;
    }

    if (field.t === 'actions') {
      const row = document.createElement('div');
      row.className = 'field';
      row.innerHTML = `<span class="name">${L(field.name)}</span><span class="ctl"></span>`;
      const ctl = row.querySelector('.ctl');

      const folder = document.createElement('button');
      folder.className = 'ghost';
      folder.textContent = t('openFolder');
      folder.onclick = () => send({ type: 'openFolder' });

      const reset = document.createElement('button');
      reset.className = 'ghost';
      reset.textContent = t('reset');
      reset.onclick = () => {
        if (!confirm(t('resetAsk'))) return;
        options = defaults();
        persist();
        buildCat();
        renderFields();
        applyLangTheme();
        toast(t('saved'));
      };

      ctl.append(folder, reset);
      body.appendChild(row);
      return;
    }

    const row = document.createElement('div');
    row.className = 'field' + (field.t === 'area' ? ' stack' : '');
    row.innerHTML = `<span class="name">${L(field.name)}</span><span class="ctl"></span>`;
    const ctl = row.querySelector('.ctl');
    body.appendChild(row);

    const value = options[field.k];

    if (field.t === 'chips') {
      const wrap = document.createElement('div');
      wrap.className = 'chips';
      field.opts.forEach((opt) => {
        const chip = document.createElement('button');
        chip.className = 'chip' + (String(value) === opt.v ? ' on' : '');
        chip.textContent = L(opt);
        chip.onclick = () => {
          set(field.k, opt.v);
          wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('on'));
          chip.classList.add('on');
          if (field.app) applyLangTheme();
        };
        wrap.appendChild(chip);
      });
      ctl.appendChild(wrap);
      return;
    }

    if (field.t === 'chips+custom') {
      const wrap = document.createElement('div');
      wrap.className = 'chips';

      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'short';
      input.maxLength = 2;
      input.placeholder = 'CC';
      input.value = value || '';

      field.opts.forEach((opt) => {
        const chip = document.createElement('button');
        chip.className = 'chip' + ((value || '') === opt.v ? ' on' : '');
        chip.textContent = L(opt);
        chip.onclick = () => {
          set(field.k, opt.v);
          input.value = opt.v;
          wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('on'));
          chip.classList.add('on');
        };
        wrap.appendChild(chip);
      });

      input.oninput = () => {
        const code = input.value.replace(/[^a-zA-Z]/g, '').toUpperCase().slice(0, 2);
        input.value = code;
        set(field.k, code);
        wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('on'));
      };

      ctl.appendChild(input);
      ctl.appendChild(wrap);
      return;
    }

    if (field.t === 'switch' || field.t === 'switch-app') {
      const toggle = document.createElement('button');
      toggle.className = 'switch' + (value ? ' on' : '');
      toggle.onclick = () => {
        const next = !options[field.k];
        set(field.k, next);
        toggle.classList.toggle('on', next);
        if (field.t === 'switch-app') send({ type: 'autostart', on: next });
      };
      ctl.appendChild(toggle);
      return;
    }

    if (field.t === 'number') {
      const input = document.createElement('input');
      input.type = 'number';
      input.className = 'short';
      input.value = value ?? 0;
      input.onchange = () => set(field.k, parseInt(input.value, 10) || 0);
      ctl.appendChild(input);
      return;
    }

    if (field.t === 'area') {
      const area = document.createElement('textarea');
      area.value = value || '';
      area.placeholder = field.ph || '';
      area.spellcheck = false;
      area.oninput = () => set(field.k, area.value);
      ctl.appendChild(area);
      return;
    }

    const input = document.createElement('input');
    input.type = 'text';
    input.value = value || '';
    input.placeholder = field.ph || '';
    input.spellcheck = false;
    input.oninput = () => set(field.k, input.value);
    ctl.appendChild(input);
  });
}

/** The old name kept so the rest of the file reads unchanged. */
function buildGroups() {
  buildCat();
  renderFields();
}

function transportLabel(value) {
  const found = TRANSPORTS.find((x) => x.v === value);
  return found ? L(found) : (value || t('none'));
}

/* -------------------------------------------------------------------- state */

let options = {};
let snap = { phase: 'idle', message: '', up: 0, down: 0, uptime: 0 };
let saveTimer = null;

/** When the check button was last pressed, 0 when no check is in flight. */
let probingAt = 0;

function defaults() {
  return {
    transport: 'MASQUE_H3', autoFallback: true, bindPort: 1819, httpPort: 1820,
    dns: '1.1.1.1,1.0.0.1', ipFamily: 'V4', exitLoc: '', peer: '', masqueSni: '',
    upstreamProxy: '', reconnectSecs: 2, quickReconnect: true, keepalive: 5,
    fragment: false, fragmentSize: '', fragmentDelay: '', scanMode: 'DEFAULT',
    perf: '', logLevel: 'INFO', ech: '', routeDirect: '', routeBlock: '', extraArgs: '',
    psiphon: false, psiphonRegion: '', psiphonMode: 'auto',
    theme: 'dark', lang: 'fa', startWithWindows: false, minimizeToTray: true,
  };
}

function set(key, value) {
  options[key] = value;
  persist();
  renderHome();
}

/** Debounced: typing in a field must not write the settings file per keystroke. */
function persist() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => send({ type: 'save', options }), 250);
}

/** Sent before a connect, so the tunnel uses what is on screen right now. */
function persistNow() {
  clearTimeout(saveTimer);
  send({ type: 'save', options });
}

function applyLangTheme() {
  lang = options.lang === 'en' ? 'en' : 'fa';
  theme = options.theme === 'light' ? 'light' : 'dark';

  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'fa' ? 'rtl' : 'ltr';
  document.documentElement.dataset.theme = theme;
  el('langBtn').textContent = lang === 'fa' ? 'EN' : 'فا';
  el('themeBtn').textContent = theme === 'dark' ? '☀' : '☾';

  buildTabs();
  buildGroups();
  renderStatic();
  renderHome();
  renderAbout();
  show(currentView);
}

/* --------------------------------------------------------------------- home */

function renderStatic() {
  el('kChart').textContent = t('chart');
  el('kDown').textContent = t('down');
  el('kUp').textContent = t('up');
  el('kTime').textContent = t('time');
  el('kYou').textContent = t('you');
  el('kProto').textContent = t('proto');
  el('kRoute').textContent = t('route');
  el('probeBtn').textContent = t('check');
  el('logCopy').textContent = t('copy');
  el('logClear').textContent = t('clear');
  el('logFolder').textContent = t('folder');
  el('logTitle').textContent = t('log');
  el('stCoreName').textContent = t('core');
  el('stListenerName').textContent = t('listener');
  el('stTunnelName').textContent = t('tunnel');
  el('stExitName').textContent = t('exitStage');
}

function bytes(value) {
  if (!value) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let index = 0;
  let size = value;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return (index === 0 ? size : size.toFixed(size < 10 ? 2 : 1)) + ' ' + units[index];
}

function duration(seconds) {
  const total = Math.max(0, Math.floor(seconds || 0));
  const hours = String(Math.floor(total / 3600)).padStart(2, '0');
  const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const secs = String(total % 60).padStart(2, '0');
  return `${hours}:${minutes}:${secs}`;
}

const PHASE_TEXT = {
  idle: 'ready', starting: 'starting', connecting: 'connecting',
  running: 'running', stopping: 'stopping', error: 'error',
};

function renderHome() {
  const phase = snap.phase || 'idle';
  const busy = phase === 'starting' || phase === 'connecting' || phase === 'stopping';
  const running = phase === 'running';
  const failed = phase === 'error';

  const main = el('heroMain');
  main.classList.toggle('busy', busy);
  main.classList.toggle('on', running);
  main.classList.toggle('err', failed);

  const dot = el('phaseDot');
  dot.classList.toggle('busy', busy);
  dot.classList.toggle('on', running);
  dot.classList.toggle('err', failed);

  el('phaseText').textContent = snap.message || t(PHASE_TEXT[phase] || 'ready');
  el('tbState').className = 'tb-state' + (running ? ' ok' : failed ? ' bad' : '');
  el('tbState').textContent = running
    ? (snap.egress && snap.egress.ip ? snap.egress.ip : t('running'))
    : (failed ? (snap.message || t('error')) : t(PHASE_TEXT[phase] || 'ready'));

  const transport = snap.transport || options.transport;
  el('heroTransport').textContent = transport ? transportLabel(transport) : t('none');

  // The big line is the exit address when there is one, because that is the
  // fact the user came for; otherwise it says what is happening instead of
  // pretending to have an address.
  const exit = snap.egress;
  el('heroIp').textContent = exit && exit.ip
    ? exit.ip
    : (busy ? (snap.message || t(PHASE_TEXT[phase])) : failed ? t('error') : t('ready'));

  const parts = [];
  if (exit) {
    if (exit.country) parts.push(exit.country);
    if (exit.colo) parts.push('colo ' + exit.colo);
    if (exit.warp) parts.push('warp ' + exit.warp);
  } else if (running) {
    parts.push(t('unknown'));
  }
  if (snap.psiphon) parts.push('Psiphon' + (snap.psiphonRegion ? ' · ' + snap.psiphonRegion : ''));
  el('heroSub').textContent = parts.join(' · ');

  el('connectBtn').textContent = running ? t('disconnect') : busy ? t('cancel') : failed ? t('retry') : t('connect');
  el('connectBtn').className = 'primary' + (running ? ' on' : busy ? ' busy' : '');

  const fill = el('trackFill');
  fill.classList.toggle('slide', busy);
  if (!busy) fill.style.width = running ? '100%' : failed ? '100%' : '0';

  el('vDown').textContent = bytes(snap.down);
  el('vUp').textContent = bytes(snap.up);
  el('vTime').textContent = duration(snap.uptime);

  // A dash until two readings of the counters are far enough apart to divide
  // by. The chart starts empty too, so nothing on this panel claims a
  // measurement before one exists.
  const rate = (SERIES.down[SERIES.down.length - 1] || 0) + (SERIES.up[SERIES.up.length - 1] || 0);
  el('vRate').textContent = SERIES.down.length ? bytes(Math.round(rate)) + '/s' : t('none');

  const local = snap.local;
  el('vLocal').textContent = local && local.ip ? local.ip : t('none');
  el('mLocal').textContent = local
    ? [local.country, local.colo ? 'colo ' + local.colo : ''].filter(Boolean).join(' · ')
    : '';

  const proto = el('vProto');
  proto.textContent = transportLabel(transport);
  el('mProto').textContent = running ? `${t('socks')} ${snap.port} · 127.0.0.1` : '';

  const route = el('vRoute');
  if (running) {
    route.textContent = snap.proxyOn ? t('proxyOn') : t('proxyOff');
    el('mRoute').textContent = `127.0.0.1:${options.httpPort || 1820}`;
  } else {
    route.textContent = t('none');
    el('mRoute').textContent = '';
  }

  renderStrip(phase);
  renderAlert(phase);
}

/**
 * The four stages, lit only on evidence.
 *
 *   core      a child process is up -- the phase says so, nothing else does
 *   listener  a SOCKS5 port is known, which only happens once one accepted
 *   tunnel    the probe came back through it, which is the only real proof
 *   exit      an address was answered through the tunnel
 */
function renderStrip(phase) {
  const started = phase !== 'idle';
  const listener = snap.port > 0;
  const tunnel = phase === 'running';
  const exit = !!(snap.egress && snap.egress.ip);

  const core = el('stCore');
  core.classList.toggle('lit', started && !tunnel);
  core.classList.toggle('done', tunnel);

  const lis = el('stListener');
  lis.classList.toggle('lit', listener && !tunnel);
  lis.classList.toggle('done', listener || tunnel);

  const psi = el('stPsiphon');
  const inPath = !!snap.psiphon || !!options.psiphon;
  psi.classList.toggle('lit', inPath && !tunnel);
  psi.classList.toggle('done', inPath && tunnel);
  psi.classList.toggle('hidden', !inPath);
  el('stPsiphonArrow').classList.toggle('hidden', !inPath);
  // A stage that is not in the path is not drawn at all: a dimmed stage reads
  // as "waiting" when the truth is "not part of this connection".
  el('stPsiphonArrow').classList.toggle('off', !inPath);

  const tun = el('stTunnel');
  tun.classList.toggle('lit', tunnel && !exit);
  tun.classList.toggle('done', tunnel);

  const ex = el('stExit');
  ex.classList.toggle('lit', tunnel && !exit);
  ex.classList.toggle('done', exit);
}

/** The failure, said once and in place, rather than only in the chrome. */
function renderAlert(phase) {
  const node = el('alert');
  if (phase === 'error' && snap.message) {
    node.classList.remove('hidden');
    el('alertText').textContent = snap.message;
  } else {
    node.classList.add('hidden');
  }
}

/* -------------------------------------------------------------------- chart */

/**
 * Throughput over the last minute.
 *
 * The host reports the core's own cumulative byte counters about once a
 * second, so a rate is the difference between two readings divided by the time
 * between them -- measured here rather than assumed, because the host also
 * sends a state message whenever the phase changes and those are not a second
 * apart. A counter that goes backwards is a counter that was reset by a new
 * connection, and that sample is dropped rather than drawn as a spike.
 */
const SERIES = { up: [], down: [], last: null, lastAt: 0 };
const SERIES_MAX = 60;

function sample() {
  const now = Date.now();
  const up = snap.up || 0;
  const down = snap.down || 0;

  if (SERIES.last === null) {
    SERIES.last = { up, down };
    SERIES.lastAt = now;
    return;
  }

  const elapsed = (now - SERIES.lastAt) / 1000;
  if (elapsed < 0.25) return;

  const reset = up < SERIES.last.up || down < SERIES.last.down;
  const upRate = reset ? 0 : Math.max(0, (up - SERIES.last.up) / elapsed);
  const downRate = reset ? 0 : Math.max(0, (down - SERIES.last.down) / elapsed);

  SERIES.last = { up, down };
  SERIES.lastAt = now;
  SERIES.up.push(upRate);
  SERIES.down.push(downRate);
  if (SERIES.up.length > SERIES_MAX) SERIES.up.shift();
  if (SERIES.down.length > SERIES_MAX) SERIES.down.shift();

  // The caption is written by the renderer, from the series this just pushed
  // to -- one place decides what the panel says.
  drawChart();
}

function drawChart() {
  const canvas = el('chart');
  if (!canvas || !canvas.getContext) return;

  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth || 400;
  const height = 132;
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }

  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const style = getComputedStyle(document.documentElement);
  const up = style.getPropertyValue('--accent').trim() || '#00D4AA';
  const down = style.getPropertyValue('--accent-deep').trim() || '#00B896';
  const line = style.getPropertyValue('--line').trim() || 'rgba(255,255,255,.08)';

  // Three rules, so an empty chart still reads as a chart.
  ctx.strokeStyle = line;
  ctx.lineWidth = 1;
  for (let i = 1; i <= 3; i += 1) {
    const y = Math.round((height / 4) * i) + 0.5;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }

  const peak = Math.max(1024, ...SERIES.up, ...SERIES.down);
  const step = SERIES.up.length > 1 ? width / (SERIES_MAX - 1) : width;
  // Right-aligned: the newest sample sits at the trailing edge, so the chart
  // fills from the direction the page reads in.
  const offset = width - (SERIES_MAX - 1) * step;

  const series = [{ data: SERIES.down, color: down }, { data: SERIES.up, color: up }];

  series.forEach(({ data, color }) => {
    if (data.length < 2) return;
    const at = (index) => offset + index * step;

    ctx.beginPath();
    ctx.moveTo(at(SERIES_MAX - data.length), height);
    data.forEach((value, index) => {
      const x = at(SERIES_MAX - data.length + index);
      const y = height - Math.min(height - 4, (value / peak) * (height - 10));
      ctx.lineTo(x, y);
    });
    ctx.lineTo(at(SERIES_MAX - 1), height);
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.globalAlpha = 0.14;
    ctx.fill();
    ctx.globalAlpha = 1;

    ctx.beginPath();
    data.forEach((value, index) => {
      const x = at(SERIES_MAX - data.length + index);
      const y = height - Math.min(height - 4, (value / peak) * (height - 10));
      if (index === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.8;
    ctx.lineJoin = 'round';
    ctx.stroke();
  });
}

window.addEventListener('resize', drawChart);

/* --------------------------------------------------------------- from host */

function onMessage(event) {
  const message = event.data;
  if (!message || !message.type) return;

  switch (message.type) {
    case 'init':
      options = Object.assign(defaults(), message.options || {});
      el('ver').textContent = 'v' + (message.version || '');
      state.corePath = message.core;
      state.hasPsiphon = message.psiphon;
      state.proxy = message.proxy;
      applyLangTheme();
      break;

    case 'state':
      snap = message;
      // A counter tick also arrives as a state message, about once a second,
      // so the check button waits out a short grace before believing that this
      // one is its answer.
      if (probingAt && Date.now() - probingAt > 1_200) {
        probingAt = 0;
        el('probeBtn').textContent = t('check');
      }
      // Sampled first, so the rate in the caption is the rate the chart is
      // drawing rather than the reading before it.
      sample();
      renderHome();
      break;

    case 'log':
      appendLog(message.line);
      break;

    case 'toast':
      toast(message.text, true);
      break;

    case 'saved':
      break;
  }
}

const state = { corePath: '', hasPsiphon: false, proxy: '' };

/* ---------------------------------------------------------------------- log */

const lines = [];

function appendLog(line) {
  if (!line) return;
  lines.push(line);
  if (lines.length > 600) lines.splice(0, lines.length - 600);
  const box = el('logBox');
  const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  box.textContent = lines.join('\n');
  if (atBottom) box.scrollTop = box.scrollHeight;
}

/* -------------------------------------------------------------------- about */

function renderAbout() {
  const version = el('ver').textContent || '';
  el('about').innerHTML = `
    <h2><span class="logo"></span>RIX <span class="ver">${esc(version)}</span></h2>
    <p>${lang === 'fa'
      ? 'کلاینت عبور از فیلتر برای ویندوز. ترافیک از یک تونل بیرون می‌رود و به‌صورت پروکسی SOCKS5 و پروکسی سیستم در اختیار برنامه‌ها قرار می‌گیرد.'
      : 'A censorship-circumvention client for Windows. Traffic leaves through a tunnel and is offered to applications as a local SOCKS5 endpoint and as the system proxy.'}</p>

    <div class="facts">
      <div class="field"><span class="name">${lang === 'fa' ? 'هسته' : 'Core'}</span>
        <span class="ctl"><code>${esc(state.corePath || '—')}</code></span></div>
      <div class="field"><span class="name">Psiphon</span>
        <span class="ctl"><span class="pill ${state.hasPsiphon ? 'ok' : 'off'}">${state.hasPsiphon ? (lang === 'fa' ? 'موجود' : 'present') : (lang === 'fa' ? 'ناموجود' : 'missing')}</span></span></div>
      <div class="field"><span class="name">SOCKS5</span>
        <span class="ctl"><code>127.0.0.1:${snap.port || options.bindPort || 1819}</code></span></div>
      <div class="field"><span class="name">${lang === 'fa' ? 'پروکسی سیستم' : 'System proxy'}</span>
        <span class="ctl"><code>${esc(state.proxy || '—')}</code></span></div>
    </div>

    <h3>${lang === 'fa' ? 'محدودیت‌ها' : 'LIMITS'}</h3>
    <p>${lang === 'fa'
      ? 'پروکسی سیستم همه برنامه‌ها را پوشش نمی‌دهد: فایرفاکس تنظیم پروکسی خودش را دارد و برنامه‌هایی که سوکت خودشان را باز می‌کنند باید دستی به SOCKS5 بالا وصل شوند.'
      : 'The system proxy does not cover everything: Firefox keeps its own proxy settings, and applications that open their own sockets have to be pointed at the SOCKS5 endpoint above by hand.'}</p>
    <p>${lang === 'fa'
      ? 'RIX تحت AGPL-3.0 است و هسته سایفون GPLv3 است؛ این دو با هم سازگارند.'
      : 'RIX is AGPL-3.0 and the Psiphon core is GPLv3; the two are compatible.'}</p>
  `;
}

/* -------------------------------------------------------------------- toast */

let toastTimer = null;

function toast(text, bad) {
  const node = el('toast');
  node.textContent = text || '';
  node.classList.toggle('bad', !!bad);
  node.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('on'), 2400);
}

/* ------------------------------------------------------------------- wiring */

function wire() {
  // One button for all four states: connect, cancel, disconnect, retry. A
  // second press while connecting is a cancel, which is what a user means by
  // pressing it again.
  el('connectBtn').onclick = () => {
    if (snap.phase === 'running') {
      send({ type: 'disconnect' });
    } else if (snap.phase === 'connecting' || snap.phase === 'starting') {
      send({ type: 'disconnect' });
    } else if (snap.phase === 'stopping') {
      return;
    } else {
      persistNow();
      send({ type: 'connect' });
    }
  };

  // The check runs wherever the host runs it and answers with a state message,
  // which is what clears this. The timer is only the bound that keeps the
  // button from saying "..." forever if that answer never comes.
  el('probeBtn').onclick = () => {
    probingAt = Date.now();
    el('probeBtn').textContent = '...';
    send({ type: 'probe' });
    setTimeout(() => {
      if (probingAt && Date.now() - probingAt >= 11_000) {
        probingAt = 0;
        el('probeBtn').textContent = t('check');
      }
    }, 12_000);
  };

  el('langBtn').onclick = () => {
    options.lang = lang === 'fa' ? 'en' : 'fa';
    persist();
    applyLangTheme();
  };

  el('themeBtn').onclick = () => {
    options.theme = theme === 'dark' ? 'light' : 'dark';
    persist();
    applyLangTheme();
  };

  el('minBtn').onclick = () => send({ type: 'minimize' });
  el('maxBtn').onclick = () => send({ type: 'maximize' });
  el('closeBtn').onclick = () => send({ type: 'close' });

  const drag = el('drag');
  drag.onmousedown = (event) => {
    if (event.button === 0) send({ type: 'drag' });
  };
  drag.ondblclick = () => send({ type: 'maximize' });

  el('logCopy').onclick = () => {
    send({ type: 'copy', text: lines.join('\n') });
    toast(t('copied'));
  };
  el('logClear').onclick = () => { lines.length = 0; el('logBox').textContent = ''; };
  el('logFolder').onclick = () => send({ type: 'openFolder' });

  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && (snap.phase === 'connecting' || snap.phase === 'starting')) {
      send({ type: 'disconnect' });
    }
  });

  // The wheel over the page must not zoom the whole window.
  window.addEventListener('wheel', (event) => {
    if (event.ctrlKey) event.preventDefault();
  }, { passive: false });
}

if (host) host.addEventListener('message', onMessage);
else window.addEventListener('message', onMessage);

options = defaults();
wire();
applyLangTheme();
drawChart();
send({ type: 'ready' });
