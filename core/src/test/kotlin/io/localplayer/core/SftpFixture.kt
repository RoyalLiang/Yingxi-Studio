package io.localplayer.core

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.nio.file.Path

/** Foreground loopback fixture for emulator validation, never a shipped server. */
object SftpFixture {
    @JvmStatic fun main(args:Array<String>){
        val root=Path.of(requireNotNull(System.getenv("PLAYER_FIXTURE_ROOT")))
        val server=SshServer.setUpDefaultServer().apply{
            host="127.0.0.1";port=System.getenv("PLAYER_SFTP_FIXTURE_PORT")?.toInt()?:2222;keyPairProvider=SimpleGeneratorHostKeyProvider(root.resolve("server-key"))
            passwordAuthenticator=org.apache.sshd.server.auth.password.PasswordAuthenticator{u,p,_->u==(System.getenv("PLAYER_TEST_USERNAME")?:"test-user")&&p==requireNotNull(System.getenv("PLAYER_TEST_PASSWORD"))}
            subsystemFactories=listOf(SftpSubsystemFactory.Builder().build());fileSystemFactory=VirtualFileSystemFactory(root)
        }
        server.start();println("SFTP fixture ready on loopback port 2222")
        Runtime.getRuntime().addShutdownHook(Thread{server.stop(true)})
        java.util.concurrent.CountDownLatch(1).await()
    }
}
