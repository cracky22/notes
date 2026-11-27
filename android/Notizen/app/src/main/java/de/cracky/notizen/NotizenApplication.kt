package de.cracky.notizen

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import de.cracky.notizen.data.NotesRepository

class NotizenApplication : Application(), ImageLoaderFactory {
    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NotesRepository(this)
        // Netz wieder da → WebSocket sofort neu verbinden statt auf den Backoff zu warten
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = repository.realtime.kick()
            })
        }
    }

    /** Bilder (Thumbnails, PDF-Seiten, Avatare) laufen über denselben OkHttp-Client inkl. Sitzungs-Cookie. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { repository.api.http }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
