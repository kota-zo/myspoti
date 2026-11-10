// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
package app.myspoti.host.android

/**
 * The page's view of its own browser, written before Spotify's scripts run.
 *
 * The WebView is told what browser it is ([android.webkit.WebSettings.setUserAgentString] plus, where
 * the WebView supports it, the client hints), and that is the whole story on a current WebView. On an
 * Android 6/7 WebView it is not: the client-hint API is missing, so the page's `navigator.userAgentData`
 * — and the `Sec-CH-UA` headers — still announce **Android WebView** while the user agent says
 * desktop Chrome, and `navigator.platform` says `Linux armv8l` next to an `x86_64` user agent. Spotify's
 * web player refuses a browser it cannot recognise in exactly this shape ("this browser is not
 * supported"), which is what a TV was getting.
 *
 * The wrappers that work on those devices (SpotiDuck / Spotifuck) do the same thing from the page's
 * side: a desktop user agent **and** a `navigator` that agrees with it, installed at document start.
 * This install's own [Fingerprint] supplies the values, so user agent, brands and hints stay the same
 * one browser.
 */
internal object DesktopSpoof {

    /** The script [fingerprint] describes, run at document start (or as early as a WebView allows). */
    fun script(fingerprint: Fingerprint): String {
        val ua = fingerprint.userAgent
        val appVersion = ua.removePrefix("Mozilla/")
        val brands = fingerprint.brands.joinToString(",") { brand ->
            """{"brand":"${js(brand.name)}","version":"${js(brand.major)}"}"""
        }
        val fullBrands = fingerprint.brands.joinToString(",") { brand ->
            """{"brand":"${js(brand.name)}","version":"${js(brand.fullVersion)}"}"""
        }
        return SCRIPT
            .replace("__UA__", js(ua))
            .replace("__APP_VERSION__", js(appVersion))
            .replace("__FULL_VERSION__", js(fingerprint.fullVersion))
            .replace("__PLATFORM_VERSION__", js(fingerprint.kernel))
            .replace("__BRANDS__", brands)
            .replace("__FULL_BRANDS__", fullBrands)
    }

    /** A JS single-quoted string literal body: no quotes, backslashes or line breaks get through. */
    private fun js(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "")
        .replace("\r", "")

    private val SCRIPT = """(function(){try{
if(window.__myspotiDesktop)return;window.__myspotiDesktop=true;
var UA='__UA__',APP='__APP_VERSION__',FULL='__FULL_VERSION__',PV='__PLATFORM_VERSION__';
var B=__BRANDS__,FB=__FULL_BRANDS__;
function def(o,n,g){try{Object.defineProperty(o,n,{get:g,configurable:true});}catch(e){}}
try{
if(navigator.userAgent!==UA)def(navigator,'userAgent',function(){return UA;});
def(navigator,'appVersion',function(){return APP;});
def(navigator,'appName',function(){return 'Netscape';});
def(navigator,'appCodeName',function(){return 'Mozilla';});
def(navigator,'product',function(){return 'Gecko';});
def(navigator,'productSub',function(){return '20030107';});
def(navigator,'vendor',function(){return 'Google Inc.';});
def(navigator,'vendorSub',function(){return '';});
def(navigator,'platform',function(){return 'Linux x86_64';});
def(navigator,'oscpu',function(){return 'Linux x86_64';});
def(navigator,'webdriver',function(){return false;});
def(navigator,'maxTouchPoints',function(){return 0;});
}catch(e){}
try{
var d={brands:B,mobile:false,platform:'Linux',platformVersion:PV,architecture:'x86',bitness:'64',wow64:false,model:'',uaFullVersion:FULL,fullVersionList:FB};
d.getHighEntropyValues=function(hints){
    return new Promise(function(done){
        var out={};
        try{
            for(var i=0;i<(hints||[]).length;i++){var h=hints[i];if(d[h]!==undefined)out[h]=d[h];}
            out.brands=d.brands;out.fullVersionList=d.fullVersionList;out.uaFullVersion=d.uaFullVersion;
        }catch(e){}
        done(out);
    });
};
d.toJSON=function(){return {brands:B,mobile:false,platform:'Linux'};};
def(navigator,'userAgentData',function(){return d;});
}catch(e){}
try{
if(!window.chrome){
    Object.defineProperty(window,'chrome',{configurable:true,writable:true,value:{
        runtime:{id:undefined,connect:function(){},sendMessage:function(){}},
        app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'}},
        loadTimes:function(){return {};},
        csi:function(){return {};},
        webstore:undefined
    }});
}
}catch(e){}
}catch(e){}})()"""
}
