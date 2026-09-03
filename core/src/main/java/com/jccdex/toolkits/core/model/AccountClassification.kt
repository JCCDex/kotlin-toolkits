package com.jccdex.toolkits.core.model

/**
 * Single source of truth for wallet account kind predicates (C-22).
 *
 * [WalletAccount.isRootHD], [WalletAccount.isSubHD], and Room SQL fragments below
 * must stay aligned — update predicates and SQL fragments only here, then run
 * `:account:testDebugUnitTest` (RoomAccountStoreTest covers SQL parity).
 */
object AccountClassification {
    private fun isRootPath(
        pathAccount: Int,
        pathChange: Int,
        pathIndex: Int
    ): Boolean = pathAccount == 0 && pathChange == 0 && pathIndex == 0

    fun isTraditional(isHD: Boolean): Boolean = !isHD

    fun isSubHD(
        isHD: Boolean,
        parentId: String?,
        pathAccount: Int?,
        pathChange: Int?,
        pathIndex: Int?
    ): Boolean {
        if (!isHD) return false
        if (parentId != null) return true
        if (pathIndex == null) return false
        return !isRootPath(pathAccount ?: 0, pathChange ?: 0, pathIndex)
    }

    fun isRootHD(
        isHD: Boolean,
        parentId: String?,
        pathAccount: Int?,
        pathChange: Int?,
        pathIndex: Int?
    ): Boolean {
        if (!isHD || parentId != null) return false
        if (pathIndex == null) return false
        return isRootPath(pathAccount ?: 0, pathChange ?: 0, pathIndex)
    }

    fun isNonRoot(
        isHD: Boolean,
        parentId: String?,
        pathAccount: Int?,
        pathChange: Int?,
        pathIndex: Int?
    ): Boolean = isTraditional(isHD) || isSubHD(isHD, parentId, pathAccount, pathChange, pathIndex)

    /** SQL fragment: BIP44 path columns denote the root path (m/44'/c'/0'/0/0). */
    const val SQL_IS_ROOT_PATH =
        "(COALESCE(pathAccount, 0) = 0 AND COALESCE(pathChange, 0) = 0 AND pathIndex = 0)"

    /** SQL fragment matching [isSubHD]. */
    const val SQL_IS_SUB_HD =
        "(isHD = 1 AND (parentId IS NOT NULL OR (pathIndex IS NOT NULL AND NOT $SQL_IS_ROOT_PATH)))"

    /** SQL fragment matching [isRootHD]. */
    const val SQL_IS_ROOT_HD =
        "(isHD = 1 AND parentId IS NULL AND pathIndex IS NOT NULL AND $SQL_IS_ROOT_PATH)"

    /** SQL fragment matching [isNonRoot] (traditional or sub-HD). */
    const val SQL_IS_NON_ROOT = "(isHD = 0 OR $SQL_IS_SUB_HD)"
}
