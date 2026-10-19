package com.vaishnavi.theweatherwithin

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

data class CheckInWithAnswers(
    @Embedded val checkIn: CheckInEntity,
    @Relation(parentColumn = "id", entityColumn = "checkInId")
    val answers: List<AnswerEntity>
)

data class DimensionAverage(val dimension: String, val avgLoad: Double)

data class MonthlyDimensionAverage(
    val month: String,
    val dimension: String,
    val avgScore: Double
)

@Dao
interface CheckInDao {

    @Insert
    suspend fun insertCheckIn(checkIn: CheckInEntity): Long

    @Insert
    suspend fun insertAnswers(answers: List<AnswerEntity>)

    @Insert
    suspend fun insertJournalEntry(entry: JournalEntity): Long

    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC")
    suspend fun getAllJournalEntries(): List<JournalEntity>

    @Query("SELECT * FROM facet_estimates")
    suspend fun getAllFacetEstimates(): List<FacetEstimateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFacetEstimates(estimates: List<FacetEstimateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDimensionSnapshots(snapshots: List<DimensionSnapshotEntity>)

    @Query("""
        SELECT * FROM dimension_snapshots
        WHERE dimension = :dimension
        ORDER BY dateKey DESC
        LIMIT :days
    """)
    suspend fun getRecentSnapshotsForDimension(dimension: String, days: Int): List<DimensionSnapshotEntity>

    @Query("""
        SELECT * FROM dimension_snapshots
        ORDER BY dateKey DESC
    """)
    suspend fun getAllSnapshotsDescending(): List<DimensionSnapshotEntity>

    @Query("""
        SELECT substr(dateKey, 1, 7) AS month, dimension, AVG(score) AS avgScore
        FROM dimension_snapshots
        GROUP BY month, dimension
        ORDER BY month DESC
    """)
    suspend fun getMonthlyDimensionAverages(): List<MonthlyDimensionAverage>

    @Transaction
    suspend fun saveCheckInWithFacets(
        checkIn: CheckInEntity,
        answers: List<AnswerEntity>,
        updatedFacets: List<FacetEstimateEntity>,
        dimensionSnapshots: List<DimensionSnapshotEntity>
    ): Long {
        val id = insertCheckIn(checkIn)
        insertAnswers(answers.map { it.copy(checkInId = id) })
        upsertFacetEstimates(updatedFacets)
        upsertDimensionSnapshots(dimensionSnapshots)
        return id
    }

    @Transaction
    @Query("SELECT * FROM checkins ORDER BY timestamp DESC")
    suspend fun getAllWithAnswers(): List<CheckInWithAnswers>

    @Transaction
    @Query("SELECT * FROM checkins ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestWithAnswers(): CheckInWithAnswers?

    @Query("SELECT * FROM checkins ORDER BY timestamp DESC")
    suspend fun getAllCheckIns(): List<CheckInEntity>

    @Query("SELECT * FROM checkins WHERE kind = 'daily' ORDER BY timestamp ASC")
    suspend fun getDailyCheckInsAscending(): List<CheckInEntity>

    @Query("SELECT COUNT(*) FROM checkins WHERE dateKey = :dateKey")
    suspend fun countForDate(dateKey: String): Int

    @Query("SELECT COUNT(*) FROM checkins")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM checkins WHERE kind = 'daily'")
    suspend fun countDaily(): Int

    @Query("""
        SELECT a.dimension AS dimension, AVG(a.load) AS avgLoad
        FROM answers a
        INNER JOIN checkins c ON c.id = a.checkInId
        WHERE c.timestamp >= :since
        GROUP BY a.dimension
    """)
    suspend fun averageByDimension(since: Long): List<DimensionAverage>

    @Query("""
        SELECT AVG(a.load) FROM answers a
        INNER JOIN checkins c ON c.id = a.checkInId
        WHERE a.facet = :facet AND c.timestamp >= :since
    """)
    suspend fun averageForFacet(facet: String, since: Long): Double?

    @Query("DELETE FROM checkins")
    suspend fun deleteAll()

    @Query("DELETE FROM facet_estimates")
    suspend fun deleteAllFacetEstimates()

    @Query("DELETE FROM dimension_snapshots")
    suspend fun deleteAllDimensionSnapshots()

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAllJournalEntries()
}