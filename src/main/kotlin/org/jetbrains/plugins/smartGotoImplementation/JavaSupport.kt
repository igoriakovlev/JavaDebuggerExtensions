package org.jetbrains.plugins.smartGotoImplementation

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

class JavaSupport : SmartGotoImplementationExtension() {

    private val PsiElement.isValidElement get() = isValid && language == JavaLanguage.INSTANCE

    override fun tryResolveCallExpression(element: PsiElement): ReceiverAndResolvedCall? = element.run {
        if (!isValidElement) return null

        val callExpression =
                (this as? PsiIdentifier)
                ?.run { parent.parent as? PsiMethodCallExpression }
                ?: return null

        val receiver = callExpression.methodExpression.qualifierExpression

        val resolvedMethod = callExpression.resolveMethod() ?: return null

        ReceiverAndResolvedCall(receiver, resolvedMethod)
    }

    override fun tryFindContainingClass(element: PsiElement): PsiClass? =
            if (element.isValidElement) PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) else null
}