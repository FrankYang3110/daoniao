/* 离线缓存策略：
   - index.html / sw 自身：网络优先（改了代码立刻生效），离线时回退缓存
   - 图标等静态资源：缓存优先（永不变化）
   这样不再需要手工维护版本号 */
var CACHE = 'daoniao-v4';
var CORE = ['./', './index.html'];
var STATIC = ['./manifest.webmanifest', './icon-180.png', './icon-192.png', './icon-512.png'];

self.addEventListener('install', function(e){
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE).then(function(c){
      return c.addAll(CORE.concat(STATIC));
    }).catch(function(){})
  );
});

self.addEventListener('activate', function(e){
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.map(function(k){ if(k !== CACHE) return caches.delete(k); }));
    }).then(function(){ return self.clients.claim(); })
  );
});

function isStatic(url){
  return /\.(png|jpg|jpeg|svg|ico|webmanifest)$/i.test(url.pathname);
}

self.addEventListener('fetch', function(e){
  if(e.request.method !== 'GET') return;

  var req = e.request;
  var url;
  try{ url = new URL(req.url); }catch(err){ return; }

  /* 只处理同源请求，避免拦截第三方 */
  if(url.origin !== self.location.origin) return;

  if(isStatic(url)){
    /* 静态资源：缓存优先 */
    e.respondWith(
      caches.match(req).then(function(hit){
        if(hit) return hit;
        return fetch(req).then(function(res){
          var copy = res.clone();
          caches.open(CACHE).then(function(c){ c.put(req, copy); }).catch(function(){});
          return res;
        });
      })
    );
    return;
  }

  /* 页面/脚本：网络优先，失败回退缓存 */
  e.respondWith(
    fetch(req).then(function(res){
      var copy = res.clone();
      caches.open(CACHE).then(function(c){ c.put(req, copy); }).catch(function(){});
      return res;
    }).catch(function(){
      return caches.match(req).then(function(hit){
        return hit || caches.match('./index.html');
      });
    })
  );
});
