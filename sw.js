// 英語學習筆記本 - Service Worker（讓 App 可離線開啟）
// 更新程式後，把版本號改掉，使用者下次開啟就會拿到新版
const CACHE_NAME = 'eng-notebook-v1.9.0';
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
  'https://cdn.jsdelivr.net/npm/sortablejs@1.15.6/Sortable.min.js'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache =>
      // 個別加入，某個檔案失敗也不影響其他檔案
      Promise.all(APP_FILES.map(url => cache.add(url).catch(err => console.warn('快取失敗:', url, err))))
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;                       // AI 查詢等 POST 請求不處理
  const url = new URL(req.url);
  const isSameOrigin = url.origin === self.location.origin;
  const isSortable = url.hostname === 'cdn.jsdelivr.net';
  if (!isSameOrigin && !isSortable) return;              // 其他網站（Gemini、發音）直接連網

  if (req.mode === 'navigate' || url.pathname.endsWith('.html')) {
    // 網頁本體：優先連網拿最新版，沒網路時用快取
    event.respondWith(
      fetch(req).then(res => {
        const copy = res.clone();
        caches.open(CACHE_NAME).then(c => c.put(req, copy));
        return res;
      }).catch(() => caches.match(req).then(r => r || caches.match('./index.html')))
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
