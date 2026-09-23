package com.wall.guard.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY package_name ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY package_name ASC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: Int): RuleEntity?

    @Query("SELECT * FROM rules WHERE uid = :uid LIMIT 1")
    fun observeByUid(uid: Int): Flow<RuleEntity?>

    @Query("SELECT * FROM rules WHERE package_name = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RuleEntity>)

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE uid = :uid")
    suspend fun deleteByUid(uid: Int)

    @Query("DELETE FROM rules WHERE uid NOT IN (:uids)")
    suspend fun deleteByUidsNotIn(uids: List<Int>)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    @Query("SELECT package_name FROM rules WHERE allowed = 0")
    suspend fun getBlockedPackages(): List<String>
}
