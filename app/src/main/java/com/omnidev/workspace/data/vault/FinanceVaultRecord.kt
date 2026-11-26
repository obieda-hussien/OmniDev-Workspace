package com.omnidev.workspace.data.vault

/** A masked reference for local inventory. Never contains payment or login credentials. */
internal data class FinanceVaultRecord(val id: String, val issuer: String, val kind: Kind, val lastFour: String) {
    enum class Kind(val label: String) {
        BANK_ACCOUNT("Bank account"),
        PAYMENT_CARD("Payment card")
    }

    companion object {
        private val issuerName = Regex("""[\p{L}\p{M}][\p{L}\p{M} .&'’\-]{0,59}""")
        private val suffix = Regex("[0-9]{4}")

        fun isValid(issuer: String, lastFour: CharSequence): Boolean =
            issuerName.matches(issuer.trim()) && suffix.matches(lastFour)
    }
}
