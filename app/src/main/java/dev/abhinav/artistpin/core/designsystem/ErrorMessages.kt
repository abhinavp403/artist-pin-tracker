package dev.abhinav.artistpin.core.designsystem

import dev.abhinav.artistpin.core.model.DataError

fun DataError.toUserMessage(): String = when (this) {
    DataError.Storage -> "Couldn't save that — storage is unavailable"
    DataError.MediaUnavailable -> "Couldn't read the selected photos"
    is DataError.Network -> "Couldn't reach venue search — you can still drop a pin manually"
    DataError.SearchUnavailable -> "Venue search isn't set up — drop a pin manually instead"
    DataError.SyncUnavailable -> "Couldn't reach your account — changes will sync when you're back online"
    is DataError.Validation -> reason
    is DataError.Unknown -> message ?: "Something went wrong"
}
