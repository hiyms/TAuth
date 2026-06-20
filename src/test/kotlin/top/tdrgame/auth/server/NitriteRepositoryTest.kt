package top.tdrgame.auth.server

import org.dizitart.no2.Nitrite
import org.dizitart.no2.exceptions.ValidationException
import org.dizitart.no2.filters.FluentFilter.where
import org.dizitart.no2.mvstore.MVStoreModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NitriteRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `repository type validation fails without PlayerAuthData converter`() {
        val db = openDb("without-converter.db", registerConverter = false)
        try {
            assertFailsWith<ValidationException> {
                db.getRepository(PlayerAuthData::class.java)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `repository opens with PlayerAuthData converter and type validation enabled`() {
        val db = openDb("with-converter.db", registerConverter = true)
        try {
            val repo = db.getRepository(PlayerAuthData::class.java)
            assertNotNull(repo)
            assertTrue(repo.find().toList().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `repository persists and reads PlayerAuthData with all fields`() {
        val db = openDb("roundtrip.db", registerConverter = true)
        try {
            val repo = db.getRepository(PlayerAuthData::class.java)
            val source = PlayerAuthData(
                playerName = "Steve",
                passwordHash = "v1:10000:256:salt:hash",
                verified = true,
                lastLoginType = "online",
                autoLoginMachineId = "machine-id",
                autoLoginIp = "127.0.0.1"
            )

            repo.insert(source)
            val restored = repo.find(where("playerName").eq("Steve")).firstOrNull()

            assertEquals(source, restored)
        } finally {
            db.close()
        }
    }

    @Test
    fun `repository update preserves premium history and auto login fields`() {
        val db = openDb("update.db", registerConverter = true)
        try {
            val repo = db.getRepository(PlayerAuthData::class.java)
            val source = PlayerAuthData(
                playerName = "Alex",
                passwordHash = "hash",
                verified = false,
                lastLoginType = "offline"
            )
            repo.insert(source)

            val stored = repo.find(where("playerName").eq("Alex")).firstOrNull()
            assertNotNull(stored)
            repo.update(stored.copy(
                verified = true,
                lastLoginType = "online",
                autoLoginMachineId = "machine",
                autoLoginIp = "10.0.0.1"
            ))

            val updated = repo.find(where("playerName").eq("Alex")).firstOrNull()
            assertNotNull(updated)
            assertTrue(updated.verified)
            assertEquals("online", updated.lastLoginType)
            assertEquals("machine", updated.autoLoginMachineId)
            assertEquals("10.0.0.1", updated.autoLoginIp)
        } finally {
            db.close()
        }
    }

    @Test
    fun `repository removes PlayerAuthData by player name filter`() {
        val db = openDb("delete.db", registerConverter = true)
        try {
            val repo = db.getRepository(PlayerAuthData::class.java)
            repo.insert(PlayerAuthData("Herobrine", "hash", verified = false))
            assertFalse(repo.find(where("playerName").eq("Herobrine")).toList().isEmpty())

            repo.remove(where("playerName").eq("Herobrine"))

            assertNull(repo.find(where("playerName").eq("Herobrine")).firstOrNull())
        } finally {
            db.close()
        }
    }

    private fun openDb(fileName: String, registerConverter: Boolean): Nitrite {
        val builder = Nitrite.builder()
            .loadModule(MVStoreModule(tempDir.resolve(fileName).toString()))
        if (registerConverter) {
            builder.registerEntityConverter(PlayerAuthDataConverter())
        }
        return builder.openOrCreate()
    }
}
