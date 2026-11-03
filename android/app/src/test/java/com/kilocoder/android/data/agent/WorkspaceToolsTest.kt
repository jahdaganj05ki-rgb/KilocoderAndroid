package com.kilocoder.android.data.agent

import com.kilocoder.android.data.workspace.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesAndReadsThroughToolSchema() = runBlocking {
        val tools = WorkspaceTools(WorkspaceRepository(temp.newFolder("workspace")))

        val write = tools.execute(
            "write_file",
            """{"path":"notes.txt","content":"hello"}""",
        )
        assertFalse(write.isError)

        val read = tools.execute("read_file", """{"path":"notes.txt"}""")
        assertFalse(read.isError)
        assertTrue(read.output.contains("hello"))

        val escape = tools.execute("read_file", """{"path":"../outside.txt"}""")
        assertTrue(escape.isError)
    }
}
