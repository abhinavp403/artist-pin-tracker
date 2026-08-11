package dev.abhinav.artistpin.core.model

/** What a lookup can tell us about an artist. Every field is best-effort and may be absent. */
data class ArtistProfile(
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val spotifyUrl: String? = null,
    /**
     * True when the lookup itself failed rather than simply finding nothing. The two must not be
     * conflated: a genuine "this artist has no genres" is cached forever, whereas being offline
     * has to stay retryable or one bad launch would permanently blank everyone.
     */
    val lookupFailed: Boolean = false,
) {
    val isEmpty: Boolean get() = imageUrl == null && genres.isEmpty() && spotifyUrl == null
}
