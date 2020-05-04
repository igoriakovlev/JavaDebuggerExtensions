package org.jetbrains.plugins.smartGotoImplementation

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

abstract class SmartGotoImplementationExtension {

    data class ReceiverAndResolvedCall(val receiver: PsiElement?, val resolvedMethod: PsiMethod)

    companion object {
        val EP_NAME = ExtensionPointName.create<SmartGotoImplementationExtension>("org.jetbrains.plugins.smartGotoImplementationExtension")

        fun tryResolveCallExpression(element: PsiElement): ReceiverAndResolvedCall? =
                EP_NAME.extensions.firstNotNullResult { it.tryResolveCallExpression(element) }

        fun tryFindContainingClass(element: PsiElement): PsiClass? =
                EP_NAME.extensions.firstNotNullResult { it.tryFindContainingClass(element) }
    }

    abstract fun tryResolveCallExpression(element: PsiElement): ReceiverAndResolvedCall?

    abstract fun tryFindContainingClass(element: PsiElement): PsiClass?
}