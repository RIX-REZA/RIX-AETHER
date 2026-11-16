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
    best: 'آی‌پی بهینه', latency: 'تأخیر', coreState: 'Rix Core',
    mode: 'حالت', tunnel: 'تانل کامل', systemProxy: 'پروکسی سیستم',
    proxyOffBtn: 'خاموش کردن پروکسی سیستم', scan: 'اسکن', scanning: 'در حال اسکن',
    scanTitle: 'اسکنر لبه کلودفلر', scanIdle: 'اسکن نشده', scanDone: 'اسکن انجام شد',
    noBest: 'هنوز نتیجه‌ای نیست', unavailable: 'در دسترس نیست',
    disconnected: 'قطع', connected: 'متصل', degraded: 'کیفیت پایین',
    reconnecting: 'اتصال دوباره', connecting2: 'در حال اتصال',
    fqUnavailable: 'تانل کامل در این ساخت در دسترس نیست',
    telegram: 'Rix Channel — Telegram', github: 'Rix GitHub',
    details: 'جزئیات', scan: 'اسکنر',
    scanNote: 'هر آدرس مستقیم با TCP و TLS آزمایش می‌شود؛ هیچ ترافیکی از تانل عبور نمی‌کند. اتصال اول همیشه با مسیر خود هسته برقرار می‌شود؛ آدرس اسکن‌شده فقط بعد از شکست آن مسیر، در تلاش‌های بعدی به‌عنوان peer آزمایش می‌شود. با Psiphon این انتخاب کنار گذاشته می‌شود چون سایفون مسیر خودش را انتخاب می‌کند.',
    updateCheck: 'بررسی به‌روزرسانی', updateChecking: 'در حال بررسی…', updateGet: 'دانلود به‌روزرسانی',
    scanTip: 'برای اتصال بهتر می‌توانی از منوی همبرگری یک اسکن تازه بزنی و آی‌پی جدید بگیری. اتصال بدون اسکن هم همیشه کار می‌کند.',
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
    best: 'Best IP', latency: 'Latency', coreState: 'Rix Core',
    mode: 'Mode', tunnel: 'Full Tunnel', systemProxy: 'System proxy',
    proxyOffBtn: 'Turn off system proxy', scan: 'Scan', scanning: 'Scanning',
    scanTitle: 'Cloudflare edge scanner', scanIdle: 'Not scanned', scanDone: 'Scan finished',
    noBest: 'No result yet', unavailable: 'unavailable',
    disconnected: 'Disconnected', connected: 'Connected', degraded: 'Degraded',
    reconnecting: 'Reconnecting', connecting2: 'Connecting',
    fqUnavailable: 'Full Tunnel is not available in this build',
    telegram: 'Rix Channel — Telegram', github: 'Rix GitHub',
    details: 'Details', scan: 'Scanner',
    scanNote: 'Each address is probed directly with TCP and TLS; nothing goes through the tunnel. The first connect always uses the core\'s own path; a scanned address is only tried as --peer on later attempts, after that path has failed. With Psiphon selected, this pick is left out because Psiphon chooses its own path.',
    updateCheck: 'Check for updates', updateChecking: 'Checking…', updateGet: 'Download update',
    scanTip: 'For a better connection you can run a fresh scan from the hamburger menu and get a new IP. Connecting always works without one.',
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
  { id: 'scan', icon: ICON.globe },
  { id: 'settings', icon: ICON.gear },
  { id: 'details', icon: ICON.bolt },
  { id: 'log', icon: ICON.list },
  { id: 'about', icon: ICON.info },
];

/** The drawer replaces the tab strip: the dial keeps the home screen to
 *  itself, and everything else is one menu away. */
function buildDrawer() {
  NAV.forEach((item) => {
    const icon = el('di' + item.id.charAt(0).toUpperCase() + item.id.slice(1));
    if (icon) icon.innerHTML = item.icon;
  });

  document.querySelectorAll('.drawer-item').forEach((button) => {
    button.onclick = () => {
      show(button.dataset.view);
      closeDrawer();
    };
  });
}

