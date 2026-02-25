package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class CheckClassErrorsToolTest : BasePlatformTestCase() {

    @Test
    fun testCheckClassErrorsToolMissingQualifiedName() = runBlocking {
        val tool = CheckClassErrorsTool()

        val result = tool.execute(project, buildJsonObject {})

        assertTrue("Should error with missing qualifiedName", result.isError)
    }

    @Test
    fun testCheckClassErrorsToolWithNonExistentClass() = runBlocking {
        val tool = CheckClassErrorsTool()

        val result = tool.execute(project, buildJsonObject {
            put("qualifiedName", "com.nonexistent.Class123")
        })

        assertFalse("Should succeed even for non-existent class", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    @Test
    fun testCheckClassErrorsToolWithValidClass() = runBlocking {
        val tool = CheckClassErrorsTool()

        val result = tool.execute(project, buildJsonObject {
            put("qualifiedName", "java.lang.String")
        })

        assertFalse("Should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    @Test
    fun testCheckClassErrorsToolWithProjectPath() = runBlocking {
        val tool = CheckClassErrorsTool()

        val result = tool.execute(project, buildJsonObject {
            put("qualifiedName", "java.util.List")
            put("projectPath", project.basePath)
        })

        assertFalse("Should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())
    }
}
