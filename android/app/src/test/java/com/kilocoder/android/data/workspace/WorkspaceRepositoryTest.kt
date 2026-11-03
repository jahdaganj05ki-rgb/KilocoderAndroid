package com.kilocoder.android.data.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: WorkspaceRepository

    @Before
    fun setUp() {
        repo = WorkspaceRepository(temp.newFolder("workspace"))
    }

    @Test
    fun writesReadsReplacesAndSearches() = runBlocking {
        repo.write("src/Main.kt", "hello")
        assertTrue(repo.read("src/Main.kt").contains("1: hello"))

        repo.replace("src/Main.kt", "hello", "world", replaceAll = false)
        assertTrue(repo.read("src/Main.kt").contains("world"))
        assertTrue(repo.search("world").contains("src/Main.kt"))
        assertTrue(repo.list("src").contains("Main.kt"))
    }

    @Test
    fun rejectsTraversal() {
        assertThrows(WorkspaceException::class.java) {
            repo.resolve("../escape.txt")
        }
    }

    @Test
    fun refusesToDeleteRoot() = runBlocking {
        try {
            repo.delete(".")
            throw AssertionError("root deletion should be rejected")
        } catch (expected: WorkspaceException) {
            // expected
        }
    }
}
