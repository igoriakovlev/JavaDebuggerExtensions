/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.smartGotoImplementation

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.actions.BaseCodeInsightAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.evaluation.CodeFragmentKind
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.Obsolescent
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.evaluate.quick.XDebuggerPsiEvaluator
import org.jetbrains.kotlin.psi.KtSuperExpression
import java.util.concurrent.TimeUnit

const val NAVIGATION_DURING_UPDATE_ERROR = "Navigation is not available here during index update"
const val TARGET_SHOULD_BE_PHYSICAL_ERROR = "Target element should be physical"
const val COULD_NOT_FIND_TARGET_ELEMENT_ERROR = "Could't find target element on caret"
const val INVALID_ELEMENT_TO_GOTO_ERROR = "Invalid element to smart goto implementation"
const val SMART_GOTO_ONLY_IN_DEBUG_MODE_ERROR = "Smart goto definition runs only with debug mode"
const val SMART_GOTO_MENU_ITEM = "Smart goto implementation"
const val ORDINAL_GOTO_MENU_ITEM = "Goto definition"
const val SMART_GOTO_MENU_TITLE = "Select goto action type"
const val CAN_NOT_GET_TYPE_FOR_VALUE_ERROR = "Can't get type for evaluated value"
const val CAN_NOT_FIND_TYPE_FOR_EVALUATED_VALUE_ERROR = "Can't find type for evaluated type"
const val CAN_NOT_FIND_TARGET_OVERRIDDEN_METHOD_ERROR = "Can't find target overridden method for evaluated type"
const val RECEIVER_EVALUATION_ERROR = "Receiver evaluation error"

class SmartGotoImplementationWithSelectionAction  : SmartGotoImplementationActionBase(withSelection = true)

class SmartGotoImplementationAction : SmartGotoImplementationActionBase(withSelection = false)

abstract class SmartGotoImplementationActionBase(private val withSelection: Boolean) : BaseCodeInsightAction(), CodeInsightActionHandler, DumbAware {

