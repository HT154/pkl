/*
 * Copyright © 2025-2026 Apple Inc. and the Pkl project authors. All rights reserved.
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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.DirectCallNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.pkl.core.Composite;
import org.pkl.core.PType;
import org.pkl.core.Reference;
import org.pkl.core.util.paguro.RrbTree;
import org.pkl.core.util.paguro.RrbTree.ImRrbt;

public final class VmReference extends VmValue {

  private final VmTyped domain;
  private final Object data;
  private final ImRrbt<VmTyped> path;
  // candidate types can only be: VmType.ClassType, VmType.AliasType (only preservedAliasTypes),
  // VmType.StringLiteralType, VmType.UNKNOWN, VmType.FunctionType, VmType.TypeVariableTybe, or
  // VmType.UnionType
  // (containing only the previous; flattened)
  private final VmType referentType;

  private boolean forced = false;

  private static VmTyped newAccess(@Nullable String property, @Nullable Object key) {
    return new VmObjectBuilder()
        .addProperty(Identifier.PROPERTY, property == null ? VmNull.withoutDefault() : property)
        .addProperty(Identifier.KEY, key == null ? VmNull.withoutDefault() : key)
        .toTyped(RefModule.getAccessClass());
  }

  @TruffleBoundary
  public VmReference(VmTyped domain, VmClass clazz, Object data) {
    this(
        domain,
        data,
        RrbTree.empty(),
        normalizeTypes(new VmType.ClassType(clazz), clazz.getModuleClass()));
  }

  public VmReference(VmTyped domain, Object data, ImRrbt<VmTyped> path, VmType referentType) {
    this.domain = domain;
    this.data = data;
    this.referentType = referentType;
    this.path = path;
  }

  public VmTyped getDomain() {
    return domain;
  }

  public Object getData() {
    return data;
  }

  public List<VmTyped> getPath() {
    return path;
  }

  public VmType getReferentType() {
    return referentType;
  }

  // simplifies a type by:
  // * erasing constraints
  // * transforming T? into T|Null
  // * dereferencing aliases (except for well-known stdlib alias types)
  // * flattening unions
  // * when moduleClass is supplied, replace PType.MODULE with appropriate VmType.ClassType
  // * drop PType.Function and PType.TypeVariable
  private static VmType normalizeTypes(VmType type, VmClass moduleClass) {
    var types = new HashSet<VmType>();
    normalizeTypes(type, moduleClass, types);
    return minimizeTypes(types);
  }

  private static VmType minimizeTypes(Set<VmType> types) {
    if (types.size() == 1) return types.iterator().next();
    // optimization: unknown allows all references, erase all candidates to only unknown
    if (types.contains(VmType.UNKNOWN)) return VmType.UNKNOWN;
    // optimization: All allows all references, erase all candidates to only All
    if (containsClass(types, BaseModule.getAnyClass()))
      return new VmType.ClassType(BaseModule.getAnyClass());
    var typeArray = types.toArray(new VmType[0]);
    Arrays.sort(typeArray, Comparator.comparing(Object::toString));
    return new VmType.UnionType(-1, typeArray);
  }

  private static void normalizeTypes(VmType type, VmClass moduleClass, Set<VmType> result) {
    if (type == VmType.UNKNOWN
        || type == VmType.NOTHING
        || type instanceof VmType.StringLiteralType) {
      result.add(type);
    } else if (type instanceof VmType.ClassType ct) {
      if (!ct.isParametric()) {
        result.add(ct);
      } else {
        result.add(
            ct.withTypeArguments(
                Arrays.stream(ct.getTypeArguments())
                    .map(arg -> normalizeTypes(arg, moduleClass))
                    .toArray(VmType[]::new)));
      }
    }
    // normalize `T?` to `T | Null`
    else if (type instanceof VmType.NullableType nullable) {
      normalizeTypes(nullable.getElementType(), moduleClass, result);
      result.add(new VmType.ClassType(BaseModule.getNullClass()));
      // erase `T(someConstraint)` to `T`
    } else if (type instanceof VmType.ConstrainedType constrained) {
      normalizeTypes(constrained.getBaseType(), moduleClass, result);
    } else if (type instanceof VmType.AliasType alias) {
      if (isPreservedTypeAlias(alias.getVmTypeAlias())) {
        result.add(alias);
      } else {
        normalizeTypes(alias.getAliasedType(), alias.getVmTypeAlias().getModuleClass(), result);
      }
    } else if (type instanceof VmType.UnionType union) {
      for (var t : union.getElementTypes()) {
        normalizeTypes(t, moduleClass, result);
      }
    } else if (type instanceof VmType.ModuleType) {
      result.add(new VmType.ClassType(moduleClass));
    } else {
      // remaining types: PType.Function, PType.TypeVariable. no normalizing needed; TypeVariable
      // gets replaced upon instantiation, and Function can bubble up to users as a reference error
      // if accessed.
      result.add(type);
    }
  }

  private static VmType[] iterateTypes(VmType t) {
    if (t instanceof VmType.UnionType union) return union.getElementTypes();
    return new VmType[] {t};
  }

  public VmReference withPropertyAccess(Identifier property) {
    var propString = property.toString();
    return withAccess(
        (t, candidates) -> getCandidatePropertyType(t, propString, candidates),
        () -> newAccess(property.toString(), null));
  }

  public VmReference withSubscriptAccess(Object key) {
    return withAccess(
        (t, candidates) -> getCandidateSubscriptType(t, key, candidates),
        () -> newAccess(null, key));
  }

  @TruffleBoundary
  private VmReference withAccess(
      BiConsumer<VmType, Set<VmType>> checkCandidate, Supplier<VmTyped> makeAccess) {
    Set<VmType> candidates = new HashSet<>();
    for (var t : iterateTypes(referentType)) {
      checkCandidate.accept(t, candidates);
    }
    return new VmReference(domain, data, path.append(makeAccess.get()), minimizeTypes(candidates));
  }

  @SuppressWarnings("DuplicatedCode")
  private static void getCandidatePropertyType(VmType type, String property, Set<VmType> result) {
    if (type == VmType.UNKNOWN) {
      result.add(type);
      return;
    }
    // restriction: only class types can have their properties referenced
    if (!(type instanceof VmType.ClassType ct)) {
      throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.CANNOT_FIND_MEMBER);
    }
    if (ct.getVmClass().isDynamicClass()) {
      if (property.equals("default")) {
        // restriction: cannot reference Dynamic.default
        throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.DEFAULT_MEMBER);
      }
      result.add(VmType.UNKNOWN);
      return;
    }
    // restriction: cannot reference Listing/Mapping.default
    if (ct.getVmClass().isListingClass() || ct.getVmClass().isMappingClass()) {
      var errorType =
          property.equals("default")
              ? VmReferenceAccessErrorType.DEFAULT_MEMBER
              : VmReferenceAccessErrorType.CANNOT_FIND_MEMBER;
      throw new VmReferenceAccessError(type, errorType);
    }
    var baseModule = BaseModule.getModuleClass();
    // restriction: cannot reference Module.output.
    //   generalized: properties originally defined in external classes; the only extant example.
    // This is implemented specifically because this is the only case where an external class
    //   containing a property can be subclassed.
    // And this can't check prop.getOwner().isExternal() because fully overriding the property with
    //   a new type annotation means the owner isn't Module.
    if (ct.getVmClass().isSubclassOf(baseModule) && property.equals("output")) {
      throw new VmReferenceAccessError(
          new VmType.ClassType(baseModule), VmReferenceAccessErrorType.EXTERNAL_CLASS);
    }

    // dot access on `Reference<D, Null>` gives `Reference<D, Null>`
    if (ct.getVmClass().isNullClass()) {
      result.add(ct);
      return;
    }

    var prop = ct.getVmClass().getAllProperties().get(Identifier.get(property));
    //noinspection ConstantValue
    if (prop == null) {
      throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.CANNOT_FIND_MEMBER);
    }

    // restriction: cannot reference external properties
    if (prop.isExternal()) {
      throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.EXTERNAL_MEMBER);
    }

    normalizeTypes(prop.getType(), ct.getVmClass().getModuleClass(), result);
  }

  @SuppressWarnings("DuplicatedCode")
  private static void getCandidateSubscriptType(VmType type, Object key, Set<VmType> result) {
    if (type == VmType.UNKNOWN) {
      result.add(type);
      return;
    }
    if (!(type instanceof VmType.ClassType ct)) {
      throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.CANNOT_FIND_MEMBER);
    }
    if (ct.getVmClass().isDynamicClass()) {
      result.add(VmType.UNKNOWN);
      return;
    }
    if (ct.getVmClass().isListingClass() || ct.getVmClass().isListClass()) {
      if (!(key instanceof Long)) {
        throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.CANNOT_FIND_MEMBER);
      }
      normalizeTypes(ct.getTypeArguments()[0], ct.getVmClass().getModuleClass(), result);
      return;
    }
    if (ct.getVmClass().isMappingClass() || ct.getVmClass().isMapClass()) {
      var typeArgs = ct.getTypeArguments();
      var keyTypes = normalizeTypes(typeArgs[0], ct.getVmClass().getModuleClass());
      for (var kt : iterateTypes(keyTypes)) {
        if (kt == VmType.UNKNOWN
            || (kt instanceof VmType.ClassType klazz && klazz.getVmClass() == VmUtils.getClass(key))
            || (kt instanceof VmType.StringLiteralType stringLiteral
                && stringLiteral.getLiteral().equals(key))) {
          normalizeTypes(typeArgs[1], ct.getVmClass().getModuleClass(), result);
          return;
        }
      }
    }

    // subscript access on `Reference<D, Null>` gives `Reference<D, Null>`
    if (ct.getVmClass() == BaseModule.getNullClass()) {
      result.add(ct);
      return;
    }

    throw new VmReferenceAccessError(type, VmReferenceAccessErrorType.CANNOT_FIND_MEMBER);
  }

  /**
   * Tells if this reference's referent type is a subtype of {@code type}. Does not check domain.
   */
  public boolean referentTypeIsSubtypeOf(VmType type, VmClass moduleClass) {
    // fast path: if referent is unknown it can match any type check
    if (referentType == VmType.UNKNOWN) {
      return true;
    }

    var checkType = normalizeTypes(type, moduleClass);
    // fast path: short circuit if any referent is accepted
    if (checkType == VmType.UNKNOWN || isClass(checkType, BaseModule.getAnyClass())) {
      return true;
    }
    // fast path: short circuit if nothing is accepted
    if (checkType == VmType.NOTHING) {
      return false;
    }

    return referentType.isSubtypeOf(checkType);
  }

  private static boolean containsClass(Set<VmType> types, VmClass clazz) {
    for (var t : types) {
      if (isClass(t, clazz)) return true;
    }
    return false;
  }

  private static boolean isClass(VmType t, VmClass clazz) {
    return t instanceof VmType.ClassType ct && ct.getVmClass() == clazz;
  }

  private static boolean isIntTypeAlias(VmTypeAlias t) {
    return t == BaseModule.getInt8TypeAlias()
        || t == BaseModule.getInt16TypeAlias()
        || t == BaseModule.getInt32TypeAlias()
        || t == BaseModule.getUInt8TypeAlias()
        || t == BaseModule.getUInt16TypeAlias()
        || t == BaseModule.getUInt32TypeAlias()
        || t == BaseModule.getUIntTypeAlias();
  }

  private static boolean isPreservedTypeAlias(VmTypeAlias t) {
    return isIntTypeAlias(t);
  }

  @Override
  public VmClass getVmClass() {
    return RefModule.getReferenceClass();
  }

  @Override
  public void force(boolean allowUndefinedValues) {
    if (forced) return;

    forced = true;

    domain.force(allowUndefinedValues);
    VmValue.force(data, allowUndefinedValues);
    for (var elem : path) {
      elem.force(allowUndefinedValues);
    }
  }

  @Override
  public Reference export() {
    var pathList = new ArrayList<Composite>(path.size());
    for (var elem : path) {
      pathList.add(elem.export());
    }

    return new Reference(
        domain.export(), VmValue.export(data), pathList, getReferentType().export());
  }

  public PType exportType() {
    return new PType.Class(
        RefModule.getReferenceClass().export(),
        new PType.Class(domain.getVmClass().export()),
        getReferentType().export());
  }

  @Override
  public void accept(VmValueVisitor visitor) {
    visitor.visitReference(this);
  }

  @Override
  public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
    return converter.convertReference(this, path);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (o == this) return true;
    if (!(o instanceof VmReference that)) {
      return false;
    }

    return domain.equals(that.domain)
        && data.equals(that.data)
        && path.equals(that.path)
        && referentType.equals(that.referentType);
  }

  @Override
  public int hashCode() {
    int result = domain.hashCode();
    result = 31 * result + data.hashCode();
    result = 31 * result + path.hashCode();
    result = 31 * result + referentType.hashCode();
    return result;
  }

  // in-language calls _should_ all go through `ToStringNode`.
  // however, some calls escape through to here currently (e.g. `Listing.join`).
  @Override
  public String toString() {
    var toStringMethod = getVmClass().getDeclaredMethod(Identifier.TO_STRING);
    assert toStringMethod != null;
    var callNode = DirectCallNode.create(toStringMethod.getCallTarget());
    return (String) callNode.call(this, getVmClass().getPrototype());
  }

  public enum VmReferenceAccessErrorType {
    CANNOT_FIND_MEMBER,
    EXTERNAL_MEMBER,
    DEFAULT_MEMBER,
    EXTERNAL_CLASS
  }

  public static final class VmReferenceAccessError extends RuntimeException {
    private final VmType type;
    private final VmReferenceAccessErrorType errorType;

    public VmReferenceAccessError(VmType type, VmReferenceAccessErrorType errorType) {
      this.type = type;
      this.errorType = errorType;
    }

    public VmType getType() {
      return type;
    }

    public VmReferenceAccessErrorType getErrorType() {
      return errorType;
    }
  }
}
