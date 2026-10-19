package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** Environment supplied by the test runner; never compiled with local credentials. */
object TestFixtures {
    private val args get()=InstrumentationRegistry.getArguments()
    val password:String get(){val value=args.getString("fixture_password");assumeTrue("Explicit loopback fixture password required",!value.isNullOrBlank());return value!!}
    val username:String get()=args.getString("fixture_username")?:"test-user"
    val smbPort:Int get()=args.getString("fixture_smb_port")?.toInt()?:1445
    val sftpPort:Int get()=args.getString("fixture_sftp_port")?.toInt()?:2222
}