function closeDrawer() {
  el('drawer').classList.add('hidden');
  el('drawerBackdrop').classList.add('hidden');
}

let currentView = 'home';

function show(view) {
  currentView = view;
  document.querySelectorAll('.view').forEach((node) => node.classList.remove('on'));
  el('view-' + view).classList.add('on');
  document.querySelectorAll('.drawer-item').forEach((button) => {
    button.classList.toggle('on', button.dataset.view === view);
  });
  if (view === 'details') drawChart();
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
      {
        k: 'tunnelMode', t: 'chips', name: { fa: 'حالت عبور', en: 'Carriage' },
        opts: [
          { v: 'proxy', fa: 'پروکسی سیستم', en: 'System proxy' },
          { v: 'tunnel', fa: 'تانل کامل (آزمایشی)', en: 'Full Tunnel (experimental)' },
        ],
      },
      { k: 'killSwitch', t: 'switch', name: { fa: 'کیل سوییچ', en: 'Kill switch' } },
      { k: 'splitTunnel', t: 'text', name: { fa: 'استثنای تانل', en: 'Split tunnel' }, ph: '10.0.0.0/8, 192.168.1.5' },
    ],
  },
  {
    id: 'scanner',
    icon: ICON.globe,
    title: { fa: 'اسکنر لبه', en: 'Edge scanner' },
    sub: { fa: 'رنج‌های کلودفلر و انتخاب بهترین آی‌پی', en: 'Cloudflare ranges and best-address selection' },
    fields: [
      { k: 'scanEnabled', t: 'switch', name: { fa: 'اسکن پس‌زمینه', en: 'Background scan' } },
      { k: 'scanUseBestPeer', t: 'switch', name: { fa: 'استفاده از بهترین آی‌پی به‌عنوان peer', en: 'Use best IP as peer' } },
      { k: 'scanPorts', t: 'text', name: { fa: 'پورت‌ها', en: 'Ports' }, ph: '443,2053,8443' },
      { k: 'scanConcurrency', t: 'number', name: { fa: 'هم‌زمانی', en: 'Concurrency' } },
      { k: 'scanTimeoutMs', t: 'number', name: { fa: 'مهلت هر تلاش (میلی‌ثانیه)', en: 'Timeout per attempt (ms)' } },
      { k: 'scanIpv6', t: 'switch', name: { fa: 'شامل IPv6', en: 'Include IPv6' } },
      { k: 'scanSpeedSample', t: 'switch', name: { fa: 'نمونه سرعت', en: 'Throughput sample' } },
      { k: 'scanOnLaunch', t: 'switch', name: { fa: 'اسکن هنگام اجرا', en: 'Scan on launch' } },
      {
        k: 'scannerNote', t: 'note',
        text: {
          fa: 'اسکنر یک منبع ۲۰۰ آدرسی کلودفلر را همراه خود دارد و هر آدرس را مستقیم با TCP و TLS روی پورت‌های بالا آزمایش می‌کند؛ از تانل عبور نمی‌کند. در هر اجرا حداکثر یک اسکن انجام می‌شود. اتصال اول همیشه با مسیر خود هسته برقرار می‌شود؛ آدرس اسکن‌شده فقط بعد از شکست آن مسیر، در تلاش‌های بعدی به‌عنوان peer آزمایش می‌شود. اسکن دوباره فقط با دکمهٔ خودت.',
          en: 'The scanner carries a bundled 200-address Cloudflare source and tests each address directly with TCP and TLS on the ports above; nothing goes through the tunnel. At most one scan runs per launch. The first connect always uses the core\'s own path; a scanned address is only tried as --peer on later attempts, after it has failed. Rescanning is only your button.',
        },
      },
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
      // A switch whose prerequisite is missing (Psiphon without its binaries,
      // which upstream ships for x64 only) is shown locked rather than let
      // the user flip a connection into a guaranteed failure.
      const locked = field.k === 'psiphon' && !state.hasPsiphon;
      toggle.className = 'switch' + (value && !locked ? ' on' : '') + (locked ? ' locked' : '');
      toggle.onclick = () => {
        if (locked) {
          toast(lang === 'fa'
            ? 'سایفون در این ساخت نصب نشده (فقط ساخت ۶۴ بیتی)'
            : 'Psiphon is not present in this build (x64 builds only)', true);
          return;
        }
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
let scan = { running: false, fraction: 0, done: 0, total: 0, current: '', best: null, ranked: [], scannedAt: '', source: '' };
let rix = { state: 'disconnected', detail: '' };
let tunnel = { mode: 'proxy', available: false, why: '', on: false };
let saveTimer = null;

/* The scanner tip is shown once and then never again; the page's own
   localStorage keeps the dismissal across launches. */
let tipClosed = false;
try { tipClosed = localStorage.getItem('rix.scanTip') === '1'; } catch (e) { /* file:// storage */ }

/** When the check button was last pressed, 0 when no check is in flight. */
let probingAt = 0;

function defaults() {
  return {
    transport: 'MASQUE_H3', autoFallback: true, bindPort: 1819, httpPort: 1820,
    dns: '1.1.1.1,1.0.0.1', ipFamily: 'V4', exitLoc: '', peer: '', masqueSni: '',
    upstreamProxy: '', reconnectSecs: 2, quickReconnect: true, keepalive: 5,
    fragment: false, fragmentSize: '', fragmentDelay: '', scanMode: 'DEFAULT',
    perf: '', logLevel: 'INFO', ech: '', routeDirect: '', routeBlock: '', extraArgs: '',
    tunnelMode: 'proxy', killSwitch: false, splitTunnel: '',
    scanEnabled: true, scanUseBestPeer: true, scanIntervalMinutes: 30,
    scanConcurrency: 128, scanTimeoutMs: 1200,
    scanPorts: '443,2053,8443', scanIpv6: false,
    scanSpeedSample: true, scanOnLaunch: true,
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

  buildDrawer();
  buildGroups();
  renderStatic();
  renderHome();
  renderAbout();
  show(currentView);
}

/* --------------------------------------------------------------------- home */

function renderTip() {
  const tip = el('scanTip');
  if (!tip) return;
  el('scanTipText').textContent = t('scanTip');
  // A scan is meaningless with Psiphon, which picks its own path.
  const show = !tipClosed && !options.psiphon;
  tip.classList.toggle('hidden', !show);
}

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
  el('kBest').textContent = t('best');
  el('kLatency').textContent = t('latency');
  el('kCoreState').textContent = t('coreState');
  el('scanTitle').textContent = t('scanTitle');
  el('scanNowBtn').textContent = t('scan');
  el('proxyOffBtn').textContent = t('proxyOffBtn');
  el('scanNote').textContent = t('scanNote');
  NAV.forEach((item) => {
    const label = el('dl' + item.id.charAt(0).toUpperCase() + item.id.slice(1));
    if (label) label.textContent = t(item.id);
  });
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
  // The supervisor starts before the core does, so the session snapshot is
  // still "idle" while Rix Core is already connecting. The screen follows the
  // supervisor for those transitions and the session for the rest; neither
  // one is allowed to claim a state the other has left.
  let phase = snap.phase || 'idle';
  const coreState = snap.rixState || rix.state || 'disconnected';
  if (phase === 'idle' || phase === 'error') {
    if (coreState === 'connecting' || coreState === 'reconnecting') phase = 'connecting';
    else if (coreState === 'connected' || coreState === 'degraded') phase = 'running';
  }
  const busy = phase === 'starting' || phase === 'connecting' || phase === 'stopping';
  const running = phase === 'running';
  const failed = phase === 'error';

  const dial = el('connectBtn');
  dial.classList.toggle('busy', busy);
  dial.classList.toggle('on', running);
  dial.classList.toggle('err', failed);
  el('dialLabel').textContent = running ? t('disconnect')
    : busy ? t('cancel') : failed ? t('retry') : t('connect');

  el('phaseText').textContent = (busy && snap.rixDetail ? snap.rixDetail : '')
    || snap.message || t(PHASE_TEXT[phase] || 'ready');
  el('tbState').className = 'tb-state' + (running ? ' ok' : failed ? ' bad' : '');
  el('tbState').textContent = running
    ? (snap.egress && snap.egress.ip ? snap.egress.ip : t('running'))
    : (failed ? (snap.message || t('error')) : t(PHASE_TEXT[phase] || 'ready'));

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

  // The carriage the user picked, and what Full Tunnel needs. The switch
  // itself lives in settings; the note stays here because it changes what
  // this very connection does.
  const mode = options.tunnelMode === 'tunnel' ? 'tunnel' : 'proxy';
  const note = el('tunnelNote');
  if (mode === 'tunnel' && !(snap.tunnelAvailable ?? tunnel.available)) {
    note.classList.remove('hidden');
    note.textContent = (snap.tunnelWhy || tunnel.why) || t('fqUnavailable');
  } else if (mode === 'tunnel') {
    note.classList.remove('hidden');
    note.textContent = lang === 'fa'
      ? 'تانل کامل آزمایشی است: به RIX-Tunnel.exe، Wintun و تأیید UAC نیاز دارد. مسیرها و DNS هنگام قطع بازگردانده می‌شوند.'
      : 'Full Tunnel is experimental: it needs RIX-Tunnel.exe, Wintun and a UAC confirmation. Routes and DNS are restored on disconnect.';
  } else {
    note.classList.add('hidden');
  }

  // ---- the details view's numbers. They are updated even when the view is
  // hidden, so opening the drawer shows current values, not stale ones.
  el('vDown').textContent = bytes(snap.down);
  el('vUp').textContent = bytes(snap.up);
  el('vTime').textContent = duration(snap.uptime);
  const rate = (SERIES.down[SERIES.down.length - 1] || 0) + (SERIES.up[SERIES.up.length - 1] || 0);
  el('vRate').textContent = SERIES.down.length ? bytes(Math.round(rate)) + '/s' : t('none');

  const local = snap.local;
  el('vLocal').textContent = local && local.ip ? local.ip : t('none');
  el('mLocal').textContent = local
    ? [local.country, local.colo ? 'colo ' + local.colo : ''].filter(Boolean).join(' · ')
    : '';

  const transport = snap.transport || options.transport;
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

  // The scanner's recommendation and the supervisor's state stay two separate
  // facts: an address can be excellent while the supervisor is reconnecting.
  const best = scan.best || snap.scannedBest || null;
  el('vBest').textContent = best ? best.endpoint : t('noBest');
  el('mBest').textContent = best
    ? [best.port ? ':' + best.port : '', best.success != null ? Math.round(best.success * 100) + '%' : '',
       best.score != null ? 'score ' + Number(best.score).toFixed(2) : ''].filter(Boolean).join(' · ')
    : (scan.running ? t('scanning') : '');

  const latencyValue = scan.best && scan.best.latency != null ? scan.best.latency : null;
  el('vLatency').textContent = latencyValue != null ? Math.round(latencyValue) + ' ms' : '—';
  el('mLatency').textContent = scan.best && scan.best.jitter != null ? 'jitter ' + Math.round(scan.best.jitter) + ' ms' : '';

  const coreLabels = {
    connecting: 'connecting2', connected: 'connected', degraded: 'degraded',
    reconnecting: 'reconnecting', disconnected: 'disconnected',
  };
  const coreNode = el('vCoreState');
  coreNode.textContent = t(coreLabels[coreState] || 'disconnected');
  coreNode.className = 'tile-v small ' + (coreState === 'connected' ? 'ok'
    : coreState === 'degraded' ? 'warn' : coreState === 'disconnected' ? '' : 'warn');
  el('mCoreState').textContent = snap.rixDetail || rix.detail || '';
  el('tileCore').classList.toggle('on', coreState === 'connected');
  el('tileCore').classList.toggle('bad', coreState === 'degraded');

  renderStrip(phase);
  renderAlert(phase);
  renderScan();
  renderUpdate();
}

/** The one secondary action on the home screen: keeping the app current. */
function renderUpdate() {
  const button = el('updateBtn');
  const update = state.update || {};
  if (update.checking) {
    button.textContent = t('updateChecking');
    button.disabled = true;
  } else if (update.available) {
    button.textContent = t('updateGet') + (update.version ? ' — v' + update.version : '');
    button.disabled = false;
  } else {
    button.textContent = t('updateCheck');
    button.disabled = false;
  }
}

/** The scanner panel: progress, the address being tested, and the shortlist. */
function renderScan() {
  const running = !!scan.running;
  const fraction = Number.isFinite(scan.fraction) ? scan.fraction : 0;
  el('scanFill').style.width = running
    ? Math.max(2, Math.min(100, fraction * 100)) + '%'
    : (scan.best ? '100%' : '0');

  const info = el('scanInfo');
  if (running) {
    info.textContent = scan.total
      ? `${t('scanning')} ${scan.done}/${scan.total}`
      : t('scanning');
  } else if (scan.best) {
    info.textContent = `${t('scanDone')}${scan.source ? ' · ' + scan.source : ''}`;
  } else {
    info.textContent = t('scanIdle');
  }

  el('scanNowBtn').textContent = running ? '…' : t('scan');
  el('scanNowBtn').disabled = running;

  el('scanCurrent').textContent = running && scan.current
    ? 'TCP/TLS · ' + scan.current
    : (scan.scannedAt ? scan.scannedAt.replace('Z', '').replace('T', ' ') : '');
  el('scanBest').textContent = scan.best
    ? `${t('best')}: ${scan.best.endpoint} · ${Math.round(scan.best.latency)} ms`
    : '';

  const list = el('scanList');
  const ranked = Array.isArray(scan.ranked) ? scan.ranked : [];
  list.innerHTML = ranked.slice(0, 6).map((entry, index) =>
    `<span class="scan-chip${index === 0 ? ' best' : ''}" title="${esc(entry.address || '')}">` +
    `${esc((entry.address || '') + ':' + (entry.port || ''))} · ${Math.round(entry.latency || 0)}ms</span>`
  ).join('');

  // The home screen's one-line heartbeat for the same scan. Clicking it opens
  // the full scanner view; nothing here duplicates the detail.
  const miniFill = el('miniFill');
  miniFill.style.width = running
    ? Math.max(2, Math.min(100, fraction * 100)) + '%'
    : (scan.best ? '100%' : '0');
  el('miniScanText').textContent = running
    ? (scan.total ? `${t('scanning')} ${scan.done}/${scan.total}` : t('scanning'))
    : (scan.best ? `${t('best')}: ${scan.best.endpoint}` : t('scanIdle'));
  renderTip();
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
  const up = style.getPropertyValue('--accent').trim() || '#14b8a6';
  const down = style.getPropertyValue('--cyan').trim() || '#22d3ee';
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
      tunnel.available = !!message.tunnelAvailable;
      tunnel.why = message.tunnelWhy || '';
      if (message.scan) scan = Object.assign(scan, message.scan);
      applyLangTheme();
      break;

    case 'state':
      snap = message;
      if (message.rixState) rix = { state: message.rixState, detail: message.rixDetail || '' };
      tunnel.mode = message.tunnelMode || tunnel.mode;
      tunnel.available = !!message.tunnelAvailable;
      tunnel.why = message.tunnelWhy || '';
      tunnel.on = !!message.fullTunnelOn;
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

    case 'scan':
      // Drop anything older than the newest scan message. Progress arrives
      // from concurrent probes, and a late "running" report must not undo a
      // completion the page has already drawn.
      if (typeof message.seq === 'number' && message.seq < (scan.seq || 0)) break;
      scan = Object.assign(scan, message);
      if (typeof message.fraction === 'number') scan.fraction = message.fraction;
      renderScan();
      renderHome();
      break;

    case 'log':
      appendLog(message.line);
      break;

    case 'toast':
      toast(message.text, true);
      break;

    case 'update':
      state.update = {
        checking: false,
        available: !!message.available,
        version: message.version || '',
        url: message.url || '',
      };
      renderUpdate();
      if (message.error) toast(message.error, true);
      break;

    case 'saved':
      break;
  }
}

const state = { corePath: '', hasPsiphon: false, proxy: '', update: { checking: false } };

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
    const coreState = snap.rixState || rix.state || 'disconnected';
    const coreBusy = coreState === 'connecting' || coreState === 'reconnecting';
    const coreUp = coreState === 'connected' || coreState === 'degraded';
    if (snap.phase === 'running' || snap.phase === 'connecting' ||
        snap.phase === 'starting' || coreBusy || coreUp) {
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

  el('tgBtn').title = t('telegram');
  el('ghBtn').title = t('github');
  el('tgBtn').onclick = () => send({ type: 'openUrl', url: 'https://t.me/rixpanel' });
  el('ghBtn').onclick = () => send({ type: 'openUrl', url: 'https://github.com/RIX-REZA/RIX-AETHER' });

  // The drawer: one button opens it, one click inside navigates, anywhere
  // outside closes it. Escape closes it before it cancels a connect.
  el('menuBtn').onclick = () => {
    const hidden = el('drawer').classList.toggle('hidden');
    el('drawerBackdrop').classList.toggle('hidden', hidden);
  };
  el('drawerBackdrop').onclick = closeDrawer;
  el('miniScan').onclick = () => { show('scan'); closeDrawer(); };

  // Update check: the first press asks the host, the second opens the release
  // page when there is something to get. The host keeps the whitelist; the
  // page never opens a URL it was not handed back with an "available" flag.
  el('updateBtn').onclick = () => {
    const update = state.update || {};
    if (update.available && update.url) {
      send({ type: 'openUrl', url: update.url });
      return;
    }
    state.update = { checking: true };
    renderUpdate();
    send({ type: 'checkUpdate' });
    setTimeout(() => {
      if (state.update && state.update.checking) {
        state.update = {};
        renderUpdate();
      }
    }, 15_000);
  };

  el('proxyOffBtn').onclick = () => {
    send({ type: 'proxyOff' });
    toast(t('proxyOffBtn'));
  };

  el('scanTipClose').onclick = () => {
    tipClosed = true;
    try { localStorage.setItem('rix.scanTip', '1'); } catch (e) { /* file:// storage */ }
    renderTip();
  };

  el('scanNowBtn').onclick = () => {
    scan.running = true;
    renderScan();
    send({ type: 'scanNow' });
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
    if (event.button !== 0) return;
    // Buttons live inside the drag strip (the hamburger); their click must
    // win over the drag, or the window starts moving and the menu never
    // opens. Only bare drags start a drag.
    if (event.target.closest('button')) return;
    send({ type: 'drag' });
  };
  drag.ondblclick = () => send({ type: 'maximize' });

  el('logCopy').onclick = () => {
    send({ type: 'copy', text: lines.join('\n') });
    toast(t('copied'));
  };
  el('logClear').onclick = () => { lines.length = 0; el('logBox').textContent = ''; };
  el('logFolder').onclick = () => send({ type: 'openFolder' });

  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      if (!el('drawer').classList.contains('hidden')) {
        closeDrawer();
        return;
      }
      if (snap.phase === 'connecting' || snap.phase === 'starting') {
        send({ type: 'disconnect' });
      }
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
