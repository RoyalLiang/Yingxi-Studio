package io.localplayer.app

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class PlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AliyunCloud.initialize(this)
        // SSHJ uses the ECDSA KeyFactory alias missing from Android's reduced BC.
        // Replace only this process's BC entry with the bundled implementation,
        // at the lowest priority. AndroidKeyStore and default Conscrypt stay first.
        val existing=Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if(existing?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            check(Security.addProvider(BouncyCastleProvider()) > 0) { "无法初始化 SFTP 加密提供者" }
        }
    }
}
