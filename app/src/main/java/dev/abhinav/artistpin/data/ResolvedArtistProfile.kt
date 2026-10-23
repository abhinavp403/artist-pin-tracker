package dev.abhinav.artistpin.data

/** What a finished artist lookup produced, on its way to the shared catalog. */
data class ResolvedArtistProfile(
    val artistId: String,
    val artistName: String,
    val imageUrl: String?,
    val genres: String,
    val spotifyUrl: String?,
)
