/* RIX — the interface.
 *
 * The page owns the look; C# owns the tunnel. Everything here is either drawn
 * from the last snapshot the host sent, or is a message asking the host to do
 * something. Nothing decides on its own that the tunnel is up: that verdict
 * comes from a real request the host made through it, because a listener that
 * accepts is not the same thing as a tunnel that carries.
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
    down: 'دانلود', up: 'آپلود', time: 'زمان اتصال',
    exit: 'آی‌پی خروجی', you: 'اتصال شما', proto: 'پروتکل',
    check: 'بررسی', copy: 'کپی', copied: 'کپی شد',
    home: 'خانه', settings: 'تنظیمات', log: 'گزارش', about: 'درباره',
    saved: 'ذخیره شد', clear: 'پاک کردن', folder: 'پوشه',
    none: '—', unknown: 'نامشخص',
    running: 'متصل', connecting: 'در حال اتصال', starting: 'آماده‌سازی',
    stopping: 'در حال قطع', error: 'خطا',
    proxyOn: 'پروکسی سیستم روشن', proxyOff: 'پروکسی سیستم خاموش',
    socks: 'پورت', beta: 'بتا',
    lang: 'زبان', theme: 'پوسته', dark: 'تیره', light: 'روشن',
    minTray: 'بستن به سینی سیستم',
    autostart: 'اجرا با ویندوز',
    openFolder: 'پوشه تنظیمات', reset: 'بازگردانی پیش‌فرض',
    resetAsk: 'همه تنظیمات به حالت پیش‌فرض برگردد؟',
    on: 'روشن', off: 'خاموش', auto: 'خودکار',
  },
  en: {
    connect: 'Connect', disconnect: 'Disconnect', cancel: 'Cancel', retry: 'Try again',
    ready: 'Ready', idle: 'Ready',
    down: 'Download', up: 'Upload', time: 'Connected for',
    exit: 'Exit address', you: 'Your connection', proto: 'Transport',
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
  { v: 'DEFAULT', fa: 'پیش‌فرض هسته', en: 'Core default' },
  { v: 'TURBO', fa: 'Turbo — سریع‌ترین', en: 'Turbo — fastest' },
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
  { v: 'DEBUG', fa: 'اشکال‌زدایی', en: 'Debug' },
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
  { v: 'direct', fa: 'مستقیم — سریع‌تر', en: 'Direct — faster' },
  { v: 'cdn', fa: 'از پشت CDN — مقاوم‌تر', en: 'Behind a CDN — harder to block' },
];

/* --------------------------------------------------------------- svg glyphs */

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

const NAV = [
  { id: 'home', label: 'خانه', icon: ICON.home },
  { id: 'settings', label: 'تنظیمات', icon: ICON.gear },
  { id: 'log', label: 'گزارش', icon: ICON.list },
  { id: 'about', label: 'درباره', icon: ICON.info },
];

function buildRail() {
  const rail = el('rail');
  rail.innerHTML = NAV.map((item) =>
    `<button data-view="${item.id}">${item.icon}<span>${t(item.id)}</span></button>`
  ).join('');

  rail.querySelectorAll('button').forEach((button) => {
    button.onclick = () => show(button.dataset.view);
  });
}

let currentView = 'home';

