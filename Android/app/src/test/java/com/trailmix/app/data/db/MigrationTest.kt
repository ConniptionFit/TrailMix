package com.trailmix.app.data.db

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Exercises the real v2→v9 migration chain ([Migrations]) against a pure-JVM SQLite engine
 * ([JdbcSupportSQLiteDatabase]) — previously an explicit, named gap (BLD-01/the migration
 * chain in `di/AppModule.kt` had zero coverage).
 *
 * Each test builds the `notes`/`chat_messages` table exactly as it existed one version prior,
 * derived by subtracting that migration's own `ADD COLUMN`s from the authoritative v9 schema
 * captured in `app/schemas/com.trailmix.app.data.db.TrailMixDatabase/9.json`
 * (`room.schemaLocation`) — not hand-guessed — seeds a representative pre-migration row, runs
 * the production [Migration] object, and asserts both the new column(s) and the old row's data
 * survive untouched.
 */
class MigrationTest {

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    /** name -> (declared type, NOT NULL) */
    private fun columns(connection: Connection, table: String): Map<String, Pair<String, Boolean>> {
        val result = linkedMapOf<String, Pair<String, Boolean>>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    result[rs.getString("name")] = rs.getString("type") to (rs.getInt("notnull") == 1)
                }
            }
        }
        return result
    }

    private fun migrate(connection: Connection, migration: Migration) {
        migration.migrate(JdbcSupportSQLiteDatabase(connection))
    }

    @Test
    fun migrate2To3_addsMeetingContextColumns_andBackfillsExistingRow() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('Standup', '[]', '[]', '', 60000, 1000, 1, 0)",
                )
            }

            migrate(c, Migrations.MIGRATION_2_3)

            val cols = columns(c, "notes")
            assertTrue(cols.containsKey("meetingTitle"))
            assertTrue(cols.containsKey("capturedInCall"))

            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT title, meetingTitle, capturedInCall FROM notes").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Standup", rs.getString("title"))
                    assertNull(rs.getString("meetingTitle"))
                    assertEquals(0, rs.getInt("capturedInCall")) // DEFAULT 0 backfilled the old row
                }
            }
        }
    }

    @Test
    fun migrate3To4_addsBodyOverride_nullOnExistingRow() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL, meetingTitle TEXT, " +
                        "capturedInCall INTEGER NOT NULL DEFAULT 0)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('1:1 with Sam', '[]', '[]', '', 1800000, 2000, 1, 1)",
                )
            }

            migrate(c, Migrations.MIGRATION_3_4)

            assertTrue(columns(c, "notes").containsKey("bodyOverride"))
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT title, bodyOverride FROM notes").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("1:1 with Sam", rs.getString("title"))
                    assertNull(rs.getString("bodyOverride"))
                }
            }
        }
    }

    @Test
    fun migrate4To5_addsAttendeesSummaryTemplate() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL, meetingTitle TEXT, " +
                        "capturedInCall INTEGER NOT NULL DEFAULT 0, bodyOverride TEXT)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('Weekly Standup', '[]', '[]', '', 900000, 3000, 1, 1)",
                )
            }

            migrate(c, Migrations.MIGRATION_4_5)

            val cols = columns(c, "notes")
            assertTrue(cols.containsKey("attendeesJson"))
            assertTrue(cols.containsKey("summaryJson"))
            assertTrue(cols.containsKey("template"))
        }
    }

    @Test
    fun migrate5To6_addsExportUriColumns() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL, meetingTitle TEXT, " +
                        "capturedInCall INTEGER NOT NULL DEFAULT 0, bodyOverride TEXT, " +
                        "attendeesJson TEXT, summaryJson TEXT, template TEXT)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('Keynote', '[]', '[]', '', 5400000, 4000, 1, 1)",
                )
            }

            migrate(c, Migrations.MIGRATION_5_6)

            val cols = columns(c, "notes")
            assertTrue(cols.containsKey("obsidianFileUri"))
            assertTrue(cols.containsKey("driveFileUri"))
        }
    }

    @Test
    fun migrate6To7_addsRecipeNameToChatMessages() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "noteId INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL)",
                )
                it.execute(
                    "INSERT INTO chat_messages (noteId, role, text, createdAtEpochMs) VALUES " +
                        "(1, 'user', 'Summarize the action items', 5000)",
                )
            }

            migrate(c, Migrations.MIGRATION_6_7)

            assertTrue(columns(c, "chat_messages").containsKey("recipeName"))
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT text, recipeName FROM chat_messages").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Summarize the action items", rs.getString("text"))
                    assertNull(rs.getString("recipeName"))
                }
            }
        }
    }

    @Test
    fun migrate7To8_addsSoftDeleteTimestamp() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL, meetingTitle TEXT, " +
                        "capturedInCall INTEGER NOT NULL DEFAULT 0, bodyOverride TEXT, " +
                        "attendeesJson TEXT, summaryJson TEXT, template TEXT, " +
                        "obsidianFileUri TEXT, driveFileUri TEXT)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('User Interview', '[]', '[]', '', 2700000, 6000, 1, 1)",
                )
            }

            migrate(c, Migrations.MIGRATION_7_8)

            assertTrue(columns(c, "notes").containsKey("deletedAtEpochMs"))
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT title, deletedAtEpochMs FROM notes").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("User Interview", rs.getString("title"))
                    assertNull(rs.getObject("deletedAtEpochMs"))
                }
            }
        }
    }

    @Test
    fun migrate8To9_addsTranscriptFileUri_andFinalShapeMatchesV9Schema() {
        connect().use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, segmentsJson TEXT NOT NULL, transcriptJson TEXT NOT NULL, " +
                        "typedFragments TEXT NOT NULL, durationMs INTEGER NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, showSources INTEGER NOT NULL, " +
                        "mergedWithAi INTEGER NOT NULL, meetingTitle TEXT, " +
                        "capturedInCall INTEGER NOT NULL DEFAULT 0, bodyOverride TEXT, " +
                        "attendeesJson TEXT, summaryJson TEXT, template TEXT, " +
                        "obsidianFileUri TEXT, driveFileUri TEXT, deletedAtEpochMs INTEGER)",
                )
                it.execute(
                    "INSERT INTO notes (title, segmentsJson, transcriptJson, typedFragments, " +
                        "durationMs, createdAtEpochMs, showSources, mergedWithAi) VALUES " +
                        "('Conference keynote', '[]', '[]', '', 5400000, 7000, 1, 1)",
                )
            }

            migrate(c, Migrations.MIGRATION_8_9)

            val cols = columns(c, "notes")
            assertTrue(cols.containsKey("transcriptFileUri"))

            // Pinned against app/schemas/com.trailmix.app.data.db.TrailMixDatabase/9.json —
            // this is the check that would catch a column silently added/renamed/dropped
            // anywhere in the chain, not just in this last step.
            val expectedV9Columns = setOf(
                "id", "title", "segmentsJson", "transcriptJson", "typedFragments", "durationMs",
                "createdAtEpochMs", "showSources", "mergedWithAi", "meetingTitle", "capturedInCall",
                "bodyOverride", "attendeesJson", "summaryJson", "template", "obsidianFileUri",
                "driveFileUri", "deletedAtEpochMs", "transcriptFileUri",
            )
            assertEquals(expectedV9Columns, cols.keys)
        }
    }
}
