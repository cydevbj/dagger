/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.SourceFiles.simpleVariableName;
import static dagger.internal.codegen.TypeSpecs.addSupertype;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.type.TypeKind.VOID;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.Preconditions;
import dagger.internal.codegen.ComponentDescriptor.BuilderRequirementMethod;
import dagger.internal.codegen.ComponentDescriptor.BuilderSpec;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Models the generated code for a component builder. */
final class GeneratedComponentBuilderModel {
  private final TypeSpec typeSpec;
  private final ClassName name;
  private final ImmutableMap<ComponentRequirement, FieldSpec> builderFields;

  private GeneratedComponentBuilderModel(
      TypeSpec typeSpec,
      ClassName name,
      ImmutableMap<ComponentRequirement, FieldSpec> builderFields) {
    this.typeSpec = typeSpec;
    this.name = name;
    this.builderFields = builderFields;
  }

  TypeSpec typeSpec() {
    return typeSpec;
  }

  ClassName name() {
    return name;
  }

  ImmutableMap<ComponentRequirement, FieldSpec> builderFields() {
    return builderFields;
  }

  static Optional<GeneratedComponentBuilderModel> create(
      GeneratedComponentModel generatedComponentModel,
      BindingGraph graph,
      Elements elements,
      Types types) {
    return hasBuilder(graph.componentDescriptor())
        ? Optional.of(new Creator(generatedComponentModel, graph, elements, types).create())
        : Optional.empty();
  }

  private static boolean hasBuilder(ComponentDescriptor component) {
    return component.kind().isTopLevel() || component.builderSpec().isPresent();
  }

  private static final class Creator {
    private static final String NOOP_BUILDER_METHOD_JAVADOC =
        "This module is declared, but an instance is not used in the component. This method is a "
            + "no-op. For more, see https://google.github.io/dagger/unused-modules.\n";
    private final BindingGraph graph;
    private final TypeSpec.Builder builder;
    private final GeneratedComponentModel generatedComponentModel;
    private final Elements elements;
    private final Types types;

    Creator(
        GeneratedComponentModel generatedComponentModel,
        BindingGraph graph,
        Elements elements,
        Types types) {
      this.generatedComponentModel = generatedComponentModel;
      this.builder = classBuilder(generatedComponentModel.getBuilderName());
      this.graph = graph;
      this.elements = elements;
      this.types = types;
    }

    GeneratedComponentBuilderModel create() {
      if (!generatedComponentModel.isNested()) {
        builder.addModifiers(STATIC);
      }
      if (builderSpec().isPresent()) {
        if (generatedComponentModel.isAbstract()) {
          builder.addModifiers(PROTECTED);
        } else {
          builder.addModifiers(PRIVATE);
        }
        setSupertype();
      } else {
        builder.addModifiers(PUBLIC).addMethod(constructorBuilder().addModifiers(PRIVATE).build());
      }

      ImmutableMap<ComponentRequirement, FieldSpec> builderFields = builderFields(graph);

      if (generatedComponentModel.isAbstract()) {
        builder.addModifiers(ABSTRACT);
      } else {
        builder.addModifiers(FINAL);
        builder.addMethod(buildMethod(builderFields)); // Can only instantiate concrete classes.
      }

      builder
          .addFields(builderFields.values())
          // TODO(ronshapiro): this should be switched with buildMethod(), but that currently breaks
          // compile-testing tests that rely on the order of the methods
          .addMethods(builderMethods(builderFields));

      return new GeneratedComponentBuilderModel(
          builder.build(), generatedComponentModel.getBuilderName(), builderFields);
    }

    /** Set the superclass being extended or interface being implemented for this builder. */
    private void setSupertype() {
      if (generatedComponentModel.supermodel().isPresent()) {
        // If there's a superclass, extend the Builder defined there.
        builder.superclass(generatedComponentModel.supermodel().get().getBuilderName());
      } else {
        addSupertype(builder, builderSpec().get().builderDefinitionType());
      }
    }

    /**
     * Computes fields for each of the {@linkplain BindingGraph#componentRequirements component
     * requirements}. Regardless of builder spec, there is always one field per requirement.
     */
    private static ImmutableMap<ComponentRequirement, FieldSpec> builderFields(BindingGraph graph) {
      UniqueNameSet fieldNames = new UniqueNameSet();
      ImmutableMap.Builder<ComponentRequirement, FieldSpec> builderFields = ImmutableMap.builder();
      for (ComponentRequirement componentRequirement : graph.componentRequirements()) {
        String name = fieldNames.getUniqueName(componentRequirement.variableName());
        builderFields.put(
            componentRequirement,
            FieldSpec.builder(TypeName.get(componentRequirement.type()), name, PRIVATE).build());
      }
      return builderFields.build();
    }

    private MethodSpec buildMethod(ImmutableMap<ComponentRequirement, FieldSpec> builderFields) {
      MethodSpec.Builder buildMethod;
      if (builderSpec().isPresent()) {
        ExecutableElement specBuildMethod = builderSpec().get().buildMethod();
        // Note: we don't use the specBuildMethod.getReturnType() as the return type
        // because it might be a type variable.  We make use of covariant returns to allow
        // us to return the component type, which will always be valid.
        buildMethod =
            methodBuilder(specBuildMethod.getSimpleName().toString()).addAnnotation(Override.class);
      } else {
        buildMethod = methodBuilder("build");
      }
      buildMethod.returns(ClassName.get(graph.componentType())).addModifiers(PUBLIC);

      builderFields.forEach(
          (requirement, builderField) -> {
            switch (requirement.nullPolicy(elements, types)) {
              case NEW:
                buildMethod.addCode(
                    "if ($1N == null) { this.$1N = new $2T(); }", builderField, builderField.type);
                break;
              case THROW:
                buildMethod.addCode(
                    "if ($N == null) { throw new $T($T.class.getCanonicalName() + $S); }",
                    builderField,
                    IllegalStateException.class,
                    TypeNames.rawTypeName(builderField.type),
                    " must be set");
                break;
              case ALLOW:
                break;
              default:
                throw new AssertionError(requirement);
            }
          });
      buildMethod.addStatement("return new $T(this)", generatedComponentModel.name());
      return buildMethod.build();
    }

