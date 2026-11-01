package app.myspoti.extensions

import org.junit.Assert.*
import org.junit.Test

class SourceRaceTest {
    @Test fun firstUsableSourceWinsWithoutWaitingForSlowOne() {
        val m = Manifest("io.test.race", "Race", "1", author="Test", provides=listOf("download-source"),
            permissions=listOf(PermissionRequest("download.source"),PermissionRequest("network",listOf("example.org"))))
        val host = object : Host {
            val store = MemoryStorage()
            override fun storage(extensionId:String)=store
            override fun setting(extensionId:String,key:String):String?=null
            override fun log(extensionId:String,message:String) {}
            override fun http(extensionId:String,request:HttpRequest):HttpResponse {
                when(request.url.substringAfterLast('/')) {
                    "slow" -> Thread.sleep(3000)
                    "fast" -> Thread.sleep(60)
                }
                return HttpResponse(200,emptyMap(),"{}")
            }
        }
        val script = """
          myspoti.provide('download-source', function(t) {
            if (!t.source) return myspoti.sources.race('download-source', t, ['slow','empty','lossy','fast']);
            myspoti.http.get('https://example.org/'+t.source);
            if(t.source==='empty') return [];
            if(t.source==='lossy') return [{url:'https://example.org/a.mp3',format:'mp3'}];
            return [{url:'https://example.org/'+t.source+'.flac',format:'flac',isrcMatched:true}];
          });
        """
        ExtensionRuntime(m,script,host,m.asks().map {it.key}.toSet()).use { rt ->
            rt.start()
            val start=System.nanoTime()
            val files=DownloadFile.parseList(rt.call(Hook.DOWNLOAD_SOURCE,"""{"quality":"cd"}""",8000)!!)
            assertTrue(files.single().url!!.contains("fast.flac"))
            assertTrue("waited for losing source", (System.nanoTime()-start)/1_000_000 < 2500)
        }
    }
}
