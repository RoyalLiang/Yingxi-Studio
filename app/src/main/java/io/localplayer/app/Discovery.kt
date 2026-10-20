package io.localplayer.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.localplayer.core.Protocol
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Candidate(val name:String,val host:String,val port:Int,val protocol:Protocol,val hint:String,val secure:Boolean=true,val root:String="") {
    val id get()=protocol.name+":"+host+":"+port+if(protocol==Protocol.WEBDAV)":"+secure+":"+root else ""
}

/** Standard service advertisements only. No fake entries or authentication from discovery callbacks. */
@Suppress("DEPRECATION")
class Discovery(context:Context,private val found:(Candidate)->Unit,private val message:(String)->Unit) : java.io.Closeable {
    private val manager=context.getSystemService(NsdManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val listeners=mutableListOf<NsdManager.DiscoveryListener>()
    private val queue=ArrayDeque<Pair<NsdServiceInfo,DiscoveryKind>>()
    private var resolving=false
    private var active=true
    fun start() {
        DiscoveryRecords.kinds.forEach { kind ->
            val type=kind.type
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType:String){}
                override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int){ main.post{if(active)message("部分服务发现不可用，可手动添加来源（$errorCode）")} }
                override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int){}
                override fun onDiscoveryStopped(serviceType:String){}
                override fun onServiceLost(serviceInfo:NsdServiceInfo){}
                override fun onServiceFound(info:NsdServiceInfo){main.post{if(active){queue.add(info to kind);resolveNext()}}}
            }
            listeners+=listener
            runCatching { manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }.onFailure { message("发现未启动，请检查网络或手动添加") }
        }
    }
    private fun resolveNext() {
        if(!active || resolving || queue.isEmpty())return
        val (info,kind)=queue.removeFirst();resolving=true
        try { manager.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo:NsdServiceInfo,errorCode:Int){main.post{resolving=false;resolveNext()}}
            override fun onServiceResolved(serviceInfo:NsdServiceInfo){main.post{
                if(active) serviceInfo.host?.hostAddress?.let { host -> DiscoveryRecords.candidate(kind,serviceInfo.serviceName,host,serviceInfo.port,serviceInfo.attributes)?.let(found) }
                resolving=false;resolveNext()
            }}
        }) }catch(_:Exception){resolving=false;resolveNext()}
    }
    override fun close(){active=false;queue.clear();listeners.forEach{runCatching{manager.stopServiceDiscovery(it)}};listeners.clear()}
}

/** Explicit opt-in only: at most the current private IPv4 /24, ports 22 and 445, 12s wall budget. */
class LocalProbe(context:Context,private val found:(Candidate)->Unit,private val done:(String)->Unit) : java.io.Closeable {
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val stopped=AtomicBoolean(false)
    private val workers=Executors.newFixedThreadPool(16)
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    fun start(){
        val network=cm.activeNetwork
        val caps=network?.let{cm.getNetworkCapabilities(it)}
        if(caps==null || !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
            done("补充发现仅用于当前 Wi-Fi/以太网，不使用 VPN 或移动网络");close();return
        }
        val address=cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
        if(address==null){done("没有可扫描的本机 IPv4 局域网，请使用服务发现或手动地址");close();return}
        val bytes=address.address.address;val own=bytes[3].toInt() and 255
        val prefix=address.prefixLength.coerceAtLeast(24)
        val mask=(0xff shl (32-prefix)) and 255;val start=own and mask;val end=start+(255 xor mask)
        val deadline=System.nanoTime()+12_000_000_000L
        val remaining=java.util.concurrent.atomic.AtomicInteger((end-start-1).coerceAtLeast(0)*2)
        val main=Handler(Looper.getMainLooper())
        for(last in start+1 until end) for(port in listOf(445,22)) {
            workers.execute {
                try {
                    if(last!=own && !stopped.get() && System.nanoTime()<deadline){
                        val host="${bytes[0].toInt() and 255}.${bytes[1].toInt() and 255}.${bytes[2].toInt() and 255}.$last"
                        val socket=Socket();sockets+=socket
                        try { network.bindSocket(socket);socket.connect(InetSocketAddress(host,port),350)
                            if(!stopped.get())main.post{if(!stopped.get())found(Candidate(host,host,port,if(port==445)Protocol.SMB else Protocol.SFTP,"端口候选 · 协议待验证"))}
                        } finally {sockets-=socket;runCatching{socket.close()}}
                    }
                }catch(_:Exception){}finally{
                    if(remaining.decrementAndGet()==0)main.post{if(!stopped.get()){done("补充发现结束，仅列出端口候选");close()}}
                }
            }
        }
        main.postDelayed({if(!stopped.get()){done("补充发现窗口结束");close()}},12_000)
    }
    override fun close(){if(stopped.compareAndSet(false,true)){sockets.forEach{runCatching{it.close()}};workers.shutdownNow()}}
}
