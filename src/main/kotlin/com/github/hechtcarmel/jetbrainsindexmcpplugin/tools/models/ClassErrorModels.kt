package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models

import kotlinx.serialization.Serializable

@Serializable
data class ClassErrorCheckRequest(
    val qualifiedName: String,
    val projectPath: String? = null
)

@Serializable
data class ErrorLocation(
    val message: String,
    val line: Int,
    val column: Int,
    val endLine: Int? = null,
    val endColumn: Int? = null
)

@Serializable
data class ClassErrorInfo(
    val qualifiedName: String,
    val hasErrors: Boolean,
    val errorCount: Int,
    val checkedByCompile: Boolean = false,  // false: PSI 结果
    val errorType: String = "psi",  // "psi"
    val errors: List<ErrorLocation> = emptyList()
)
