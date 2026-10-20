package io.localplayer.app.ypauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.localplayer.app.AliyunCloud

/** The class name is part of Aliyunpan's registered Android native callback contract. */
class YPAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); receive(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(value: Intent) {
        if (!AliyunCloud.callback(value)) Toast.makeText(this, "授权会话不存在或已过期，请回到来源重新授权", Toast.LENGTH_LONG).show()
        finish()
    }
}
