package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ClassErrorCheckRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ClassErrorInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ErrorLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Document

class ClassErrorCheckService(private val project: Project) {

    /**
     * 统一入口：检查类的错误。
     * 目前仅支持 PSI 高亮检查（快速）。
     */
    fun checkClassErrors(
        request: ClassErrorCheckRequest,
        callback: (ClassErrorInfo) -> Unit
    ) {
        val psiClass = findPsiClass(request.qualifiedName)
        if (psiClass == null) {
            callback(
                ClassErrorInfo(
                    qualifiedName = request.qualifiedName,
                    hasErrors = true,
                    errorCount = 1,
                    checkedByCompile = false,
                    errorType = "psi",
                    errors = listOf(
                        ErrorLocation(
                            message = "Class not found in project/index",
                            line = 0,
                            column = 0
                        )
                    )
                )
            )
            return
        }

        // 仅 PSI 高亮检查（快速）
        val info = checkClassErrorsByPsi(psiClass, request)
        callback(info)
    }

    // ---------- 公共辅助 ----------

    private fun findPsiClass(fqn: String): PsiClass? {
        return ApplicationManager.getApplication().runReadAction<PsiClass?> {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)
            facade.findClass(fqn, scope)
        }
    }

    // ---------- 基于 PSI 高亮 ----------

    private fun checkClassErrorsByPsi(
        psiClass: PsiClass,
        request: ClassErrorCheckRequest
    ): ClassErrorInfo {
        val psiFile = psiClass.containingFile ?: return ClassErrorInfo(
            qualifiedName = request.qualifiedName,
            hasErrors = true,
            errorCount = 1,
            checkedByCompile = false,
            errorType = "psi",
            errors = listOf(
                ErrorLocation(
                    message = "PsiFile not found for class",
                    line = 0,
                    column = 0
                )
            )
        )

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return ClassErrorInfo(
                qualifiedName = request.qualifiedName,
                hasErrors = false,
                errorCount = 0,
                checkedByCompile = false,
                errorType = "psi",
                errors = emptyList()
            )

        // 触发/刷新高亮分析
        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)

        val errors = mutableListOf<HighlightInfo>()

        // 通常需要在 EDT 上调用高亮信息获取
        ApplicationManager.getApplication().invokeAndWait {
            val editor = EditorFactory.getInstance().createViewer(document, project)
            try {
                val infos = DaemonCodeAnalyzerImpl.getHighlights(
                    document,
                    null,
                    project
                )
                val classRange = psiClass.textRange
                for (info in infos) {
                    if (info.severity == HighlightSeverity.ERROR) {
                        // 限定在该类范围内（也可以选择整个文件）
                        if (classRange == null || classRange.contains(info.startOffset)) {
                            errors += info
                        }
                    }
                }
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }

        return if (errors.isEmpty()) {
            ClassErrorInfo(
                qualifiedName = request.qualifiedName,
                hasErrors = false,
                errorCount = 0,
                checkedByCompile = false,
                errorType = "psi",
                errors = emptyList()
            )
        } else {
            val errorLocations = errors.map { highlightInfo ->
                val line = document.getLineNumber(highlightInfo.startOffset) + 1  // 转换为1-based
                val column = highlightInfo.startOffset - document.getLineStartOffset(line - 1) + 1
                val endLine = document.getLineNumber(highlightInfo.endOffset) + 1
                val endColumn = highlightInfo.endOffset - document.getLineStartOffset(endLine - 1) + 1
                ErrorLocation(
                    message = highlightInfo.description,
                    line = line,
                    column = column,
                    endLine = endLine,
                    endColumn = endColumn
                )
            }
            ClassErrorInfo(
                qualifiedName = request.qualifiedName,
                hasErrors = true,
                errorCount = errors.size,
                checkedByCompile = false,
                errorType = "psi",
                errors = errorLocations
            )
        }
    }
}
