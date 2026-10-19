package com.vaishnavi.theweatherwithin

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkins",
    indices = [Index(value = ["dateKey"])]
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val dateKey: String,
    val kind: String,
    val overallLoad: Int,
    val flaggedCareless: Boolean = false
)

@Entity(
    tableName = "answers",
    foreignKeys = [
        ForeignKey(
            entity = CheckInEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["checkInId"]),
        Index(value = ["dimension"]),
        Index(value = ["facet"]),
        Index(value = ["itemId"])
    ]
)
data class AnswerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val checkInId: Long,
    val itemId: String,
    val dimension: String,
    val facet: String,
    val rawAnswer: Int,
    val reverse: Boolean,
    val load: Int
)

@Entity(tableName = "facet_estimates", primaryKeys = ["dimension", "facet"])
data class FacetEstimateEntity(
    val dimension: String,
    val facet: String,
    val estimate: Double,
    val lastUpdated: Long
)

@Entity(tableName = "dimension_snapshots", primaryKeys = ["dateKey", "dimension"])
data class DimensionSnapshotEntity(
    val dateKey: String,
    val dimension: String,
    val score: Int,
    val timestamp: Long
)

/**
 * One row per journal entry — the raw free text, kept forever, separate
 * from any facet tags that get extracted from it. Extraction results are
 * NOT stored here; confirmed tags flow into facet_estimates/answers via
 * the normal saveCheckIn() path, same as any other check-in.
 */
@Entity(tableName = "journal_entries")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val dateKey: String,
    val timestamp: Long
)