    /**
     * Computes the methods that set each of parameters on the builder. If the {@link BuilderSpec}
     * is present, it will tailor the methods to match the spec.
     */
    private ImmutableSet<MethodSpec> builderMethods(
        ImmutableMap<ComponentRequirement, FieldSpec> builderFields) {
      ImmutableSet<ComponentRequirement> componentRequirements = graph.componentRequirements();
      ImmutableSet.Builder<MethodSpec> methods = ImmutableSet.builder();
      if (builderSpec().isPresent()) {
        UniqueNameSet parameterNames = new UniqueNameSet();
        for (BuilderRequirementMethod requirementMethod :
            builderSpec().get().requirementMethods()) {
          ComponentRequirement builderRequirement = requirementMethod.requirement();
          ExecutableElement specMethod = requirementMethod.method();
          MethodSpec.Builder builderMethod = addBuilderMethodFromSpec(specMethod);
          VariableElement parameterElement = Iterables.getOnlyElement(specMethod.getParameters());
          String parameterName = parameterNames.getUniqueName(parameterElement.getSimpleName());

          TypeName argType =
              parameterElement.asType().getKind().isPrimitive()
                  // Primitives need to use the original (unresolved) type to avoid boxing.
                  ? TypeName.get(parameterElement.asType())
                  // Otherwise we use the full resolved type.
                  : TypeName.get(builderRequirement.type());

          builderMethod.addParameter(argType, parameterName);
          if (componentRequirements.contains(builderRequirement)) {
            // required type
            builderMethod.addStatement(
                "this.$N = $L",
                builderFields.get(builderRequirement),
                builderRequirement
                        .nullPolicy(elements, types)
                        .equals(ComponentRequirement.NullPolicy.ALLOW)
                    ? parameterName
                    : CodeBlock.of("$T.checkNotNull($L)", Preconditions.class, parameterName));
            addBuilderMethodReturnStatementForSpec(specMethod, builderMethod);
          } else if (graph.ownedModuleTypes().contains(builderRequirement.typeElement())) {
            // owned, but not required
            builderMethod.addJavadoc(NOOP_BUILDER_METHOD_JAVADOC);
            addBuilderMethodReturnStatementForSpec(specMethod, builderMethod);
          } else {
            // neither owned nor required, so it must be an inherited module
            builderMethod.addStatement(
                "throw new $T($T.format($S, $T.class.getCanonicalName()))",
                UnsupportedOperationException.class,
                String.class,
                "%s cannot be set because it is inherited from the enclosing component",
                TypeNames.rawTypeName(TypeName.get(builderRequirement.type())));
          }
          methods.add(builderMethod.build());
        }
      } else {
        for (ComponentRequirement componentRequirement :
            graph.componentDescriptor().availableDependencies()) {
          String componentRequirementName = simpleVariableName(componentRequirement.typeElement());
          MethodSpec.Builder builderMethod =
              methodBuilder(componentRequirementName)
                  .returns(generatedComponentModel.getBuilderName())
                  .addModifiers(PUBLIC)
                  .addParameter(
                      TypeName.get(componentRequirement.type()), componentRequirementName);
          if (componentRequirements.contains(componentRequirement)) {
            builderMethod.addStatement(
                "this.$N = $T.checkNotNull($L)",
                builderFields.get(componentRequirement),
                Preconditions.class,
                componentRequirementName);
          } else {
            builderMethod.addStatement(
                "$T.checkNotNull($L)", Preconditions.class, componentRequirementName);
            builderMethod.addJavadoc("@deprecated " + NOOP_BUILDER_METHOD_JAVADOC);
            builderMethod.addAnnotation(Deprecated.class);
          }
          builderMethod.addStatement("return this");
          methods.add(builderMethod.build());
        }
      }
      return methods.build();
    }

    private MethodSpec.Builder addBuilderMethodFromSpec(ExecutableElement method) {
      TypeMirror returnType = method.getReturnType();
      MethodSpec.Builder builderMethod =
          methodBuilder(method.getSimpleName().toString())
              .addAnnotation(Override.class)
              .addModifiers(Sets.difference(method.getModifiers(), ImmutableSet.of(ABSTRACT)));
      // If the return type is void, we add a method with the void return type.
      // Otherwise we use the generated builder name and take advantage of covariant returns
      // (so that we don't have to worry about setter methods that return type variables).
      if (!returnType.getKind().equals(VOID)) {
        builderMethod.returns(generatedComponentModel.getBuilderName());
      }
      return builderMethod;
    }

    private static void addBuilderMethodReturnStatementForSpec(
        ExecutableElement specMethod, MethodSpec.Builder builderMethod) {
      if (!specMethod.getReturnType().getKind().equals(VOID)) {
        builderMethod.addStatement("return this");
      }
    }

    private Optional<BuilderSpec> builderSpec() {
      return graph.componentDescriptor().builderSpec();
    }
  }
}
