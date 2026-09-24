package app.tellev.core.extension

import kotlinx.coroutines.CancellationException

/** Keep one extension's failure local while preserving the caller's cancellation. */
internal suspend fun dispatchExtensionEventToRuntimes(
    extensionIds: List<String>,
    excludeExtensionId: String?,
    dispatch: suspend (String) -> Unit,
    onFailure: (String, Exception) -> Unit,
) {
    for (id in extensionIds) {
        if (id == excludeExtensionId) continue
        try {
            dispatch(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(id, failure)
        }
    }
}
