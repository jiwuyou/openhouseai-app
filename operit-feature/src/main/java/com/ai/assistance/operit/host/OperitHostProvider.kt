package com.ai.assistance.operit.host

object OperitHostProvider {
    @Volatile
    private var host: OperitHostContract? = null

    fun install(contract: OperitHostContract) {
        host = contract
    }

    fun currentOrNull(): OperitHostContract? = host

    fun requireHost(): OperitHostContract =
        host ?: error("Operit host contract has not been installed by SmallPhoneAI.")
}
