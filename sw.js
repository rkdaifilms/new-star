self.addEventListener('install', (e) => {
  console.log('New Star Service Worker Installed');
});

self.addEventListener('fetch', (e) => {
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});r