    private class EvaluatorListener(
            private val project: Project,
            private val receiverAndCall: SmartGotoImplementationExtension.ReceiverAndResolvedCall,
            private val editor: Editor,
            private val file: PsiFile
    ) : XDebuggerEvaluator.XEvaluationCallback, Obsolescent {

        override fun errorOccurred(errorMessage: String) =
                project.showCannotGotoNotification("$RECEIVER_EVALUATION_ERROR $errorMessage")

        override fun evaluated(result: XValue) {
            if (isObsolete) return

            val evaluatedType = (result as? JavaValue)
                    ?.descriptor
                    ?.value
                    ?.type()
                    ?: return project.showCannotGotoNotification(CAN_NOT_GET_TYPE_FOR_VALUE_ERROR)

            val typeClass = findClassByDebuggerType(evaluatedType, result.evaluationContext.debugProcess, project)
                    ?: return project.showCannotGotoNotification(CAN_NOT_FIND_TYPE_FOR_EVALUATED_VALUE_ERROR)

            laterPlease {
                readPlease {
                    val resultMethod = MethodSignatureUtil.findMethodBySignature(typeClass, receiverAndCall.resolvedMethod, true)
                    if (resultMethod != null) {
                        resultMethod.gotoMethod(editor, file)
                    } else {
                        project.showCannotGotoNotification(CAN_NOT_FIND_TARGET_OVERRIDDEN_METHOD_ERROR)
                    }
                }
            }
        }

        fun startObsoleteTimer() {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ isObsoleted = true }, 1, TimeUnit.SECONDS)
        }

        private var isObsoleted = false

        override fun isObsolete(): Boolean = isObsoleted
    }

    companion object {
        private val myNotificationGroup = NotificationGroup.balloonGroup("smartGoToImplementation-notification-group")

        fun Project.showCannotGotoNotification(cause: String) {
            laterPlease {
                myNotificationGroup.createNotification(cause, NotificationType.WARNING).notify(this)
            }
        }
    }

    override fun isValidForLookup(): Boolean = true

    override fun getHandler(): CodeInsightActionHandler = this

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        try {
            DumbService.getInstance(project).runWithAlternativeResolveEnabled<IndexNotReadyException> {
                getGotoDeclarationTarget(project, editor, file)
            }
        }
        catch (e: IndexNotReadyException) {
            DumbService.getInstance(project).showDumbModeNotification(NAVIGATION_DURING_UPDATE_ERROR)
        }
    }

    private fun getGotoDeclarationTarget(project: Project, editor: Editor, file: PsiFile) {

        val offset = editor.caretModel.offset

        if (TargetElementUtil.inVirtualSpace(editor, offset)) return project.showCannotGotoNotification(TARGET_SHOULD_BE_PHYSICAL_ERROR)

        val sourceElement = file.findElementAt(TargetElementUtil.adjustOffset(file, editor.document, offset))
                ?: return project.showCannotGotoNotification(COULD_NOT_FIND_TARGET_ELEMENT_ERROR)

        val receiverAndMethod =
                SmartGotoImplementationExtension.tryResolveCallExpression(sourceElement)
                    ?: return project.showCannotGotoNotification(INVALID_ELEMENT_TO_GOTO_ERROR)

        if (receiverAndMethod.receiver is KtSuperExpression || receiverAndMethod.resolvedMethod.hasModifierProperty("static")) {
            GotoDeclarationAction().invoke(project, editor, file)
            return
        }

        val evaluator = XDebuggerManager.getInstance(project).currentSession
                ?.debugProcess
                ?.evaluator
                ?: return project.showCannotGotoNotification(SMART_GOTO_ONLY_IN_DEBUG_MODE_ERROR)

        if (withSelection) {
            val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(listOf(SMART_GOTO_MENU_ITEM, ORDINAL_GOTO_MENU_ITEM))
                    .setTitle(SMART_GOTO_MENU_TITLE)
                    .setMovable(false)
                    .setResizable(false)
                    .setRequestFocus(true)
                    .setItemChosenCallback {
                        laterPlease {
                            when(it) {
                                SMART_GOTO_MENU_ITEM ->
                                    executeSmartGotoDeclaration(project, editor, file, receiverAndMethod, evaluator)
                                ORDINAL_GOTO_MENU_ITEM ->
                                    GotoDeclarationAction().invoke(project, editor, file)
                            }
                        }
                    }
                    .createPopup()

            laterPlease { popup.showInBestPositionFor(editor) }
        } else {
            executeSmartGotoDeclaration(project, editor, file, receiverAndMethod, evaluator)
        }
    }

    private fun executeSmartGotoDeclaration(
            project: Project,
            editor: Editor,
            file: PsiFile,
            receiverAndCall: SmartGotoImplementationExtension.ReceiverAndResolvedCall,
            evaluator: XDebuggerEvaluator
    ) {

        val evaluatorCallBack = EvaluatorListener(project, receiverAndCall, editor, file)

        val receiver = receiverAndCall.receiver
        val receiverText = receiver?.text ?: "this"

        if (receiver != null && evaluator is XDebuggerPsiEvaluator) {
            val textWithImports = TextWithImportsImpl(CodeFragmentKind.EXPRESSION, receiverText)
            val fragment = DebuggerUtilsEx.getCodeFragmentFactory(receiver, null)
                    .createCodeFragment(textWithImports, receiver, project)
            evaluatorCallBack.startObsoleteTimer()
            evaluator.evaluate(fragment, evaluatorCallBack)
        } else {
            val position = XDebuggerUtil.getInstance().createPositionByElement (receiver ?: file)
            evaluatorCallBack.startObsoleteTimer()
            evaluator.evaluate(receiverText, evaluatorCallBack, position)
        }
    }
}