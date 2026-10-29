package ru.sberboom.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.sberboom.remote.net.FoundSpeaker
import ru.sberboom.remote.platform.DiscoveryService
import ru.sberboom.remote.protocol.Proto
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Поиск колонок в локальной сети на Windows:
 *  1) mDNS `_staros._tcp` через JmDNS — имена, модель и прошивка из TXT;
 *  2) TCP-скан своей /24 на порт 20000 — работает и там, где multicast блокирован
 *     (клиентская изоляция Wi-Fi, VPN-адаптеры).
 */
class Discovery : DiscoveryService {

    override suspend fun discover(timeoutMs: Long): List<FoundSpeaker> = coroutineScope {
        val mdns = async { runCatching { mdnsScan(timeoutMs) }.getOrDefault(emptyList()) }
        val lan = async { runCatching { lanScan(Proto.DEFAULT_PORT, timeoutMs) }.getOrDefault(emptyList()) }
        (mdns.await() + lan.await()).distinctBy { it.host }.sortedBy { it.name ?: it.host }
    }

    // ───────────────────────────── mDNS ─────────────────────────────

    private suspend fun mdnsScan(timeoutMs: Long): List<FoundSpeaker> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, FoundSpeaker>()
        val deadline = System.currentTimeMillis() + timeoutMs

        val jmdns = runCatching { JmDNS.create(localAddress(), "sboom-desktop") }.getOrNull()
            ?: return@withContext emptyList()

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                runCatching { jmdns.requestServiceInfo(event.type, event.name, true, 1_000) }
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val addresses = info.inetAddresses ?: return
                val ip = addresses.mapNotNull { it?.hostAddress }.firstOrNull { !it.contains(":") } ?: return
                fun txt(key: String): String? = runCatching { info.getPropertyString(key) }.getOrNull()
                val speaker = FoundSpeaker(
                    host = ip,
                    port = if (info.port > 0) info.port else Proto.DEFAULT_PORT,
                    name = txt("name"),
                    model = txt("type"),
                    firmware = txt("v"),
                    via = "mDNS",
                )
                synchronized(found) { found[ip] = speaker }
            }
        }

        runCatching { jmdns.addServiceListener(SERVICE_TYPE, listener) }
        while (System.currentTimeMillis() < deadline && synchronized(found) { found.isEmpty() }) {
            Thread.sleep(150)
        }
        runCatching { jmdns.removeServiceListener(SERVICE_TYPE, listener) }
        runCatching { jmdns.close() }
        synchronized(found) { found.values.toList() }
    }

    // ───────────────────────────── TCP-скан ─────────────────────────────

    private suspend fun lanScan(port: Int, timeoutMs: Long): List<FoundSpeaker> = coroutineScope {
        val base = localSubnetPrefix() ?: return@coroutineScope emptyList()
        val gate = Semaphore(48)
        val results = java.util.Collections.synchronizedList(mutableListOf<FoundSpeaker>())
        val jobs = (1..254).map { host ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    val ip = "$base.$host"
                    if (probe(ip, port, timeout = 220)) {
                        results.add(FoundSpeaker(host = ip, port = port, via = "TCP"))
                    }
                }
            }
        }
        // Ждём не дольше бюджета: 254 хоста / 48 параллельно * 220 мс ≈ 1.2 c
        withTimeoutOrNull(timeoutMs) { jobs.forEach { it.join() } }
        results.toList()
    }

    private fun probe(ip: String, port: Int, timeout: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
        true
    } catch (_: Exception) {
        false
    }

    private fun localAddress(): InetAddress =
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<InetAddress>()
            .firstOrNull { it.address.size == 4 && it.isSiteLocalAddress && !it.isLinkLocalAddress }
            ?: InetAddress.getByName("127.0.0.1")

    private fun localSubnetPrefix(): String? {
        val addr = localAddress()
        if (addr.address.size != 4) return null
        val bytes = addr.address
        return "${bytes[0].toInt() and 0xff}.${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}"
    }

    private companion object {
        const val SERVICE_TYPE = "_staros._tcp.local."
    }
}

private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
    kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
