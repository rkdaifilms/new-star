const CACHE_NAME = "newstar-app-shell-v2";
const APP_SHELL = [
  "/",
  "/index.html",
  "/auth.html",
  "/profile.html",
  "/settings.html",
  "/navbar.js",
  "/supabase-config.js",
  "/manifest.json"
];

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(APP_SHELL);
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(key => key.startsWith("newstar-app-shell-") && key !== CACHE_NAME)
      .map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Never cache Supabase auth, database, storage, edge-function, or other cross-origin data.
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/rest/") || url.pathname.startsWith("/auth/") ||
      url.pathname.startsWith("/storage/") || url.pathname.startsWith("/functions/")) return;

  if (request.mode === "navigate") {
    event.respondWith((async () => {
      try {
        const fresh = await fetch(request);
        if (fresh && fresh.ok) {
          const cache = await caches.open(CACHE_NAME);
          cache.put(request, fresh.clone()).catch(() => {});
        }
        return fresh;
      } catch {
        const cache = await caches.open(CACHE_NAME);
        return (await cache.match(request)) || (await cache.match("/index.html")) ||
          new Response("You are offline. Reconnect to use live account features.", {
            status: 503, headers: { "Content-Type": "text/plain; charset=utf-8" }
          });
      }
    })());
    return;
  }

  if (APP_SHELL.includes(url.pathname)) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(request);
      if (cached) {
        fetch(request).then(response => {
          if (response && response.ok) cache.put(request, response.clone());
        }).catch(() => {});
        return cached;
      }
      return fetch(request);
    })());
  }
});