function show(view) {
  currentView = view;
  document.querySelectorAll('.view').forEach((node) => node.classList.remove('on'));
  el('view-' + view).classList.add('on');
  document.querySelectorAll('.rail button').forEach((button) => {
    button.classList.toggle('on', button.dataset.view === view);
  });
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

function buildGroups() {
  const root = el('groups');

  // Rebuilding the list -- which a language or theme change does -- must not
  // close whatever the user had opened.
  const open = new Set(
    Array.from(root.querySelectorAll('.group.open')).map((node) => node.dataset.group)
  );

  root.innerHTML = GROUPS.map((group) => `
    <section class="group" data-group="${group.id}">
      <header>
        <span class="ico">${group.icon}</span>
        <span class="titles">
          <b>${L(group.title)}${group.badge ? ` <span class="pill beta">${group.badge}</span>` : ''}</b>
          <span>${L(group.sub)}</span>
        </span>
        <span class="chev">›</span>
      </header>
      <div class="body"></div>
    </section>
  `).join('');

  root.querySelectorAll('.group').forEach((node) => {
    if (open.has(node.dataset.group)) node.classList.add('open');
    node.querySelector('header').onclick = () => node.classList.toggle('open');
  });

  GROUPS.forEach(renderGroup);
}

function renderGroup(group) {
  const node = document.querySelector(`.group[data-group="${group.id}"]`);
  const body = node.querySelector('.body');
  body.innerHTML = '';

  group.fields.forEach((field) => {
    if (field.t === 'note') {
      const note = document.createElement('p');
      note.className = 'hint';
      note.style.padding = '10px 0';
      note.textContent = L(field.text);
      body.appendChild(note);
      return;
    }

    if (field.t === 'chain') {
      const row = document.createElement('div');
      row.className = 'field';
      row.innerHTML = `<span class="name">${L(field.name)}</span>
        <span class="ctl"><span class="chain"><b>${transportLabel(options.transport)}</b>
        <i>→</i> WARP <i>→</i> Psiphon <i>→</i> Internet</span></span>`;
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
        buildGroups();
        applyTheme();
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
          if (field.k === 'transport') renderGroupByName('psiphon');
        };
        wrap.appendChild(chip);
      });
      ctl.appendChild(wrap);
      return;
    }

    if (field.t === 'chips+custom') {
      const wrap = document.createElement('div');
      wrap.className = 'chips';
      wrap.style.width = '100%';

      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'short';
      input.maxLength = 2;
      input.placeholder = 'CC';
      input.style.textTransform = 'uppercase';
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
          renderGroupByName('psiphon');
        };
        wrap.appendChild(chip);
      });

      input.oninput = () => {
        const code = input.value.replace(/[^a-zA-Z]/g, '').toUpperCase().slice(0, 2);
        input.value = code;
        set(field.k, code);
        wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('on'));
        renderGroupByName('psiphon');
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

function renderGroupByName(id) {
  const group = GROUPS.find((g) => g.id === id);
  if (!group) return;
  const node = document.querySelector(`.group[data-group="${id}"]`);
  const wasOpen = node && node.classList.contains('open');
  renderGroup(group);
  if (wasOpen) node.classList.add('open');
}

function transportLabel(value) {
  const found = TRANSPORTS.find((x) => x.v === value);
  return found ? L(found) : (value || t('none'));
}

/* -------------------------------------------------------------------- state */

let options = {};
let snap = { phase: 'idle', message: '', up: 0, down: 0, uptime: 0 };
let saveTimer = null;

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

  buildRail();
  buildGroups();
  renderHome();
  renderStatic();
  renderAbout();
}

/** Language or theme changed: re-draw everything, and stay on the same screen. */
function applyTheme() {
  applyLangTheme();
  show(currentView);
}

/* --------------------------------------------------------------------- home */

