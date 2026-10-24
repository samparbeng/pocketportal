package app.pocketportal.quest

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

data class Phone(val name: String, val host: String, val port: Int) {
    val url: String get() = "http://$host:$port/"
}

/**
 * Lists phones that are sharing their screen on this Wi-Fi. The phone app
 * advertises itself over mDNS while sharing; the PIN is never part of it.
 */
class PhoneFinder(context: Context) {

    private companion object {
        const val TAG = "PhoneFinder"
        /** Must match MirrorState.SERVICE_TYPE in the phone app. */
        const val SERVICE_TYPE = "_pocketportal._tcp."
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("pocketportal-discovery").apply { setReferenceCounted(false) }
    private val main = Handler(Looper.getMainLooper())

    private val _phones = MutableStateFlow<List<Phone>>(emptyList())
    val phones: StateFlow<List<Phone>> = _phones

    private val found = linkedMapOf<String, Phone>()
    // NsdManager.resolveService handles one request at a time, so queue them.
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovering = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery failed: $errorCode")
            discovering = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post { pending.addLast(info); resolveNext() }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post {
                if (found.remove(info.serviceName) != null) publish()
            }
        }
    }

    fun start() {
        if (discovering) return
        discovering = true
        multicastLock.acquire()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!discovering) return
        discovering = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
        multicastLock.release()
        found.clear()
        pending.clear()
        publish()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val host = info.host
                    // The WebView URL needs IPv4; link-local IPv6 would need a zone id.
                    if (host is Inet4Address) {
                        found[info.serviceName] = Phone(info.serviceName, host.hostAddress!!, info.port)
                        publish()
                    }
                    resolveNext()
                }
            }
        })
    }

    private fun publish() {
        _phones.value = found.values.toList()
    }
}
