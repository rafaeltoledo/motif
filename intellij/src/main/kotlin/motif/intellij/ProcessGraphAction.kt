/*
 * Copyright (c) 2018-2019 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package motif.intellij

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementFactory
import motif.ast.IrClass
import motif.ast.intellij.IntelliJClass
import motif.core.ResolvedGraph
import motif.errormessage.ErrorMessage
import kotlin.system.measureTimeMillis

class ProcessGraphAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val duration = measureTimeMillis {
            val scopeClasses: List<PsiClass> = ScopeIndex.getInstance().getScopeClasses(project)
            val scopeAnnotatedClasses: List<IrClass> = scopeClasses
                    .map { psiClass ->
                        val psiClassType: PsiClassType = PsiElementFactory.SERVICE.getInstance(project).createType(psiClass)
                        IntelliJClass(project, psiClassType)
                    }
            val graph = ResolvedGraph.create(scopeAnnotatedClasses)
            log(ErrorMessage.toString(graph))
        }

        log("Processed graph in ${duration}ms.")
    }

    private fun log(message: String) {
        Notifications.Bus.notify(
                Notification("Motif", "Motif Graph", message, NotificationType.INFORMATION))
    }
}
