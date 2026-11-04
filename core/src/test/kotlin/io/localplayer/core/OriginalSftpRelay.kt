package io.localplayer.core

import com.google.gson.JsonParser
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/** Opt-in loopback fixture: one original file, bounded verified cache, no media transcode. */
object OriginalSftpRelay {
    @JvmStatic fun main(args:Array<String>) {
        val privateDir=Path.of(requireNotNull(System.getenv("PLAYER_ORIGINAL_PRIVATE_DIR")))
        val fixture=JsonParser.parseString(Files.readString(privateDir.resolve("android-fixture.private.json"))).asJsonObject
        val auth=JsonParser.parseString(Files.readString(privateDir.resolve("relay-auth.private.json"))).asJsonObject
        val credentialFile=privateDir.resolve("credentials.private.json")
        var credentials=JsonParser.parseString(Files.readString(credentialFile)).asJsonObject
        val api=AliyunApi();val cancellation=Cancellation()
        val access=object:AliyunAccess {
            @Synchronized override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T {
                cancellation.check()
                if(credentials.get("expiresAt").asLong-System.currentTimeMillis()<60_000){
                    val token=api.refresh(credentials.get("refresh").asString,cancellation)
                    credentials.addProperty("access",token.access);credentials.addProperty("refresh",token.refresh?:credentials.get("refresh").asString);credentials.addProperty("expiresAt",token.expiresAt)
                    Files.writeString(credentialFile,credentials.toString())
                }
                return action(credentials.get("access").asString)
            }
        }
        val source=Source(id="original-loopback-fixture",label="Original stream fixture",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain=fixture.get("driveId").asString)
        val cache=SegmentCache(privateDir.resolve("original-byte-cache").toFile(),256L*1024*1024)
        val session=AliyunConnector(api,access,readOptions={AliyunReadOptions(3,cache)}).connect(source,Credentials(""),cancellation)
        val fileId=fixture.get("fileId").asString;val size=fixture.get("size").asLong
        val served=AtomicLong();val root=privateDir.resolve("sftp-root");Files.createDirectories(root)
        val placeholder=root.resolve("original.mkv")
        RandomAccessFile(placeholder.toFile(),"rw").use{it.setLength(size)}
        val server=SshServer.setUpDefaultServer().apply{
            host="127.0.0.1";port=auth.get("port").asInt
            keyPairProvider=SimpleGeneratorHostKeyProvider(privateDir.resolve("sftp-host-key"))
            passwordAuthenticator=org.apache.sshd.server.auth.password.PasswordAuthenticator{user,password,_->user==auth.get("username").asString&&password==auth.get("password").asString}
            fileSystemFactory=VirtualFileSystemFactory(root)
            subsystemFactories=listOf(SftpSubsystemFactory.Builder().withFileSystemAccessor(object:SftpFileSystemAccessor {
                override fun openFile(subsystem:SftpSubsystemProxy,handle:FileHandle,file:Path,rawHandle:String,options:Set<OpenOption>,vararg attrs:FileAttribute<*>):SeekableByteChannel {
                    if(file.fileName.toString()!="original.mkv"||options.any{it!=StandardOpenOption.READ})throw NonWritableChannelException()
                    val remote=session.open(fileId,0);check(remote.size==size&&remote.reusableCache)
                    return object:SeekableByteChannel {
                        private var cursor=0L;private var open=true
                        @Synchronized override fun read(dst:ByteBuffer):Int {
                            check(open);if(cursor>=size)return -1
                            val bytes=ByteArray(minOf(dst.remaining(),64*1024));val count=remote.readAt(cursor,bytes,0,bytes.size)
                            if(count>0){dst.put(bytes,0,count);cursor+=count;served.addAndGet(count.toLong())}
                            return count
                        }
                        override fun write(src:ByteBuffer):Int=throw NonWritableChannelException()
                        @Synchronized override fun position()=cursor
                        @Synchronized override fun position(value:Long):SeekableByteChannel {require(value>=0);cursor=value;return this}
                        override fun size()=size
                        override fun truncate(value:Long):SeekableByteChannel=throw NonWritableChannelException()
                        override fun isOpen()=open
                        override fun close(){open=false;remote.close()}
                    }
                }
            }).build())
        }
        server.start()
        println("ORIGINAL_SFTP_READY port=${server.port} size=$size loopbackOnly=true")
        Runtime.getRuntime().addShutdownHook(Thread{cancellation.close();runCatching{server.stop(true)};runCatching{session.close()};println("ORIGINAL_SFTP_STOP servedBytes=${served.get()}")})
        Thread({
            try {
                val mib=1024L*1024
                val scene=64*mib+(10_241_000_000L-64*mib)/(8*mib)*(8*mib)
                val tail=64*mib+(size-1-64*mib)/(8*mib)*(8*mib)
                val windows=listOf(0L to 8*mib,tail to size,scene to minOf(scene+160*mib,size))
                for((from,end) in windows){
                    session.open(fileId,from).use{remote->
                        check(remote.reusableCache);var at=from;var next=from;val buffer=ByteArray(64*1024)
                        while(at<end){val n=remote.readAt(at,buffer,0,minOf(buffer.size.toLong(),end-at).toInt());if(n<=0)throw IOException("Original warm window ended early");at+=n
                            if(at>=next){println("ORIGINAL_WARM position=$at windowEnd=$end");next=at+8*mib}
                        }
                    }
                }
                Files.writeString(privateDir.resolve("warm-complete.json"),"{\"complete\":true,\"bytes\":${windows.sumOf{it.second-it.first}},\"fullMovieDownloaded\":false}")
                println("ORIGINAL_WARM_COMPLETE bytes=${windows.sumOf{it.second-it.first}}")
            }catch(error:Throwable){println("ORIGINAL_WARM_FAILED class=${error.javaClass.simpleName}")}
        },"original-window-warm").apply{isDaemon=true;start()}
        CountDownLatch(1).await()
    }
}
