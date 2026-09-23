package com.wall.guard.data.repository

import com.wall.guard.data.db.RuleDao
import com.wall.guard.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleRepository @Inject constructor(
    private val ruleDao: RuleDao
) {
    fun observeAll(): Flow<List<RuleEntity>> = ruleDao.observeAll()

    suspend fun getAll(): List<RuleEntity> = ruleDao.getAll()

    suspend fun getByUid(uid: Int): RuleEntity? = ruleDao.getByUid(uid)

    fun observeByUid(uid: Int): Flow<RuleEntity?> = ruleDao.observeByUid(uid)

    suspend fun getByPackage(packageName: String): RuleEntity? = ruleDao.getByPackage(packageName)

    suspend fun upsert(rule: RuleEntity) {
        ruleDao.upsert(rule)
    }

    suspend fun upsertAll(rules: List<RuleEntity>) {
        ruleDao.upsertAll(rules)
    }

    suspend fun deleteByUid(uid: Int) {
        ruleDao.deleteByUid(uid)
    }

    suspend fun deleteRulesNotIn(uids: Set<Int>) {
        if (uids.isNotEmpty()) {
            ruleDao.deleteByUidsNotIn(uids.toList())
        }
    }

    suspend fun getBlockedPackages(): List<String> = ruleDao.getBlockedPackages()

    suspend fun count(): Int = ruleDao.count()

    suspend fun getOrCreateRule(uid: Int, packageName: String): RuleEntity {
        val existing = ruleDao.getByUid(uid)
        if (existing != null) return existing
        val newRule = RuleEntity(
            uid = uid,
            packageName = packageName,
            allowed = true
        )
        val id = ruleDao.upsert(newRule)
        return newRule.copy(id = id)
    }
}
