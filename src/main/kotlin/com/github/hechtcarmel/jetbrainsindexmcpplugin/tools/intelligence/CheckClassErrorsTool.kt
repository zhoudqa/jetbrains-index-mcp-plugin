package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ClassErrorCheckRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ClassErrorInfo
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

class CheckClassErrorsTool : AbstractMcpTool() {
    override val name: String = "ide_check_class_errors"
    override val description: String = "Check if a class has compilation or semantic errors in the project using PSI analysis"

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject("qualifiedName") {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Fully qualified name of the class to check (e.g., com.example.MyClass)")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive("qualifiedName"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val qualifiedName = arguments["qualifiedName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: qualifiedName")
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
        
        val request = ClassErrorCheckRequest(
            qualifiedName = qualifiedName,
            projectPath = projectPath
        )
        
        val service = ClassErrorCheckService(project)
        
        var result: ClassErrorInfo? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        
        service.checkClassErrors(request) { info ->
            result = info
            latch.countDown()
        }
        
        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                return createErrorResult("Timeout waiting for error check result")
            }
        } catch (e: InterruptedException) {
            return createErrorResult("Interrupted while waiting for error check: ${e.message}")
        }
        
        return createJsonResult(result!!)
    }
}
