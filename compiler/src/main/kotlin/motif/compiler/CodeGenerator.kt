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
package motif.compiler

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeSpec
import motif.core.ResolvedGraph
import motif.models.Scope
import java.lang.IllegalStateException
import javax.annotation.processing.ProcessingEnvironment

interface GeneratedClass {

    val packageName: String
    val spec: TypeSpec
}

class CodeGenerator(
        private val env: ProcessingEnvironment,
        private val graph: ResolvedGraph) {

    private val dependencies = mutableMapOf<Scope, Dependencies>()
    private val implTypeNames = mutableMapOf<Scope, ClassName>()

    private fun getGeneratedClasses(): List<GeneratedClass> {
        return getScopeImpls() + getScopeFactoryHelpers()
    }

    private fun getScopeImpls(): List<ScopeImpl> {
        return graph.scopes
                .filter { scope ->
                    val scopeImplName = getImplTypeName(scope)
                    env.elementUtils.getTypeElement(scopeImplName.toString()) == null
                }
                .map { scope ->
                    val childEdges = graph.getChildEdges(scope)
                            .map { childEdge ->
                                val childImplTypeName = getImplTypeName(childEdge.child)
                                val dependencies = getDependencies(childEdge.child)
                                ChildImpl(childEdge, dependencies, childImplTypeName)
                            }
                    ScopeImplFactory(
                            env,
                            graph,
                            scope,
                            getImplTypeName(scope),
                            getDependencies(scope),
                            childEdges).create()
                }
    }

    private fun getScopeFactoryHelpers(): List<ScopeFactoryHelper> {
        return graph.scopeFactories
                .map { scopeFactory ->
                    val scope = graph.getScope(scopeFactory.scopeClass)
                            ?: throw IllegalStateException("Could not find Scope for class: ${scopeFactory.scopeClass}")
                    val scopeImplTypeName = getImplTypeName(scope)
                    val dependencies = getDependencies(scope)
                    ScopeFactoryHelperFactory(scopeFactory, scopeImplTypeName, dependencies).create()
                }
    }

    private fun getDependencies(scope: Scope): Dependencies {
        return dependencies.computeIfAbsent(scope) {
            val implTypeName = getImplTypeName(scope)
            Dependencies.create(graph, scope, implTypeName)
        }
    }

    private fun getImplTypeName(scope: Scope): ClassName {
        return implTypeNames.computeIfAbsent(scope) {
            val scopeTypeName = scope.clazz.typeName
            val prefix = scopeTypeName.simpleNames().joinToString("")
            ClassName.get(scopeTypeName.packageName(), "${prefix}Impl")
        }
    }

    companion object {

        fun generate(env: ProcessingEnvironment, graph: ResolvedGraph): List<GeneratedClass> {
            return CodeGenerator(env, graph).getGeneratedClasses()
        }
    }
}