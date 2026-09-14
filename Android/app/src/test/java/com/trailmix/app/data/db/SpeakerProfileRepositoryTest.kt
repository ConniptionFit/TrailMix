package com.trailmix.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeakerProfileRepositoryTest {

    private lateinit var dao: FakeSpeakerProfileDao
    private lateinit var repo: SpeakerProfileRepository

    @Before
    fun setUp() {
        dao = FakeSpeakerProfileDao()
        repo = SpeakerProfileRepository(dao)
    }

    @Test
    fun `save persists a trimmed name and the given bytes`() = runBlocking {
        val id = repo.save("  Alice  ", byteArrayOf(1, 2, 3))

        assertTrue(id != null)
        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("Alice", all[0].name)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(all[0].profileBytes))
    }

    @Test
    fun `save rejects a blank name and writes nothing`() = runBlocking {
        val id = repo.save("   ", byteArrayOf(1))

        assertNull(id)
        assertEquals(0, repo.getAll().size)
    }

    @Test
    fun `save rejects an empty profile and writes nothing`() = runBlocking {
        val id = repo.save("Bob", byteArrayOf())

        assertNull(id)
        assertEquals(0, repo.getAll().size)
    }

    @Test
    fun `rename updates the name without touching the profile bytes`() = runBlocking {
        val id = repo.save("Alice", byteArrayOf(9, 9))!!

        repo.rename(id, "  Alicia  ")

        val all = repo.getAll()
        assertEquals("Alicia", all[0].name)
        assertTrue(byteArrayOf(9, 9).contentEquals(all[0].profileBytes))
    }

    @Test
    fun `rename to a blank name is a no-op`() = runBlocking {
        val id = repo.save("Alice", byteArrayOf(9, 9))!!

        repo.rename(id, "   ")

        assertEquals("Alice", repo.getAll()[0].name)
    }

    @Test
    fun `delete removes the profile`() = runBlocking {
        val id = repo.save("Alice", byteArrayOf(9, 9))!!

        repo.delete(id)

        assertEquals(0, repo.getAll().size)
    }

    @Test
    fun `enrolledSpeakers reflects saves in name order`() = runBlocking {
        repo.save("Zoe", byteArrayOf(1))
        repo.save("Amir", byteArrayOf(2))

        val names = repo.getAll().map { it.name }
        assertEquals(listOf("Amir", "Zoe"), names)
    }
}

private class FakeSpeakerProfileDao : SpeakerProfileDao {
    val rows = linkedMapOf<Long, SpeakerProfileEntity>()
    private val changes = MutableStateFlow(0)
    private var nextId = 1L

    override fun observeAll(): Flow<List<SpeakerProfileEntity>> =
        changes.map { rows.values.sortedBy { it.name } }

    override suspend fun getAll(): List<SpeakerProfileEntity> = rows.values.sortedBy { it.name }

    override suspend fun insert(profile: SpeakerProfileEntity): Long {
        val id = nextId++
        rows[id] = profile.copy(id = id)
        changes.value++
        return id
    }

    override suspend fun rename(id: Long, name: String) {
        rows[id]?.let { rows[id] = it.copy(name = name) }
        changes.value++
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        changes.value++
    }
}
