package org.jetbrains.plugins.smartGotoImplementation

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.source.getPsi

class KotlinSupport : SmartGotoImplementationExtension {

    private val PsiElement.isValidElement get() = isValid && language == KotlinLanguage.INSTANCE

    override fun tryResolveCallExpression(element: PsiElement): SmartGotoImplementationExtension.ReceiverAndResolvedCall? = element.run {
        if (!isValidElement) return null

        val nameReferenceExpression = parent as? KtNameReferenceExpression ?: return null

        val resolvedMethodDescriptor =
                nameReferenceExpression.resolveToCall(BodyResolveMode.PARTIAL)
                        ?.candidateDescriptor
                        ?: return null

        val targetDescriptor = if (resolvedMethodDescriptor is PropertyDescriptor) resolvedMethodDescriptor.getter else resolvedMethodDescriptor
        if (targetDescriptor == null) return null

        val resolvedMethod = targetDescriptor
            .run { (source.getPsi() ?: DescriptorToSourceUtilsIde.getAnyDeclaration(project, this)) }
            ?.run { getRepresentativeLightMethod()}
            ?: return null

        val receiverExpression = when(val parentOfName = nameReferenceExpression.parent) {
            is KtDotQualifiedExpression -> parentOfName.receiverExpression
            is KtCallExpression -> (parentOfName.parent as? KtDotQualifiedExpression)?.receiverExpression
            else -> null
        }

        SmartGotoImplementationExtension.ReceiverAndResolvedCall(receiverExpression, resolvedMethod)
    }

    override fun tryFindContainingClass(element: PsiElement): PsiClass? =
            if (element.isValidElement) PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)?.toLightClass() else null
}