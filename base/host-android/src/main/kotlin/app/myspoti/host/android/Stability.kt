// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package app.myspoti.host.android

/**
 * What the player page is allowed to cost the device it runs on.
 *
 * The web player is built for a laptop browser on a fast CPU with the tab in front. On an Android TV
 * WebView it runs the same React page, its own cache bookkeeping, its analytics and its animations
 * while the app draws its own screen on top — and the app then turns the page into a 1x1 view nobody
 * sees ([WebPlayerHost.attach]). What is left is pure cost: the page keeps animating at 60 frames a
 * second, keeps polling four times a second, and keeps a service worker recording every request. On a
 * TV that is the lag the user feels, and the audio starves while the CPU catches up: songs start late,
 * stall, and the player moves on to the next one.
 *
 * The working TV wrapper (SpotiDuck / Spotifuck) patches exactly this in its `desktop_spoof.js`, and
 * has done for releases. The three patches are taken from it, one to one:
 *
 * - **service worker off** — Spotify's worker intercepts every request to keep an offline cache map;
 *   on an old WebView that bookkeeping is what freezes the page and stalls the player. Blocked, the
 *   page loads over the WebView's ordinary HTTP cache.
 * - **quarter-second polling halved** — the page re-reads itself every 250 ms to keep its UI in step.
 *   Nobody sees that UI while the app draws its own; 500 ms is the same behaviour at half the wake-ups.
 * - **analytics made a no-op** — `window.ga` answers its callbacks without loading anything.
 *
 * The fourth is this app's own: **frames nobody sees**. The page's `requestAnimationFrame` loop runs at
 * 60 Hz for a page that is 1x1 and invisible. It is throttled to about ten frames a second while the
 * app's own screen covers the page ([quietJs]), and put back the moment the page is shown — the sign-in
 * page, the log-in-on-this-device step, or the debug window that shows the page itself.
 */
internal object Stability {

    /** How often the page hears an animation frame while it is covered: ten a second. */
    private const val QUIET_FRAME_MS = 100

    /**
     * The patches, run at document start (before the page's own code) and again on page start/finish
     * for WebViews that cannot run a document-start script. Every part is guarded: a patch that
     * cannot be applied leaves the rest of the script, and the page, working.
     */
    val SCRIPT: String = """(function(){try{
if(window.__myspotiStable)return;window.__myspotiStable=1;
try{
if(navigator.serviceWorker){
    try{Object.defineProperty(navigator.serviceWorker,'register',{configurable:true,writable:true,value:function(){return Promise.reject(new Error('service worker off'))}});}catch(e){}
    try{navigator.serviceWorker.getRegistrations().then(function(rs){for(var i=0;i<rs.length;i++){try{rs[i].unregister();}catch(e){}}}).catch(function(){});}catch(e){}
}
}catch(e){}
try{
var si=window.setInterval.bind(window);
window.setInterval=function(fn,d){var more=Array.prototype.slice.call(arguments,2);if(d===250)d=500;return si.apply(null,[fn,d].concat(more));};
}catch(e){}
try{
function put(o,n,v){
    try{Object.defineProperty(o,n,{configurable:true,writable:true,value:v});}catch(e){try{o[n]=v;}catch(e2){}}
}
var raf=window.requestAnimationFrame.bind(window),caf=window.cancelAnimationFrame.bind(window);
var quiet=false,ids=0,pending={},pump=0;
function flush(){
    pump=0;
    var job=pending;pending={};
    var t=(window.performance&&performance.now)?performance.now():Date.now();
    for(var k in job){try{job[k](t);}catch(e){}}
}
function setQuiet(on){
    quiet=!!on;
    if(!quiet&&pump){clearTimeout(pump);flush();}
}
put(window,'requestAnimationFrame',function(cb){
    if(typeof cb!=='function')throw new TypeError('a callback is needed');
    if(!quiet)return raf(cb);
    var id=-(++ids);
    pending[id]=cb;
    if(!pump)pump=setTimeout(flush,__QUIET_FRAME_MS__);
    return id;
});
put(window,'cancelAnimationFrame',function(id){
    if(typeof id==='number'&&id<0){delete pending[id];return;}
    return caf(id);
});
put(window,'__myspotiQuiet',setQuiet);
}catch(e){}
try{
var noop=function(){};
function Tracker(){}
Tracker.prototype.get=noop;Tracker.prototype.set=noop;Tracker.prototype.send=noop;
var ga=function(){
    var args=arguments,l=args.length;
    if(!l)return;
    var last=args[l-1];
    try{
        if(last&&typeof last==='object'&&typeof last.hitCallback==='function')last.hitCallback();
        else if(typeof last==='function')last(new Tracker());
    }catch(e){}
};
ga.create=function(){return new Tracker();};
ga.getByName=function(){return new Tracker();};
ga.getAll=function(){return [new Tracker()];};
ga.remove=noop;ga.loaded=true;
window.ga=ga;window.GoogleAnalyticsObject='ga';
}catch(e){}
}catch(e){}})()"""
        .replace("__QUIET_FRAME_MS__", QUIET_FRAME_MS.toString())

    /**
     * Tells a loaded page whether its frames are worth drawing: [on] while the app's own screen
     * covers it, off when the page is the thing on screen. A page that has not run [SCRIPT] yet
     * ignores it.
     */
    fun quietJs(on: Boolean): String =
        "try{if(window.__myspotiQuiet)window.__myspotiQuiet($on)}catch(e){}"
}
