package dev.abhinav.artistpin.core.model

/**
 * The domain error type every repository maps platform exceptions onto. SQLite, file, and
 * content-resolver exceptions never escape the data layer.
 */
sealed interface DataError {
    data object Storage : DataError
    data object MediaUnavailable : DataError

    /** Venue lookup needs a network round-trip; manual entry stays available when it fails. */
    data class Network(val message: String?) : DataError
    data object SearchUnavailable : DataError
    data class Validation(val reason: String) : DataError
    data class Unknown(val message: String?) : DataError
}

sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
}

inline fun <T> DataResult<T>.onSuccess(action: (T) -> Unit): DataResult<T> = also {
    if (it is DataResult.Success) action(it.data)
}

inline fun <T> DataResult<T>.onFailure(action: (DataError) -> Unit): DataResult<T> = also {
    if (it is DataResult.Failure) action(it.error)
}

fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Success)?.data
