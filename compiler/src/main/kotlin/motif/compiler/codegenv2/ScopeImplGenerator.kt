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
package motif.compiler.codegenv2

import com.squareup.javapoet.*
import motif.ast.IrClass
import motif.ast.compiler.CompilerClass
import motif.compiler.*
import motif.compiler.Dependencies
import motif.internal.None
import motif.models.*
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Modifier

class ScopeImplGenerator(
        private val env: ProcessingEnvironment,
        private val scope: Scope,
        private val scopeImplTypeName: ClassName,
        private val dependencies: Dependencies,
        private val childImpls: List<ChildImpl>) {

    private val packageName: String = scopeImplTypeName.packageName()
    private val scopeTypeName: ClassName = scope.clazz.typeName
    private val objectsImplName: ClassName = scopeImplTypeName.nestedClass("Objects")

    private val objectsField: FieldSpec? = scope.objects?.let {
        FieldSpec.builder(objectsImplName, "objects", Modifier.PRIVATE, Modifier.FINAL)
                .initializer(CodeBlock.of("new \$T()", objectsImplName))
                .build()
    }
    private val dependenciesField = FieldSpec.builder(
            dependencies.typeName,
            "dependencies",
            Modifier.PRIVATE, Modifier.FINAL)
            .build()

    private val methodNameScope = NameScope(blacklist = scope.scopeMethods.map { it.method.name })
    private val providerMethodNames = mutableMapOf<Type, String>()

    private val fieldNameScope = NameScope(blacklist = listOfNotNull(objectsField?.name, dependenciesField.name))

    fun create(): GeneratedClass {
        return object : GeneratedClass {
            override val packageName = this@ScopeImplGenerator.packageName
            override val spec = TypeSpec.classBuilder(scopeImplTypeName).apply {
                val cachedFields = cachedFields()
                addAnnotation(scopeImplAnnotation())
                addModifiers(Modifier.PUBLIC)
                addSuperinterface(scopeTypeName)
                objectsField?.let { addField(it) }
                addField(dependenciesField)
                addFields(cachedFields.values)
                addMethod(constructor(dependencies))
                alternateConstructor(dependencies)?.let { addMethod(it) }
                addMethods(accessMethodImpls())
                addMethods(childMethodImpls())
                addMethod(scopeProviderMethod())
                addMethods(factoryProviderMethods(cachedFields))
                addMethods(dependenciesProviderMethods())
                addType(dependencies.spec)
                objectsImpl()?.let { addType(it) }
            }.build()
        }
    }

    private fun scopeImplAnnotation(): AnnotationSpec {
        val builder = AnnotationSpec.builder(motif.ScopeImpl::class.java)
        if (scope.childMethods.isEmpty()) {
            builder.addMember("children", "{}")
        } else {
            scope.childMethods
                    .forEach { childMethod ->
                        val childScopeTypeName = childMethod.childScopeClass.typeName
                        builder.addMember("children", "\$T.class", childScopeTypeName)
                    }
        }
        return builder
                .addMember("scope", "\$T.class", scopeTypeName)
                .addMember("dependencies", "\$T.class", dependencies.typeName)
                .build()
    }

    private fun cachedFields(): Map<Type, FieldSpec> {
        val factoryMethods: List<FactoryMethod> = scope.objects?.factoryMethods ?: return emptyMap()
        return factoryMethods
                .filter { it.isCached }
                .map { factoryMethod ->
                    val returnType = factoryMethod.returnType.type
                    returnType to FieldSpec.builder(Object::class.java, fieldNameScope.name(returnType), Modifier.PRIVATE, Modifier.VOLATILE)
                            .initializer("\$T.NONE", None::class.java)
                            .build()
                }.toMap()
    }

    private fun accessMethodImpls(): List<MethodSpec> {
        return scope.accessMethods
                .map { accessMethod ->
                    overrideSpec(env, accessMethod.method)
                            .addStatement("return ${callProviderMethod(accessMethod.returnType)}")
                            .build()
                }
    }

    private fun childMethodImpls(): List<MethodSpec> {
        return childImpls.map(this::childMethodImpl)
    }

    private fun childMethodImpl(childImpl: ChildImpl): MethodSpec {
        val childMethod = childImpl.childEdge.method
        val childDependenciesImpl = TypeSpec.anonymousClassBuilder("").apply {
            addSuperinterface(childImpl.dependencies.typeName)
            childImpl.dependencies.getMethods().forEach { childDependencyMethod ->
                val abstractMethodSpec = childDependencyMethod.methodSpec
                val methodImpl = MethodSpec.methodBuilder(abstractMethodSpec.name).apply {
                    addModifiers(Modifier.PUBLIC)
                    returns(abstractMethodSpec.returnType)
                    addAnnotation(Override::class.java)
                    val returnType = childDependencyMethod.type
                    val childMethodParameter = childImpl.getParameter(returnType)
                    val returnStatement = if (childMethodParameter == null) {
                        CodeBlock.of("return \$T.this.${callProviderMethod(returnType)}", scopeImplTypeName)
                    } else {
                        CodeBlock.of("return \$N", childMethodParameter.parameter.name)
                    }
                    addStatement(returnStatement)
                }.build()
                addMethod(methodImpl)
            }
        }.build()
        return overrideWithFinalParamsSpec(childMethod.method)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("return new \$T(\$L)", childImpl.implTypeName, childDependenciesImpl)
                .build()
    }

    private fun scopeProviderMethod(): MethodSpec {
        val name = getProviderMethodName(Type(scope.clazz.type, null))
        return MethodSpec.methodBuilder(name)
                .returns(scopeTypeName)
                .addStatement("return this")
                .build()
    }

    private fun dependenciesProviderMethods(): List<MethodSpec> {
        return dependencies.getMethods()
                .map { method ->
                    MethodSpec.methodBuilder(getProviderMethodName(method.type))
                            .returns(method.type.typeName)
                            .addStatement("return \$N.\$N()", dependenciesField, method.methodSpec)
                            .build()
                }
    }

    private fun factoryProviderMethods(cachedFields: Map<Type, FieldSpec>): List<MethodSpec> {
        val factoryMethods: List<FactoryMethod> = scope.objects?.factoryMethods ?: return emptyList()
        return factoryMethods.flatMap { factoryMethod ->
            factoryProviderMethods(cachedFields, factoryMethod)
        }
    }

    private fun factoryProviderMethods(cachedFields: Map<Type, FieldSpec>, factoryMethod: FactoryMethod): List<MethodSpec> {
        val primaryReturnType = factoryMethod.returnType.type
        val primaryProviderMethod = MethodSpec.methodBuilder(getProviderMethodName(primaryReturnType))
                .returns(primaryReturnType.typeName)
                .addStatement(factoryProviderBody(cachedFields, factoryMethod))
                .build()
        return listOf(primaryProviderMethod) + spreadProviderMethods(factoryMethod)
    }

    private fun spreadProviderMethods(factoryMethod: FactoryMethod): List<MethodSpec> {
        val spread = factoryMethod.spread ?: return emptyList()
        val sourceType = spread.sourceType
        val callSourceProvider = callProviderMethod(sourceType)
        return spread.methods.map { method ->
            MethodSpec.methodBuilder(getProviderMethodName(method.returnType))
                    .returns(method.returnType.typeName)
                    .addStatement("return \$L.\$N()", callSourceProvider, method.name)
                    .build()
        }
    }

    private fun factoryProviderBody(cachedFields: Map<Type, FieldSpec>, factoryMethod: FactoryMethod): CodeBlock {
        val expression = factoryProviderExpression(factoryMethod)
        return if (factoryMethod.isCached) {
            val returnType = factoryMethod.returnType.type
            val cachedField = cachedFields.getValue(returnType)
            CodeBlock.builder()
                    .beginControlFlow("if (\$N == \$T.NONE)", cachedField, None::class.java)
                    .beginControlFlow("synchronized (this)")
                    .beginControlFlow("if (\$N == \$T.NONE)", cachedField, None::class.java)
                    .add("\$N = \$L;", cachedField, expression)
                    .endControlFlow()
                    .endControlFlow()
                    .endControlFlow()
                    .add("return (\$T) \$N", returnType.typeName, cachedField)
                    .build()
        } else {
            CodeBlock.of("return \$L", expression)
        }
    }

    private fun factoryProviderExpression(factoryMethod: FactoryMethod): CodeBlock {
        return when (factoryMethod) {
            is BasicFactoryMethod -> basicProviderExpression(factoryMethod)
            is ConstructorFactoryMethod -> constructorProviderExpression(factoryMethod)
            is BindsFactoryMethod -> bindsProviderExpression(factoryMethod)
            else -> throw UnsupportedOperationException()
        }
    }
    private fun basicProviderExpression(basicFactoryMethod: BasicFactoryMethod): CodeBlock {
        val callString = basicFactoryMethod.parameters.map { it.type }.callString()
        return if (basicFactoryMethod.isStatic) {
            CodeBlock.of("\$T.\$N$callString", objectsImplName, basicFactoryMethod.name)
        } else {
            CodeBlock.of("\$N.\$N$callString", objectsField, basicFactoryMethod.name)
        }
    }

    private fun constructorProviderExpression(constructorFactoryMethod: ConstructorFactoryMethod): CodeBlock {
        val callString = constructorFactoryMethod.parameters.map { it.type }.callString()
        return CodeBlock.of("new \$T$callString", constructorFactoryMethod.returnType.type.typeName)
    }

    private fun bindsProviderExpression(bindsFactoryMethod: BindsFactoryMethod): CodeBlock {
        return CodeBlock.of(callProviderMethod(bindsFactoryMethod.parameters[0].type))
    }

    private fun constructor(dependencies: Dependencies): MethodSpec {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(dependencies.typeName, "dependencies")
                .addStatement("this.\$N = dependencies", dependenciesField)
                .build()
    }

    private fun alternateConstructor(dependencies: Dependencies): MethodSpec? {
        if (!dependencies.isEmpty()) {
            return null
        }
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("this(new \$T() {})", dependencies.typeName)
                .build()
    }

    private fun objectsImpl(): TypeSpec? {
        val objects = scope.objects ?: return null
        val objectsClass = objects.clazz as CompilerClass
        val objectsName = objectsClass.typeName

        return TypeSpec.classBuilder(objectsImplName).apply {
            addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            if (objectsClass.kind == IrClass.Kind.INTERFACE) {
                addSuperinterface(objectsName)
            } else {
                superclass(objectsName)
            }
            scope.factoryMethods
                    .filter { it.method.isAbstract() }
                    .forEach {
                        addMethod(overrideSpec(env, it.method)
                                .addStatement("throw new \$T()", UnsupportedOperationException::class.java)
                                .build())
                    }
        }.build()
    }

    private fun List<Type>.callString(): String {
        val params = joinToString(", ") { callProviderMethod(it) }
        return "($params)"
    }

    private fun callProviderMethod(type: Type): String {
        return "${getProviderMethodName(type)}()"
    }

    private fun getProviderMethodName(type: Type) = providerMethodNames.computeIfAbsent(type) { methodNameScope.name(type) }
}