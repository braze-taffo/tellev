package app.tellev.core.provider

class ProviderRegistry(
    adapters: List<ProviderAdapter>,
) {
    private val byId: Map<String, ProviderAdapter> = adapters.associateBy { it.id }

    fun all(): List<ProviderAdapter> = byId.values.sortedBy { it.displayName }

    fun require(id: String): ProviderAdapter =
        byId[id] ?: error("Provider adapter not registered: $id")
}

