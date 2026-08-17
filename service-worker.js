const CACHE='treino-princesa-v21';
const ASSETS=['./','./index.html','./manifest.webmanifest','./icon.svg','./enhancements-v21.js'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)));self.skipWaiting();});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))));self.clients.claim();});
async function injectEnhancements(response){
  const text=await response.text();
  const html=text.includes('enhancements-v21.js')?text:text.replace('</body>','<script src="./enhancements-v21.js"></script></body>');
  const headers=new Headers(response.headers);headers.set('content-type','text/html; charset=utf-8');
  return new Response(html,{status:response.status,statusText:response.statusText,headers});
}
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET')return;
  const u=new URL(e.request.url),isPage=e.request.mode==='navigate'||u.pathname.endsWith('/index.html')||u.pathname.endsWith('/TREINO-DA-PRINCESA/');
  if(isPage){e.respondWith((async()=>{try{const r=await fetch(e.request);const cp=r.clone();caches.open(CACHE).then(c=>c.put('./index.html',cp));return injectEnhancements(r)}catch{const r=await caches.match('./index.html');return r?injectEnhancements(r):Response.error()}})());return;}
  e.respondWith(fetch(e.request).then(r=>{const cp=r.clone();caches.open(CACHE).then(c=>c.put(e.request,cp));return r;}).catch(()=>caches.match(e.request)));
});