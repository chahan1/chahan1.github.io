// 英語學習筆記本 - Service Worker（讓 App 可離線開啟）
// 更新程式後，把版本號改掉，使用者下次開啟就會拿到新版
const CACHE_NAME = 'eng-notebook-v2.37.0';
const APP_FILES = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './maskable-192.png',
  './maskable-512.png',
  './apple-touch-icon.png',
  './screenshot-1.png',
  './sortable.min.js'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache =>
      // 個別加入，某個檔案失敗也不影響其他檔案
      // cache: 'reload'：安裝新版時一定向 GitHub 拿最新檔案，不用瀏覽器暫存的舊檔
      Promise.all(APP_FILES.map(url => cache.add(new Request(url, { cache: 'reload' })).catch(err => console.warn('快取失敗:', url, err))))
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith('eng-notebook-') && k !== CACHE_NAME).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;                       // AI 查詢等 POST 請求不處理
  const url = new URL(req.url);
  const isSameOrigin = url.origin === self.location.origin;
  const isFirebaseSdk = url.hostname === 'www.gstatic.com' && url.pathname.startsWith('/firebasejs/');   // 雲端同步的程式庫（有版本號，不會變）
  if (!isSameOrigin && !isFirebaseSdk) return;   // 其他網站（Gemini、發音、Firebase 連線）直接連網

  if (req.mode === 'navigate' || url.pathname.endsWith('.html')) {
    // v2.35.1 網頁本體：先用手機裡的快取立刻開啟（不必等網路），同時在背景向 GitHub 拿最新版存起來。
    // 新版本身會透過 sw.js 版本號更新（安裝時下載新檔 → 自動重新載入一次），所以不會一直停在舊版。
    const network = fetch(req.url, { cache: 'no-cache', credentials: 'same-origin' }).then(res => {
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const copy = res.clone();
      caches.open(CACHE_NAME).then(c => c.put(req, copy));
      return res;
    });
    event.waitUntil(network.catch(() => {}));
    event.respondWith(
      caches.match(req).then(r => r || caches.match('./index.html')).then(cached => cached || network)
        .catch(() => caches.match('./index.html'))
    );
  } else {
    // 圖示、拖曳函式庫：優先用快取
    event.respondWith(
      caches.match(req).then(cached => cached || fetch(req).then(res => {
        if (res.ok) { const copy = res.clone(); caches.open(CACHE_NAME).then(c => c.put(req, copy)); }
        return res;
      }))
    );
  }
});
