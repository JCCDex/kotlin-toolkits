package com.jccdex.toolkits.account.orchestrator

sealed class AccountOperationError {
    data object AddressAlreadyExists : AccountOperationError()

    data object AccountAlreadyExists : AccountOperationError()

    data object RootAccountNotFound : AccountOperationError()

    data object PasswordRequired : AccountOperationError()

    /** Vault already has a password; clearExisting requires the current vault password. */
    data object PasswordRequiredForClear : AccountOperationError()

    data class WrongPassword(
        val message: String = "Password is wrong"
    ) : AccountOperationError()

    /** Vault auth is time-locked after too many failed attempts (M-21A); [remainingMs] for countdown UI. */
    data class VaultLocked(
        val remainingMs: Long
    ) : AccountOperationError()

    data class Failure(
        val cause: Throwable
    ) : AccountOperationError()
}

sealed class AccountOperationResult<out T> {
    data class Success<T>(
        val value: T
    ) : AccountOperationResult<T>()

    data class Error(
        val error: AccountOperationError
    ) : AccountOperationResult<Nothing>()
}
