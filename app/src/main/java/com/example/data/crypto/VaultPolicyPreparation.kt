package com.example.data.crypto

internal data class PreparedVaultPolicy(val state: VaultPolicyState, val rejected: Boolean = false)

/** Only KEY_REQUESTED may roll back: that phase guarantees no record was rewritten. */
internal object VaultPolicyPreparation {
    fun finish(store: VaultPolicyStore, state: VaultPolicyState, proveKey: (Int, Boolean) -> Unit): PreparedVaultPolicy {
        val target = state.target ?: return PreparedVaultPolicy(state)
        if (state.phase != "KEY_REQUESTED") {
            proveKey(target, false)
            return PreparedVaultPolicy(state)
        }
        try { proveKey(target, true) }
        catch (failure: Exception) {
            val original = VaultPolicyState(state.current)
            // If this durable rollback fails, leave the journal pending and fail closed.
            store.write(original)
            return PreparedVaultPolicy(original, rejected = true)
        }
        val prepared = state.copy(phase = "PREPARED")
        store.write(prepared)
        return PreparedVaultPolicy(prepared)
    }
}
