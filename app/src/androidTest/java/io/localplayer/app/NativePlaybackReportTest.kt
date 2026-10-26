package io.localplayer.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativePlaybackReportTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun script(view:WebView,text:String):String{
        val done=CountDownLatch(1);var result=""
        main{view.evaluateJavascript(text){result=it;done.countDown()}}
        assertTrue(done.await(3,TimeUnit.SECONDS));return result
    }
    private fun request(url:String,main:Boolean=true)=object:WebResourceRequest{
        override fun getUrl()=Uri.parse(url)
        override fun isForMainFrame()=main
        override fun isRedirect()=false
        override fun hasGesture()=false
        override fun getMethod()="GET"
        override fun getRequestHeaders()=emptyMap<String,String>()
    }
    @Test fun appHandsAConsentedSnapshotToItsTrustedPageExactlyOnceWithoutLocalFileAccess(){
        val payload=playbackReportRequest("""{"report":"yingxi-playback-v1","version":"1.2.3","model":"synthetic <\/script> ' \u2028 test"}""")
        val errors=mutableListOf<String>();val receipts=mutableListOf<Long>()
        val view=main{playbackReportWebView(ui.activity,payload,{receipts+=it}){errors+=it}}
        try{
            main{
                assertFalse(view.settings.allowFileAccess);assertFalse(view.settings.allowContentAccess)
                assertTrue(view.settings.javaScriptEnabled);assertTrue(view.settings.domStorageEnabled)
                view.loadDataWithBaseURL(PLAYBACK_REPORT_SUBMIT_PAGE,"<html><body><script>window.calls=0;window.startPlaybackReport=function(value){window.calls++;window.fixtureReceived=value;};</script></body></html>","text/html","UTF-8",PLAYBACK_REPORT_SUBMIT_PAGE)
            }
            ui.waitUntil(8000){script(view,"String(window.calls)")=="\"1\""}
            val text=JSONTokener(script(view,"JSON.stringify(window.fixtureReceived)")).nextValue() as String
            val received=JSONObject(text)
            assertEquals(payload.getString("id"),received.getString("id"))
            assertTrue(received.getBoolean("consent"));assertEquals(PLAYBACK_REPORT_CONSENT,received.getString("consentVersion"))
            assertEquals(payload.getJSONObject("report").getString("model"),received.getJSONObject("report").getString("model"))
            main{view.webViewClient.onPageFinished(view,PLAYBACK_REPORT_SUBMIT_PAGE)}
            assertEquals("\"1\"",script(view,"String(window.calls)"));assertTrue(errors.isEmpty())
            main{
                view.webViewClient.shouldOverrideUrlLoading(view,request("yingxi-report://receipt?id=other&number=37"))
                assertTrue(receipts.isEmpty())
                val receipt=request("yingxi-report://receipt?id="+payload.getString("id")+"&number=37")
                assertTrue(view.webViewClient.shouldOverrideUrlLoading(view,receipt))
                assertTrue(view.webViewClient.shouldOverrideUrlLoading(view,receipt))
                assertEquals(listOf(37L),receipts)
            }
        }finally{main{view.destroy()}}
    }
    @Test fun onlyTheExactHttpsReportPageCanReceiveMainFrameNavigation(){
        val view=main{playbackReportWebView(ui.activity,playbackReportRequest("""{"report":"yingxi-playback-v1","version":"1.2.3"}""")){}}
        try{main{
            assertFalse(view.webViewClient.shouldOverrideUrlLoading(view,request(PLAYBACK_REPORT_SUBMIT_PAGE)))
            for(url in listOf("http://yingxi-station.pages.dev/report/submit.html","https://attacker.invalid/report/submit.html","https://yingxi-station.pages.dev.attacker.invalid/report/submit.html","file:///data/private","content://private","https://yingxi-station.pages.dev/report/")){
                assertTrue(url,view.webViewClient.shouldOverrideUrlLoading(view,request(url)))
            }
            assertFalse(view.webViewClient.shouldOverrideUrlLoading(view,request("https://challenges.cloudflare.com/",false)))
        }}finally{main{view.destroy()}}
    }
    @Test fun interruptedAttemptIsPreservedAndCannotBeSilentlyReplaced(){
        val attempts=PlaybackReportAttempts(ui.activity)
        val first=java.util.UUID.randomUUID().toString();val second=java.util.UUID.randomUUID().toString()
        try{
            assertTrue(attempts.begin(first));assertFalse(attempts.begin(second))
            assertEquals(first,PlaybackReportAttempts(ui.activity).pending())
            assertFalse(attempts.clear(second));assertEquals(first,attempts.pending())
            ui.activity.setContent{PlayerTheme{PlaybackReportDialog(null){}}}
            ui.onNodeWithTag("report-pending").assertIsDisplayed()
            ui.onNodeWithTag("report-continue").assertDoesNotExist()
            ui.onNodeWithTag("report-new-attempt").performScrollTo().performClick()
            ui.onNodeWithTag("report-consent").performScrollTo().assertIsOff()
            ui.onNodeWithTag("report-continue").assertIsNotEnabled()
            assertNull(attempts.pending())
        }finally{attempts.clear(first);attempts.clear(second)}
    }

}
