package com.kilocoder.android.data.agent

import com.kilocoder.android.data.provider.ChatToolSpec
import com.kilocoder.android.data.provider.ProviderHttp
import com.kilocoder.android.data.workspace.WorkspaceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class WorkspaceTools(private val workspace: WorkspaceRepository) {
    val all: List<AgentTool> = listOf(
        tool(
            name = "list_files",
            description = "List files and directories in the workspace. Use recursive=true for a shallow tree.",
            parameters = schema(
                listOf(
                    Field("path", "string", "Relative directory path; defaults to the workspace root."),
                    Field("recursive", "boolean", "Recurse into subdirectories up to 8 levels."),
                ),
            ),
        ) { args ->
            AgentToolResult(workspace.list(args.string("path").ifBlank { "." }, args.bool("recursive")))
        },
        tool(
            name = "read_file",
            description = "Read a text file with line numbers. Optionally limit to a line range.",
            parameters = schema(
                listOf(
                    Field("path", "string", "Relative file path."),
                    Field("start_line", "integer", "First line, 1-based."),
                    Field("end_line", "integer", "Last line, inclusive."),
                ),
                listOf("path"),
            ),
        ) { args ->
            AgentToolResult(
                workspace.read(
                    path = args.string("path"),
                    startLine = args.int("start_line"),
                    endLine = args.int("end_line"),
                ),
            )
        },
        tool(
            name = "write_file",
            description = "Create or overwrite a text file. Parent directories are created automatically.",
            parameters = schema(
                listOf(
                    Field("path", "string", "Relative file path."),
                    Field("content", "string", "Complete file content."),
                ),
                listOf("path", "content"),
            ),
        ) { args ->
            AgentToolResult(workspace.write(args.string("path"), args.string("content")))
        },
        tool(
            name = "replace_in_file",
            description = "Replace an exact text fragment in a file. Fails if the fragment is missing or ambiguous unless replace_all=true.",
            parameters = schema(
                listOf(
                    Field("path", "string", "Relative file path."),
                    Field("old", "string", "Exact text to replace."),
                    Field("new", "string", "Replacement text."),
                    Field("replace_all", "boolean", "Replace every occurrence."),
                ),
                listOf("path", "old", "new"),
            ),
        ) { args ->
            AgentToolResult(
                workspace.replace(
                    path = args.string("path"),
                    old = args.string("old"),
                    new = args.string("new"),
                    replaceAll = args.bool("replace_all"),
                ),
            )
        },
        tool(
            name = "search_files",
            description = "Search text files for a case-insensitive substring and return path:line matches.",
            parameters = schema(
                listOf(
                    Field("query", "string", "Text to find."),
                    Field("path", "string", "Relative directory; defaults to the workspace root."),
                    Field("max_results", "integer", "Maximum matches, 1-200."),
                ),
                listOf("query"),
            ),
        ) { args ->
            AgentToolResult(
                workspace.search(
                    query = args.string("query"),
                    path = args.string("path").ifBlank { "." },
                    maxResults = args.int("max_results") ?: 80,
                ),
            )
        },
        tool(
            name = "make_directory",
            description = "Create a directory inside the workspace.",
            parameters = schema(
                listOf(Field("path", "string", "Relative directory path.")),
                listOf("path"),
            ),
        ) { args ->
            AgentToolResult(workspace.makeDirectory(args.string("path")))
        },
        tool(
            name = "delete_file",
            description = "Delete a file or an empty directory inside the workspace.",
            parameters = schema(
                listOf(Field("path", "string", "Relative path to delete.")),
                listOf("path"),
            ),
        ) { args ->
            AgentToolResult(workspace.delete(args.string("path")))
        },
    )

    fun specs(): List<ChatToolSpec> = all.map { it.spec }

    suspend fun execute(name: String, raw: String): AgentToolResult {
        val tool = all.firstOrNull { it.spec.name == name }
            ?: return AgentToolResult("Unknown tool: $name", isError = true)
        val args = try {
            ProviderHttp.json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
        } catch (error: Exception) {
            return AgentToolResult("Invalid tool arguments: ${error.message}", isError = true)
        }
        return try {
            tool.execute(args)
        } catch (error: Exception) {
            AgentToolResult(error.message ?: "Tool failed.", isError = true)
        }
    }

    private fun tool(
        name: String,
        description: String,
        parameters: JsonObject,
        block: suspend (JsonObject) -> AgentToolResult,
    ): AgentTool = object : AgentTool {
        override val spec = ChatToolSpec(name = name, description = description, parameters = parameters)
        override suspend fun execute(args: JsonObject): AgentToolResult = block(args)
    }

    private data class Field(
        val name: String,
        val type: String,
        val description: String,
    )

    private fun schema(fields: List<Field>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                fields.forEach { field ->
                    putJsonObject(field.name) {
                        put("type", field.type)
                        put("description", field.description)
                    }
                }
            }
            putJsonArray("required") {
                required.forEach { add(JsonPrimitive(it)) }
            }
        }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.bool(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
