package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ClassLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassByFqnResult
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for finding a class by its fully qualified name (FQN).
 *
 * Uses JavaPsiFacade.findClass() with GlobalSearchScope.allScope() to find classes
 * from both project source and library dependencies (including JAR files).
 *
 * Returns class metadata (package name, file path, JAR path) and optionally
 * includes source code or decompiled source code for classes from JARs.
 *
 * Equivalent to IntelliJ's "Go to Class" (Ctrl+N / Cmd+O) with exact FQN match.
 */
@Suppress("unused")
class FindClassByFqnTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<FindClassByFqnTool>()
        private const val DEFAULT_MAX_SOURCE_LENGTH = 20_000
    }

    override val name = ToolNames.FIND_CLASS_BY_FQN

    override val description = """
        Find a class by its fully qualified name (FQN) from project source or library dependencies (including JARs).

        This tool provides exact FQN matching and can return:
        - Class metadata: package name, file path, JAR path (if from library)
        - Source code: from project source files
        - Decompiled source code: from JAR files (using IntelliJ's built-in decompiler)

        Parameters:
        - qualifiedName (required): Fully qualified class name (e.g., "java.util.ArrayList")
        - includeSource (optional, default: true): Include source/decompiled source code in the response
        - maxSourceLength (optional, default: 20000): Maximum characters of source code to return

        Example: {"qualifiedName": "java.util.ArrayList", "includeSource": true}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.QUALIFIED_NAME) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Fully qualified class name (e.g., 'java.util.ArrayList')")
            }
            putJsonObject(ParamNames.INCLUDE_SOURCE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "Include source/decompiled source code in the response. Default: true.")
            }
            putJsonObject(ParamNames.MAX_SOURCE_LENGTH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum characters of source code to return. Default: 20000.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.QUALIFIED_NAME))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val qualifiedName = arguments[ParamNames.QUALIFIED_NAME]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUALIFIED_NAME}")
        val includeSource = arguments[ParamNames.INCLUDE_SOURCE]?.jsonPrimitive?.boolean ?: true
        val maxSourceLength = arguments[ParamNames.MAX_SOURCE_LENGTH]?.jsonPrimitive?.int
            ?: DEFAULT_MAX_SOURCE_LENGTH

        if (qualifiedName.isBlank()) {
            return createErrorResult("Qualified name cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)

            val psiClass = facade.findClass(qualifiedName, scope)

            if (psiClass == null) {
                createJsonResult(FindClassByFqnResult(
                    qualifiedName = qualifiedName,
                    found = false,
                    results = emptyList()
                ))
            } else {
                val location = buildLocationFromPsiClass(psiClass, project, includeSource, maxSourceLength)
                createJsonResult(FindClassByFqnResult(
                    qualifiedName = qualifiedName,
                    found = true,
                    results = listOf(location)
                ))
            }
        }
    }

    private fun buildLocationFromPsiClass(
        psiClass: PsiClass,
        project: Project,
        includeSource: Boolean,
        maxSourceLength: Int
    ): ClassLocation {
        val psiFile = psiClass.containingFile
        val vFile: VirtualFile? = psiFile?.virtualFile

        var filePath: String? = null
        var jarPath: String? = null
        var isFromSource = false

        if (vFile != null) {
            filePath = vFile.path // jar://...!/com/example/Foo.class or /path/to/Foo.java

            val fs = vFile.fileSystem
            if (fs is JarFileSystem) {
                // From JAR (possibly decompiled)
                val localFile = fs.getLocalByEntry(vFile)
                jarPath = localFile?.path
                isFromSource = false
            } else {
                // From source or compiled output directory
                isFromSource = vFile.extension.equals("java", ignoreCase = true)
            }
        }

        // Read source or decompiled source
        var source: String? = null
        var truncated = false

        if (includeSource && psiFile != null) {
            val fullText = psiFile.text ?: ""
            if (fullText.length > maxSourceLength) {
                source = fullText.substring(0, maxSourceLength)
                truncated = true
            } else {
                source = fullText
                truncated = false
            }
        }

        val qualifiedName = psiClass.qualifiedName ?: ""
        val packageName = psiClass.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")
        val className = psiClass.name ?: qualifiedName.substringAfterLast('.')

        return ClassLocation(
            fqn = qualifiedName,
            packageName = packageName,
            className = className,
            filePath = filePath,
            jarPath = jarPath,
            isFromSource = isFromSource,
            language = "JAVA",
            source = source,
            sourceTruncated = truncated
        )
    }
}
