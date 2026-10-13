package com.ensemble.scorelibrary.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import com.ensemble.scorelibrary.ui.DeviceAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class AndroidDeviceAvailability @Inject constructor(@param:ApplicationContext private val context: Context) : DeviceAvailability {
    override val online = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun update() {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            trySend(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
        }
        manager.registerDefaultNetworkCallback(callback)
        update()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override suspend fun filesAvailable(segments: List<ResolvedScoreSegment>) = withContext(Dispatchers.IO) {
        val root = context.filesDir.canonicalFile
        fun readable(path: String): Boolean {
            val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
            return file.path.startsWith(root.path + File.separator) && file.isFile && file.canRead()
        }
        segments.isNotEmpty() && segments.all { segment ->
            readable(segment.managedFilePath) ||
                (segment.cacheStatus == CacheStatus.CACHED && segment.cachedFilePath?.let(::readable) == true)
        }
    }
}
