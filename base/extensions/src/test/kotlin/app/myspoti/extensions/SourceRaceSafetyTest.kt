package app.myspoti.extensions
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
class SourceRaceSafetyTest {
 private fun run(script:String, limit:Int=120):Pair<String?,Int> {
  val count=AtomicInteger()
  val m=Manifest("io.test.safe_race","Race","1",author="Test",provides=listOf("download-source"),permissions=listOf(PermissionRequest("download.source"),PermissionRequest("network",listOf("example.org"))))
  val host=object:Host {
   val store=MemoryStorage()
   override fun storage(extensionId:String)=store
   override fun setting(extensionId:String,key:String):String?=null
   override fun log(extensionId:String,message:String) {}
   override fun http(extensionId:String,request:HttpRequest):HttpResponse { count.incrementAndGet();return HttpResponse(200,emptyMap(),"{}") }
  }
  val result=ExtensionRuntime(m,script,host,m.asks().map {it.key}.toSet(),ExtensionRuntime.Limits(requestsPerMinute=limit)).use {
   it.start();it.call(Hook.DOWNLOAD_SOURCE,"""{"quality":"cd"}""",5000)
  }
  return result to count.get()
 }
 @Test fun lanesShareOneHttpQuota() {
  val (out,count)=run("""
   myspoti.provide('download-source',function(t){
    if(!t.source)return myspoti.sources.race('download-source',t,['a','b','c']);
    myspoti.http.get('https://example.org/'+t.source);
    return [{url:'https://example.org/a.flac',format:'flac'}];
   });
  """,1)
  assertNotNull(out);assertEquals(1,count)
 }
 @Test fun nestedRacesCannotMultiplyWorkers() {
  val (out,count)=run("""
   myspoti.provide('download-source',function(t){return myspoti.sources.race('download-source',t,['a','b']);});
  """)
  assertNull(out);assertEquals(0,count)
 }
}
