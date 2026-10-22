package io.localplayer.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

data class AliyunReadOptions(val workers: Int = 2, val cache: SegmentCache? = null) {
    init { require(workers in 1..3) }
}

/** Bounded lookahead with partial delivery: the consumer never waits for a complete segment. */
internal class SegmentedRemoteFile(
    override val size: Long, parent: Cancellation, private val options: AliyunReadOptions,
    private val identity: String, override val readTelemetry: RemoteReadTelemetry,
    override val reusableCache:Boolean = true,
    private val open: (Long, Long, Cancellation) -> RemoteFile
) : RemoteFile {
    private val cancellation = Cancellation().also(parent::own)
    private val parentCancellation = parent
    private val closed = AtomicBoolean(false)
    private val state = Any()
    private val reader = Any()
    private val executor = Executors.newFixedThreadPool(options.workers) { task ->
        Thread(task, "cloud-segment").apply { isDaemon = true }
    }
    private val slots = LinkedHashMap<Long, Slot>()
    private var previousEnd = -1L
    private val ownership = java.io.Closeable { close() }.also(cancellation::own)

    private class Slot(val start: Long, val length: Int, val cancellation: Cancellation,
                       val alignedStart:Long=start,val alignedLength:Int=length) {
        val monitor = Object()
        val bytes = ByteArray(length)
        var available = 0
        var finished = false
        var error: IOException? = null
        var future: Future<*>? = null
        fun cancel() {
            cancellation.close(); future?.cancel(true)
            synchronized(monitor) { monitor.notifyAll() }
        }
    }

    init { require(size >= 0); readTelemetry.parallelism(options.workers) }

    private fun segment(position: Long): Pair<Long, Int> {
        val mib = 1024L * 1024
        val base = when { position < 8*mib -> 0L; position < 64*mib -> 8*mib; else -> 64*mib }
        val unit = when { position < 8*mib -> mib; position < 64*mib -> 4*mib; else -> 8*mib }
        val start = base + (position-base)/unit*unit
        return start to minOf(unit, size-start).toInt()
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(reader) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size-length)
        cancellation.check()
        if (length == 0) return@synchronized 0
        if (position >= size) return@synchronized -1
        val slot: Slot
        val stale: List<Slot>
        synchronized(state) {
            cancellation.check()
            val (alignedStart, alignedCount) = segment(position)
            val candidate=slots.values.firstOrNull{position>=it.start&&position<it.start+it.length}
            val ready=candidate?.let{synchronized(it.monitor){it.available>position-it.start}}==true
            // A seek discards speculative work instead of queuing behind the old position.
            val seek = previousEnd >= 0 && position != previousEnd && !ready
            stale = if (seek) slots.values.toList().also { slots.clear() } else emptyList()
            slot = if(!seek&&candidate!=null)candidate else {
                // A cold request starts at the requested byte, never at an unused window prefix.
                Slot(position,(alignedStart+alignedCount-position).toInt(),Cancellation().also(cancellation::own),alignedStart,alignedCount)
                    .also{slots[it.start]=it}
            }
        }
        stale.forEach { it.cancel(); cancellation.release(it.cancellation) }
        schedule(slot)
        synchronized(state) {
            var next = slot.start + slot.length
            repeat(options.workers-1) {
                if (next < size) {
                    val (start, count) = segment(next)
                    val lookahead = slots.getOrPut(start) { Slot(start,count,Cancellation().also(cancellation::own)) }
                    schedule(lookahead)
                    next = start + count
                }
            }
            // Only the current segment and bounded lookahead remain hot in memory.
            val keep = slots.values.filter { it.start < slot.start || it.start >= next }.toList()
            keep.forEach { slots.remove(it.start) }
            keep.forEach { it.cancel(); cancellation.release(it.cancellation) }
        }
        val from = (position-slot.start).toInt()
        synchronized(slot.monitor) {
            while (slot.available <= from && !slot.finished) {
                cancellation.check(); slot.cancellation.check()
                try { slot.monitor.wait(50) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("读取已取消") }
            }
            cancellation.check(); slot.cancellation.check()
            if (slot.available <= from) throw slot.error ?: IOException("视频分段提前结束")
            val count = minOf(length,slot.available-from)
            slot.bytes.copyInto(buffer,offset,from,from+count)
            previousEnd = position+count
            count
        }
    }

    private fun schedule(slot: Slot) = synchronized(slot.monitor) {
        if (slot.future != null || slot.finished || closed.get()) return@synchronized
        slot.future = executor.submit { fill(slot) }
    }

    private fun fill(slot: Slot) {
        try {
            slot.cancellation.check()
            var cached = options.cache?.get(identity,slot.start,slot.length)
            var cacheBytes=cached?.size?:0
            if(cached==null&&slot.start!=slot.alignedStart){
                val full=options.cache?.get(identity,slot.alignedStart,slot.alignedLength)
                if(full!=null){cacheBytes=full.size;cached=full.copyOfRange((slot.start-slot.alignedStart).toInt(),full.size)}
            }
            if (cached != null) {
                synchronized(slot.monitor) { cached.copyInto(slot.bytes); slot.available=cached.size; slot.monitor.notifyAll() }
                readTelemetry.cacheHit(cacheBytes)
                return
            }
            var attempts = 0
            while (slot.available < slot.length) {
                slot.cancellation.check()
                try {
                    open(slot.start+slot.available,slot.start+slot.length,slot.cancellation).use { file ->
                        while (slot.available < slot.length) {
                            slot.cancellation.check()
                            val at = slot.available
                            val count = file.readAt(slot.start+at,slot.bytes,at,minOf(64*1024,slot.length-at))
                            if (count <= 0 || count > slot.length-at) throw IOException("视频分段提前结束")
                            synchronized(slot.monitor) { slot.available=at+count; slot.monitor.notifyAll() }
                        }
                    }
                } catch (error: IOException) {
                    slot.cancellation.check()
                    if (error is AliyunRangeResponseFailure && !error.retryable || error is AliyunUnauthorized || ++attempts > 2) throw error
                    // Resume the missing suffix; neither duplicate nor skip a delivered byte.
                    try { Thread.sleep(100L*attempts) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("读取已取消") }
                }
            }
            // A complete segment remains valid after a seek cancels further lookahead.
            options.cache?.put(identity,slot.start,slot.bytes)
        } catch (error: Exception) {
            synchronized(slot.monitor) { slot.error = if(error is IOException)error else IOException("无法读取视频分段",error) }
        } finally {
            synchronized(slot.monitor) { slot.finished=true; slot.monitor.notifyAll() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        cancellation.release(ownership)
        cancellation.close()
        val copy = synchronized(state) { slots.values.toList().also { slots.clear() } }
        copy.forEach { it.cancel() }
        executor.shutdownNow()
        parentCancellation.release(cancellation)
    }
}
