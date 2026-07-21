package com.ai.assistance.operit.host

object OperitHostProvider {
    @Volatile
    private var host: OperitHostContract? = null

    @Volatile
    private var operations: OperitHostOperations? = null

    fun install(contract: OperitHostContract) {
        host = contract
    }

    fun currentOrNull(): OperitHostContract? = host

    fun installOperations(value: OperitHostOperations) {
        operations = value
    }

    fun currentOperationsOrNull(): OperitHostOperations? = operations

    fun operationsOrUnsupported(): OperitHostOperations =
        operations ?: UnsupportedOperitHostOperations

    fun requireHost(): OperitHostContract =
        host ?: error("Operit host contract has not been installed by SmallPhoneAI.")
}
