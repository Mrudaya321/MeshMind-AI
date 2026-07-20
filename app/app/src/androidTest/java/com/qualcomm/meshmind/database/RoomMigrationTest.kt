package com.qualcomm.meshmind.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun testMigration3To4() {
        // Create the database in version 3
        var db = helper.createDatabase(TEST_DB, 3)

        // Insert legacy data
        db.execSQL("INSERT INTO conversations (conversationId, name, lastMessageSnippet, lastMessageTimestamp, unreadCount) VALUES ('conv1', 'Team', 'Hello', 1000, 0)")
        
        // In V3, messages table does not have emergency fields
        db.execSQL("INSERT INTO messages (messageId, conversationId, senderNodeId, receiverNodeId, payload, timestamp, isSent, status) VALUES ('msg1', 'conv1', 'nodeA', 'nodeB', 'Fire', 1000, 1, 'DELIVERED')")
        
        // In V3, routing_information has pathReliability
        db.execSQL("INSERT INTO routing_information (destinationNodeId, nextHopNodeId, hopCount, sequenceNumber, updatedTimestamp, routeAge, isValid, pathReliability) VALUES ('nodeX', 'nodeY', 2, 5, 2000, 0, 1, 0.99)")
        db.execSQL("INSERT INTO routing_information (destinationNodeId, nextHopNodeId, hopCount, sequenceNumber, updatedTimestamp, routeAge, isValid, pathReliability) VALUES ('nodeA', 'nodeA', 0, 1, 1000, 0, 1, 1.0)")
        
        // In V3, ai_inference_history exists
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_inference_history (id INTEGER PRIMARY KEY, something TEXT)")
        db.execSQL("INSERT INTO ai_inference_history (id, something) VALUES (1, 'test')")

        db.close()

        // Re-open database with version 4 and provide MIGRATION_3_4
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        // Verify conversation remains
        var cursor = db.query("SELECT * FROM conversations WHERE conversationId = 'conv1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Team", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        cursor.close()

        // Verify message remains and new fields are null
        cursor = db.query("SELECT * FROM messages WHERE messageId = 'msg1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Fire", cursor.getString(cursor.getColumnIndexOrThrow("payload")))
        assertEquals("nodeA", cursor.getString(cursor.getColumnIndexOrThrow("senderNodeId")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("emergencyClassIndex")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("emergencyClassLabel")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("emergencyConfidence")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("classificationTimestamp")))
        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("taxonomyVersion")))
        cursor.close()

        // Verify routing info remains and does not have pathReliability
        cursor = db.query("SELECT * FROM routing_information WHERE destinationNodeId = 'nodeX'")
        assertTrue(cursor.moveToFirst())
        assertEquals("nodeY", cursor.getString(cursor.getColumnIndexOrThrow("nextHopNodeId")))
        assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("hopCount")))
        assertEquals(5, cursor.getInt(cursor.getColumnIndexOrThrow("sequenceNumber")))
        assertEquals(2000, cursor.getLong(cursor.getColumnIndexOrThrow("updatedTimestamp")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("isValid")))
        // check pathReliability is gone
        assertEquals(-1, cursor.getColumnIndex("pathReliability"))
        cursor.close()

        // Verify ai_inference_history is removed
        cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ai_inference_history'")
        assertFalse(cursor.moveToFirst())
        cursor.close()

        db.close()
    }
}
