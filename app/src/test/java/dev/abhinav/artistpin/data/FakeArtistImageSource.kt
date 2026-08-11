package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.ArtistProfile

class FakeArtistImageSource(
    private val profiles: Map<String, ArtistProfile> = emptyMap(),
) : ArtistImageSource {
    val lookups = mutableListOf<String>()

    override suspend fun profileFor(artistName: String): ArtistProfile {
        lookups += artistName
        return profiles[artistName] ?: ArtistProfile()
    }

    companion object {
        /** Convenience for the common case of only caring about the portrait. */
        fun withImages(urls: Map<String, String>) = FakeArtistImageSource(
            urls.mapValues { (_, url) -> ArtistProfile(imageUrl = url) },
        )
    }
}
