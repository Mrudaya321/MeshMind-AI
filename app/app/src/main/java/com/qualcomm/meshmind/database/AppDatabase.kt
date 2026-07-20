package com.qualcomm.meshmind.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main local SQLite Room database for the MeshMind node in Kotlin.
 */
@Database(
    entities = [
        LogEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        NeighborStateEntity::class,
        RoutingInformationEntity::class,
        TelemetryHistoryEntity::class,
        PacketHistoryEntity::class,
        DiagnosticEventEntity::class,
        RuntimeStatisticsEntity::class,
        EmergencyEventEntity::class,
        DeviceInformationEntity::class,
        EmergencyBroadcastEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun neighborStateDao(): NeighborStateDao
    abstract fun routingInformationDao(): RoutingInformationDao
    abstract fun telemetryHistoryDao(): TelemetryHistoryDao
    abstract fun packetHistoryDao(): PacketHistoryDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun runtimeStatisticsDao(): RuntimeStatisticsDao
    abstract fun emergencyEventDao(): EmergencyEventDao
    abstract fun deviceInformationDao(): DeviceInformationDao
    abstract fun emergencyBroadcastDao(): EmergencyBroadcastDao

    companion object {
        private const val DATABASE_NAME = "meshmind_local.db"
        
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop legacy AI table
                db.execSQL("DROP TABLE IF EXISTS ai_inference_history")
                
                // Add emergency metadata to messages table
                db.execSQL("ALTER TABLE messages ADD COLUMN emergencyClassIndex INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN emergencyClassLabel TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN emergencyConfidence REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN classificationTimestamp INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN taxonomyVersion TEXT DEFAULT NULL")

                // Remove pathReliability from routing_information table by recreating it
                db.execSQL("CREATE TABLE routing_information_new (destinationNodeId TEXT NOT NULL, nextHopNodeId TEXT NOT NULL, hopCount INTEGER NOT NULL, sequenceNumber INTEGER NOT NULL, updatedTimestamp INTEGER NOT NULL, routeAge INTEGER NOT NULL, isValid INTEGER NOT NULL, PRIMARY KEY(destinationNodeId))")
                db.execSQL("INSERT INTO routing_information_new (destinationNodeId, nextHopNodeId, hopCount, sequenceNumber, updatedTimestamp, routeAge, isValid) SELECT destinationNodeId, nextHopNodeId, hopCount, sequenceNumber, updatedTimestamp, routeAge, isValid FROM routing_information")
                db.execSQL("DROP TABLE routing_information")
                db.execSQL("ALTER TABLE routing_information_new RENAME TO routing_information")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emergency_broadcasts` (" +
                            "`emergencyId` TEXT NOT NULL, " +
                            "`originNodeId` TEXT NOT NULL, " +
                            "`originalText` TEXT NOT NULL, " +
                            "`predictedClassIndex` INTEGER NOT NULL, " +
                            "`predictedClassLabel` TEXT NOT NULL, " +
                            "`confidence` REAL NOT NULL, " +
                            "`targetResponseRole` TEXT NOT NULL, " +
                            "`destinationNodeId` TEXT, " +
                            "`classificationTimestamp` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`deliveryStatus` TEXT NOT NULL, " +
                            "`isOutgoing` INTEGER NOT NULL, " +
                            "`modelVersion` TEXT NOT NULL, " +
                            "`taxonomyVersion` TEXT NOT NULL, " +
                            "PRIMARY KEY(`emergencyId`))"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
            }
        }
    }
}
