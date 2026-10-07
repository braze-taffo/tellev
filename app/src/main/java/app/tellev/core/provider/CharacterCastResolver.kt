package app.tellev.core.provider

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterCastBinding
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.CancellationException

/** Resolve direct links only: cycles never recursively pull another cast into the conversation. */
suspend fun resolveCharacterCast(card: CharacterCard, store: StDataStore): List<CharacterCard> =
    CharacterCastBinding.members(card).map { snapshot ->
        try { store.readCharacter(snapshot.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snapshot }
    }.filter { it.id != card.id }.distinctBy { it.id }
