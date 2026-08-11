package dev.abhinav.artistpin

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.DebugLogger
import coil3.video.VideoFrameDecoder
import com.google.android.libraries.places.api.Places
import dev.abhinav.artistpin.core.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class ArtistPinApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Without a key Places stays uninitialized and the Where section falls back to manual
        // pin entry, rather than the SDK throwing on first use.
        if (BuildConfig.MAPS_API_KEY.isNotBlank() && !Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(this, BuildConfig.MAPS_API_KEY)
        }
        startKoin {
            androidLogger()
            androidContext(this@ArtistPinApplication)
            modules(appModules)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Videos in a reel need a decodable first frame for their thumbnail.
                add(VideoFrameDecoder.Factory())
                // Coil 3 has no network support built in — without this, remote artist
                // portraits fail silently while local file media keeps working.
                add(OkHttpNetworkFetcherFactory())
            }
            .crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
