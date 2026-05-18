package com.dangerfield.cards.server.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnvTest {

    @Test
    fun realEnvBeatsFile() {
        val file = tempEnvFile(
            """
            FOO=from-file
            """.trimIndent(),
        )
        val env = Env(envFilePath = file, realEnv = { if (it == "FOO") "from-env" else null })
        assertEquals("from-env", env["FOO"])
    }

    @Test
    fun fileFallsBackWhenOsEnvIsBlank() {
        val file = tempEnvFile(
            """
            FOO=from-file
            """.trimIndent(),
        )
        val env = Env(envFilePath = file, realEnv = { "" })
        assertEquals("from-file", env["FOO"])
    }

    @Test
    fun missingValueReturnsNull() {
        val file = tempEnvFile("")
        val env = Env(envFilePath = file, realEnv = { null })
        assertNull(env["MISSING"])
    }

    @Test
    fun requireThrowsWhenMissing() {
        val file = tempEnvFile("")
        val env = Env(envFilePath = file, realEnv = { null })
        assertFailsWith<IllegalStateException> { env.require("MISSING") }
    }

    @Test
    fun parsesSimpleAndQuotedValues() {
        val file = tempEnvFile(
            """
            # comment line
            UNQUOTED=hello
            QUOTED="hello world"
            SINGLE='hi there'

            EMPTY_LINES_OK=yes
            """.trimIndent(),
        )
        val env = Env(envFilePath = file, realEnv = { null })
        assertEquals("hello", env["UNQUOTED"])
        assertEquals("hello world", env["QUOTED"])
        assertEquals("hi there", env["SINGLE"])
        assertEquals("yes", env["EMPTY_LINES_OK"])
    }

    @Test
    fun missingEnvFile_doesNotThrow() {
        val env = Env(envFilePath = "/tmp/definitely-does-not-exist-cards-${System.nanoTime()}.env", realEnv = { null })
        assertNull(env["ANYTHING"])
    }

    @Test
    fun intAndLongParseOrDefault() {
        val file = tempEnvFile(
            """
            PORT=9090
            NOT_A_NUMBER=hello
            """.trimIndent(),
        )
        val env = Env(envFilePath = file, realEnv = { null })
        assertEquals(9090, env.int("PORT", default = 8080))
        assertEquals(42, env.int("NOT_A_NUMBER", default = 42))
        assertEquals(42L, env.long("MISSING", default = 42L))
    }

    private fun tempEnvFile(contents: String): String {
        val tmp = Files.createTempFile("env-test", ".env").toFile()
        tmp.writeText(contents)
        tmp.deleteOnExit()
        return tmp.absolutePath
    }
}
