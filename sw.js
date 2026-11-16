// 현장 무전기 서비스 워커
// 항상 최신 파일을 먼저 받아 오고, 인터넷이 안 될 때만 저장해 둔 화면을 보여 줍니다.
const CACHE = "radio-shell-v1";

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;          // PeerJS 등 외부 주소는 건드리지 않음
  if (url.search.indexOf("nocache=") !== -1) return;   // 새 버전 확인용 요청은 그대로 통과
  e.respondWith(
    fetch(req.mode === "navigate" ? req.url : req, { cache: "no-cache" })
      .then((res) => {
        if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {}); }
        return res;
      })
      .catch(() => caches.match(req).then((r) => r || caches.match("voice.html")))
  );
});
