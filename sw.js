const CACHE_NAME = "newstar-app-shell-v15";
const APP_SHELL = [
  "/",
  "/index.html",
  "/auth.html",
  "/profile.html",
  "/settings.html",
  "/camera.html",
  "/upload.html",
  "/inbox.html",
  "/live.html",
  "/gaming.html",
  "/campaign.html",
  "/monetization.html",
  "/wallet.html",
  "/admin.html",
  "/navbar.js",
  "/ai-chat.js",
  "/creator-ai.html",
  "/creator-ai.js",
  "/preferences.js",
  "/i18n.js",
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

self.addEventListener("push", event => {
  event.waitUntil((async () => {
    let payload = {};
    try {
      payload = event.data ? event.data.json() : {};
    } catch {
      payload = { body: event.data ? event.data.text() : "" };
    }
    const title = String(payload.title || "New Star");
    const body = String(payload.body || payload.message || "आपके अकाउंट में नई activity है।");
    const rawUrl = typeof payload.url === "string" ? payload.url : "/inbox.html";
    let targetUrl = "/inbox.html";
    try {
      const parsed = new URL(rawUrl, self.location.origin);
      if (parsed.origin === self.location.origin) targetUrl = parsed.pathname + parsed.search + parsed.hash;
    } catch {}
    await self.registration.showNotification(title, {
      body,
      tag: String(payload.tag || "newstar-activity"),
      data: { url: targetUrl },
      renotify: false
    });
  })());
});

self.addEventListener("notificationclick", event => {
  event.notification.close();
  const rawUrl = event.notification.data && event.notification.data.url;
  let targetUrl = "/inbox.html";
  try {
    const parsed = new URL(typeof rawUrl === "string" ? rawUrl : "/inbox.html", self.location.origin);
    if (parsed.origin === self.location.origin) targetUrl = parsed.pathname + parsed.search + parsed.hash;
  } catch {}
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      if (new URL(client.url).origin === self.location.origin) {
        await client.navigate(targetUrl);
        return client.focus();
      }
    }
    return self.clients.openWindow(targetUrl);
  })());
});
