/*
 * Copyright © 2026 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.pkl.core.PType;
import org.pkl.core.PklBugException;
import org.pkl.core.TypeParameter;
import org.pkl.core.ValueFormatter;
import org.pkl.core.util.LateInit;
import org.pkl.core.util.MutableBoolean;

public abstract class VmType extends VmValue {

  public boolean isFinalType() {
    var ret = new MutableBoolean(true);
    acceptType(
        true,
        type -> {
          // assumption: don't need to worry about non-final `ClassType`
          if (type instanceof SelfType moduleType && !moduleType.isFinalType()) {
            ret.set(false);
            return false;
          }
          return true;
        });
    return ret.get();
  }

  /** Visit child types of this type. */
  public abstract boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer);

  @Override
  public PType export() {
    var alias = getTypeAliasRepr();
    // needs to come before `clazz != null` check
    if (alias != null) {
      return new PType.Alias(alias.export());
    }
    var clazz = getClassRepr();
    if (clazz != null) {
      return new PType.Class(clazz.export());
    }
    CompilerDirectives.transferToInterpreter();
    throw new VmExceptionBuilder()
        .bug("`%s` must override method `doExport()`.", getClass().getTypeName())
        .build();
  }

  public boolean isParametric() {
    return false;
  }

  /** Tells if this type is the same typecheck as the other type. */
  @Override
  public boolean equals(Object value) {
    return this == value || value instanceof VmType other && doIsEquivalentTo(other);
  }

  public @Nullable VmClass getClassRepr() {
    return null;
  }

  public @Nullable VmTypeAlias getTypeAliasRepr() {
    return null;
  }

  @Override
  public VmClass getVmClass() {
    throw PklBugException.unreachableCode();
  }

  @Override
  public void force(boolean allowUndefinedValues) {
    // do nothing
  }

  @Override
  public void accept(VmValueVisitor visitor) {
    visitor.visitType(this);
  }

  @Override
  public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
    return converter.convertType(this, path);
  }

  protected abstract boolean doIsEquivalentTo(VmType other);

  public static final class UnknownType extends VmType {
    public UnknownType() {}

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof UnknownType;
    }

    @Override
    public PType export() {
      return PType.UNKNOWN;
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    public String toString() {
      return "unknown";
    }
  }

  public static final class NothingType extends VmType {
    public NothingType() {}

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof NothingType;
    }

    @Override
    public PType export() {
      return PType.NOTHING;
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    public String toString() {
      return "nothing";
    }
  }

  public abstract static class SelfType extends VmType {
    protected final VmClass clazz;

    protected SelfType(VmClass clazz) {
      this.clazz = clazz;
    }

    @Override
    public VmClass getClassRepr() {
      return clazz;
    }

    @Override
    public boolean isFinalType() {
      return clazz.isClosed();
    }
  }

  public static final class ModuleType extends SelfType {
    public ModuleType(VmClass clazz) {
      super(clazz);
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof ModuleType t && clazz == t.clazz;
    }

    @Override
    public PType export() {
      return PType.MODULE;
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    public String toString() {
      return "module";
    }
  }

  public static final class StringLiteralType extends VmType {
    private final String literal;

    public StringLiteralType(String literal) {
      this.literal = literal;
    }

    public String getLiteral() {
      return literal;
    }

    @Override
    public PType export() {
      return new PType.StringLiteral(literal);
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof StringLiteralType t && literal.equals(t.literal);
    }

    @Override
    public String toString() {
      return ValueFormatter.basic().formatStringValue(literal, "");
    }
  }

  public static final class ClassType extends VmType {
    private final VmClass clazz;
    private final VmType[] typeArguments;

    public ClassType(VmClass clazz) {
      this.clazz = clazz;
      typeArguments = new VmType[0];
    }

    public ClassType(VmClass clazz, VmType typeArgument) {
      this.clazz = clazz;
      typeArguments = new VmType[] {typeArgument};
    }

    public ClassType(VmClass clazz, VmType typeArgument1, VmType typeArgument2) {
      this.clazz = clazz;
      typeArguments = new VmType[] {typeArgument1, typeArgument2};
    }

    public ClassType(VmClass clazz, VmType[] typeArguments) {
      this.clazz = clazz;
      this.typeArguments = typeArguments;
    }

    @Override
    public VmClass getClassRepr() {
      return clazz;
    }

    @Override
    public boolean isParametric() {
      return typeArguments.length > 0;
    }

    public VmType[] getTypeArguments() {
      return typeArguments;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      if (!(other instanceof ClassType t)) return false;
      if (clazz != t.clazz) return false;
      return typesEquals(typeArguments, t.typeArguments);
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this) && !visitTypeArguments
          || (clazz.isSubclassOf(
                  BaseModule.getFunctionClass()) // do not visit args for function types
              || acceptTypes(typeArguments, visitTypeArguments, consumer));
    }

    @Override
    public PType export() {
      return new PType.Class(clazz.export(), exportTypes(typeArguments));
    }

    @Override
    public String toString() {
      var result = clazz.getDisplayName();
      if (typeArguments.length > 0) {
        result +=
            "<"
                + Arrays.stream(typeArguments)
                    .map(Object::toString)
                    .collect(Collectors.joining(", "))
                + ">";
      }
      return result;
    }
  }

  public static final class NullableType extends VmType {
    private final VmType elementType;

    public NullableType(VmType elementType) {
      this.elementType = elementType;
    }

    public VmType getElementType() {
      return elementType;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof NullableType t && elementType.equals(t.elementType);
    }

    @Override
    public PType export() {
      return new PType.Nullable(elementType.export());
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this) && elementType.acceptType(visitTypeArguments, consumer);
    }

    @Override
    public String toString() {
      return elementType instanceof FunctionType || elementType instanceof UnionType
          ? "(" + elementType + ")?"
          : elementType + "?";
    }
  }

  public static final class ConstrainedType extends VmType {
    private final VmType baseType;
    private final String[] constraints;

    public ConstrainedType(VmType baseType, String[] constraints) {
      this.baseType = baseType;
      this.constraints = constraints;
    }

    public VmType getBaseType() {
      return baseType;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      // consider constrained types as always different
      return false;
    }

    @Override
    public PType export() {
      return new PType.Constrained(baseType.export(), Arrays.asList(constraints));
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      if (!consumer.accept(this)) {
        return false;
      }
      return baseType.acceptType(visitTypeArguments, consumer);
    }

    @Override
    public String toString() {
      return (baseType instanceof FunctionType || baseType instanceof UnionType
              ? "(" + baseType + ")"
              : baseType)
          + "("
          + String.join(", ", constraints)
          + ")";
    }
  }

  public static final class AliasType extends VmType {
    private final VmTypeAlias typeAlias;
    private final @Nullable VmClass clazz;
    private final VmType[] typeArguments;
    @LateInit private VmType aliasedType = null;

    /** For intrinsified typealiases with no inherent class (e.g. NonNull) */
    public AliasType(VmTypeAlias typeAlias) {
      this.typeAlias = typeAlias;
      this.typeArguments = new VmType[0];
      this.aliasedType = typeAlias.getTypeNode().getType();
      this.clazz = null;
    }

    /** For intrinsified typealiases with an inherent class (e.g. Int aliases) */
    public AliasType(VmTypeAlias typeAlias, VmClass clazz) {
      this.typeAlias = typeAlias;
      this.typeArguments = new VmType[0];
      this.aliasedType = typeAlias.getTypeNode().getType();
      this.clazz = clazz;
    }

    /** For arbitrary typealiases */
    public AliasType(VmTypeAlias typeAlias, VmType[] typeArguments) {
      this.typeAlias = typeAlias;
      this.typeArguments = typeArguments;
      this.clazz = null;
    }

    public void initAliasedType(VmType aliasedType) {
      this.aliasedType = aliasedType;
    }

    @Override
    public VmTypeAlias getTypeAliasRepr() {
      return typeAlias;
    }

    public VmType getAliasedType() {
      return aliasedType;
    }

    @Override
    public @Nullable VmClass getClassRepr() {
      return clazz;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      if ((other instanceof AliasType aliasType)) {
        return aliasedType.equals(aliasType.aliasedType);
      }
      return aliasedType.equals(other);
    }

    @Override
    public PType export() {
      return new PType.Alias(typeAlias.export(), exportTypes(typeArguments), aliasedType.export());
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this) && aliasedType.acceptType(visitTypeArguments, consumer);
    }

    @Override
    public boolean isParametric() {
      return typeArguments.length > 0;
    }

    @Override
    public String toString() {
      var result = typeAlias.getDisplayName();
      if (typeArguments.length > 0) {
        result +=
            "<"
                + Arrays.stream(typeArguments)
                    .map(Object::toString)
                    .collect(Collectors.joining(", "))
                + ">";
      }
      return result;
    }
  }

  public static final class FunctionType extends VmType {
    private final VmType[] parameterTypes;
    private final VmType returnType;

    public FunctionType(VmType[] parameterTypes, VmType returnType) {
      this.parameterTypes = parameterTypes;
      this.returnType = returnType;
    }

    public VmType[] getParameterTypes() {
      return parameterTypes;
    }

    public VmType getReturnType() {
      return returnType;
    }

    @Override
    public VmClass getClassRepr() {
      return BaseModule.getFunctionNClass(parameterTypes.length);
    }

    @Override
    public boolean isParametric() {
      return true;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      if (!(other instanceof FunctionType t)) return false;
      if (!returnType.equals(t.returnType)) return false;
      return typesEquals(parameterTypes, t.parameterTypes);
    }

    @Override
    public PType export() {
      return new PType.Function(exportTypes(parameterTypes), returnType.export());
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    public String toString() {
      return "("
          + Arrays.stream(parameterTypes).map(Object::toString).collect(Collectors.joining(", "))
          + ") -> "
          + returnType;
    }
  }

  public static final class UnionType extends VmType {
    private final int defaultIndex;
    private final VmType[] elementTypes;

    public UnionType(int defaultIndex, VmType[] elementTypes) {
      this.defaultIndex = defaultIndex;
      this.elementTypes = elementTypes;
    }

    public UnionType(int defaultIndex, String[] stringLiterals) {
      this.defaultIndex = defaultIndex;
      elementTypes = new VmType[stringLiterals.length];
      for (var i = 0; i < elementTypes.length; i++) {
        elementTypes[i] = new StringLiteralType(stringLiterals[i]);
      }
    }

    public int getDefaultIndex() {
      return defaultIndex;
    }

    public VmType[] getElementTypes() {
      return elementTypes;
    }

    @Override
    public PType export() {
      return new PType.Union(exportTypes(elementTypes));
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      if (!(other instanceof UnionType t)) return false;
      return typesEquals(elementTypes, t.elementTypes);
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this) && acceptTypes(elementTypes, visitTypeArguments, consumer);
    }

    @Override
    public String toString() {
      return Arrays.stream(elementTypes).map(Object::toString).collect(Collectors.joining(" | "));
    }
  }

  public static final class TypeVariableType extends VmType {
    private final TypeParameter typeParameter;

    public TypeVariableType(TypeParameter typeParameter) {
      this.typeParameter = typeParameter;
    }

    public TypeParameter getTypeParameter() {
      return typeParameter;
    }

    @Override
    protected boolean doIsEquivalentTo(VmType other) {
      return other instanceof TypeVariableType;
    }

    @Override
    public PType export() {
      return new PType.TypeVariable(typeParameter);
    }

    @Override
    public boolean acceptType(boolean visitTypeArguments, TypeConsumer consumer) {
      return consumer.accept(this);
    }

    @Override
    public String toString() {
      return typeParameter.getName();
    }
  }

  @FunctionalInterface
  public interface TypeConsumer {
    /** Returns true if the visitor should continue visiting types. */
    boolean accept(VmType type);
  }

  private static boolean typesEquals(VmType[] a, VmType[] b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!a[i].equals(b[i])) return false;
    }
    return true;
  }

  private static boolean acceptTypes(
      VmType[] types, boolean visitTypeArguments, TypeConsumer consumer) {
    for (var t : types) {
      if (!t.acceptType(visitTypeArguments, consumer)) return false;
    }
    return true;
  }

  private static List<PType> exportTypes(VmType[] types) {
    return Arrays.stream(types).map(it -> it.export()).toList();
  }
}
