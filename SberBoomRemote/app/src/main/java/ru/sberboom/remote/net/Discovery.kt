package ru.sberboom.remote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import ru.sberboom.remote.platform.DiscoveryService
import ru.sberboom.remote.protocol.Proto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume


/**
 * Поиск колонок в локальной сети:
 *  1) mDNS (Zeroconf) сервис `_staros._tcp` — родной анонс колонки;
 *  2) TCP-скан порта 20000 по текущей подсети — работает даже при включённой
 *     клиентской изоляцией Wi-Fi, когда multicast не доходит (см. research/PROTOCOL.md).
 */
class Discovery(private val context: Context) : DiscoveryService {

    override suspend fun discover(timeoutMs: Long): List<FoundSpeaker> = coroutineScope {
        val nsd = async { runCatching { nsdScan(timeoutMs) }.getOrDefault(emptyList()) }
        val lan = async { runCatching { lanScan(Proto.DEFAULT_PORT) }.getOrDefault(emptyList()) }
        (nsd.await() + lan.await()).distinctBy { it.host }.sortedBy { it.name ?: it.host }
    }

    // ───────────────────────────── mDNS ─────────────────────────────

    private suspend fun nsdScan(timeoutMs: Long): List<FoundSpeaker> = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        val results = Collections.synchronizedList(mutableListOf<FoundSpeaker>())
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        // mDNS идёт мультикастом: без MulticastLock часть прошивок его не получает.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val multicastLock = runCatching {
            wifi?.createMulticastLock("sboom-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        fun resolveNext() {
            if (!resolving.compareAndSet(false, true)) return
            val next = queue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            runCatching {
                manager.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        if (host != null) {
                            // Колонка публикует в TXT: name (модель словами), type
                            // (платформа, напр. sberboom-mini), id (серийник), v (прошивка).
                            val attrs = runCatching { info.attributes }.getOrNull() ?: emptyMap()
                            fun attr(key: String): String? = attrs[key]
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() }

                            results.add(
                                FoundSpeaker(
                                    host = host,
                                    port = info.port,
                                    name = attr("name") ?: info.serviceName.ifEmpty { null },
                                    model = attr("type"),
                                    firmware = attr("v"),
                                    via = "mDNS",
                                ),
                            )
                        }
                        resolving.set(false)
                        resolveNext()
                    }
                })
            }.onFailure {
                resolving.set(false)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.add(serviceInfo)
                resolveNext()
            }
        }

        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.release() }
            if (!cont.isCompleted) cont.resume(results.toList())
        }, timeoutMs)

        cont.invokeOnCancellation {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.release() }
        }
    }

    // ───────────────────────────── TCP-скан ─────────────────────────────

    private suspend fun lanScan(port: Int): List<FoundSpeaker> = withContext(Dispatchers.IO) {
        val found = Collections.synchronizedList(mutableListOf<FoundSpeaker>())
        val semaphore = Semaphore(48)
        coroutineScope {
            for (prefix in localPrefixes()) {
                for (i in 1..254) {
                    launch {
                        semaphore.withPermit {
                            val ip = "$prefix.$i"
                            if (portOpen(ip, port, 300)) {
                                found.add(FoundSpeaker(ip, port, null, "scan"))
                            }
                        }
                    }
                }
            }
        }
        found.toList()
    }

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun localPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                        val parts = addr.hostAddress?.split(".") ?: continue
                        if (parts.size == 4) out.add(parts.take(3).joinToString("."))
                    }
                }
            }
        }
        return out.toList()
    }

    companion object {
        private const val SERVICE_TYPE = "_staros._tcp"
    }
}