function renderStatic() {
  el('kDown').textContent = t('down');
  el('kUp').textContent = t('up');
  el('kTime').textContent = t('time');
  el('kExit').textContent = t('exit');
  el('kYou').textContent = t('you');
  el('kProto').textContent = t('proto');
  el('logCopy').textContent = t('copy');
  el('logClear').textContent = t('clear');
  el('logFolder').textContent = t('folder');
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
  const orb = el('orb');

  orb.classList.toggle('busy', phase === 'starting' || phase === 'connecting' || phase === 'stopping');
  orb.classList.toggle('on', phase === 'running');
  orb.classList.toggle('err', phase === 'error');

  const title = el('orbTitle');
  const sub = el('orbSub');

  if (phase === 'running') {
    title.textContent = t('disconnect');
    const parts = [snap.transportLabel || transportLabel(options.transport)];
    if (snap.psiphon) parts.push('Psiphon' + (snap.psiphonRegion ? ' · ' + snap.psiphonRegion : ''));
    sub.textContent = parts.join(' · ');
  } else if (phase === 'connecting' || phase === 'starting' || phase === 'stopping') {
    title.textContent = t('cancel');
    sub.textContent = snap.message || t(PHASE_TEXT[phase]);
  } else if (phase === 'error') {
    title.textContent = t('retry');
    sub.textContent = snap.message || t('error');
  } else {
    title.textContent = t('connect');
    sub.textContent = t('ready');
  }

  el('vDown').textContent = bytes(snap.down);
  el('vUp').textContent = bytes(snap.up);
  el('vTime').textContent = duration(snap.uptime);
  el('bar').classList.toggle('hidden', !(phase === 'starting' || phase === 'connecting'));
  // Also the reset: every state message is the answer to whatever was asked,
  // so the check button is live again by the time one arrives.
  el('probeBtn').textContent = t('check');

  const exit = snap.egress;
  el('vExit').textContent = exit ? exit.ip : t('none');
  el('mExit').textContent = exit
    ? [exit.country, exit.colo ? 'colo ' + exit.colo : '', exit.warp ? 'warp ' + exit.warp : '']
        .filter(Boolean).join(' · ')
    : (phase === 'running' ? t('unknown') : '');

  const local = snap.local;
  el('vLocal').textContent = local ? local.ip : t('none');
  el('mLocal').textContent = local
    ? [local.country, local.colo ? 'colo ' + local.colo : ''].filter(Boolean).join(' · ')
    : '';

  const proto = el('vProto');
  proto.innerHTML = '';
  const name = document.createElement('span');
  name.textContent = transportLabel(snap.transport || options.transport);
  proto.appendChild(name);

  if (snap.psiphon || options.psiphon) {
    const pill = document.createElement('span');
    pill.className = 'pill beta';
    pill.textContent = 'Psiphon ' + t('beta');
    proto.appendChild(pill);
  }
  if (phase === 'running') {
    const pill = document.createElement('span');
    pill.className = 'pill ' + (snap.proxyOn ? 'ok' : 'off');
    pill.textContent = snap.proxyOn ? t('proxyOn') : t('proxyOff');
    proto.appendChild(pill);
  }

  const mProto = el('mProto');
  mProto.textContent = phase === 'running'
    ? `${t('socks')} ${snap.port} · 127.0.0.1`
    : '';
}

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
      applyTheme();
      break;

    case 'state':
      snap = message;
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
    <h2>RIX <span class="ver">${version}</span></h2>
    <p>${lang === 'fa'
      ? 'کلاینت عبور از فیلتر برای ویندوز. ترافیک از یک تونل WARP بیرون می‌رود و به‌صورت پروکسی SOCKS5 و پروکسی سیستم در اختیار برنامه‌ها قرار می‌گیرد.'
      : 'A censorship-circumvention client for Windows. Traffic leaves through a WARP tunnel and is offered to applications as a local SOCKS5 endpoint and as the system proxy.'}</p>
    <div class="group"><div class="body" style="display:block;border:0;padding:14px 16px">
      <div class="field"><span class="name">${lang === 'fa' ? 'هسته' : 'Core'}</span>
        <span class="ctl"><code>${state.corePath || '—'}</code></span></div>
      <div class="field"><span class="name">Psiphon</span>
        <span class="ctl"><span class="pill ${state.hasPsiphon ? 'ok' : 'off'}">${state.hasPsiphon ? (lang === 'fa' ? 'موجود' : 'present') : (lang === 'fa' ? 'ناموجود' : 'missing')}</span></span></div>
      <div class="field"><span class="name">SOCKS5</span>
        <span class="ctl"><code>127.0.0.1:${snap.port || options.bindPort || 1819}</code></span></div>
      <div class="field"><span class="name">${lang === 'fa' ? 'پروکسی سیستم' : 'System proxy'}</span>
        <span class="ctl"><code>${state.proxy || '—'}</code></span></div>
    </div></div>
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
  const orb = el('orb');
  orb.onclick = () => {
    if (snap.phase === 'running' || snap.phase === 'connecting' || snap.phase === 'starting') {
      send({ type: 'disconnect' });
    } else {
      persistNow();
      send({ type: 'connect' });
    }
  };

  el('probeBtn').onclick = () => {
    el('probeBtn').textContent = '…';
    send({ type: 'probe' });
  };

  el('langBtn').onclick = () => {
    options.lang = lang === 'fa' ? 'en' : 'fa';
    persist();
    applyTheme();
  };

  el('themeBtn').onclick = () => {
    options.theme = theme === 'dark' ? 'light' : 'dark';
    persist();
    applyTheme();
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
applyTheme();
send({ type: 'ready' });
