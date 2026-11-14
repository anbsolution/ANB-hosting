const CACHE_NAME = 'anb-hosting-shell-v1';
const SHELL_URLS = ['/', '/manifest.webmanifest', '/icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_URLS))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith('anb-hosting-') && key !== CACHE_NAME)
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const requestUrl = new URL(request.url);

  if (
    request.method !== 'GET' ||
    requestUrl.origin !== self.location.origin ||
    requestUrl.pathname.startsWith('/api/')
  ) {
    return;
  }

  if (request.mode === 'navigate' && requestUrl.pathname === '/') {
    event.respondWith(
      fetch(request).catch(async () => {
        const cachedShell = await caches.match('/');
        return cachedShell ?? Response.error();
      }),
    );
    return;
  }

  if (requestUrl.pathname.startsWith('/_next/static/')) {
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cachedAsset = await cache.match(request);
        if (cachedAsset) return cachedAsset;

        const response = await fetch(request);
        if (response.ok) await cache.put(request, response.clone());
        return response;
      }),
    );
  }
});
