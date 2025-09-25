/*
 * Copyright © 2025 Apple Inc. and the Pkl project authors. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.organicdesign.fp.collections.RrbTree;
import org.organicdesign.fp.collections.RrbTree.ImRrbt;
import org.pkl.core.Composite;
import org.pkl.core.Modifier;
import org.pkl.core.PClass;
import org.pkl.core.PClassInfo;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.PType;
import org.pkl.core.TypeAlias;
import org.pkl.core.TypeParameter.Variance;
import org.pkl.core.util.Nullable;

public final class VmReference extends VmValue {

  // candidate types can only be: PType.Class, PType.Alias (only preservedAliasTypes),
  // PType.StringLiteral, or PType.UNKNOWN
  private final Set<PType> candidateTypes;
  // TODO figure out what to do with constraints
  //    maybe: start w/ errors and open up to analyzable constraints later
  private final VmValue rootValue;
  private final ImRrbt<Access> path;
  private boolean forced = false;

  private static final PType nullType = new PType.Class(BaseModule.getNullClass().export());

  private static final Set<TypeAlias> intAliasTypes =
      BaseModule.getIntTypeAliases().stream().map((it) -> it.export()).collect(Collectors.toSet());
  private static final Set<TypeAlias> preservedAliasTypes = intAliasTypes;

  public VmReference(VmValue rootValue) {
    this(Set.of(new PType.Class(rootValue.getVmClass().export())), rootValue, RrbTree.empty());
  }

  public VmReference(Set<PType> candidateTypes, VmValue rootValue, ImRrbt<Access> path) {
    this.candidateTypes = candidateTypes;
    this.rootValue = rootValue;
    this.path = path;
  }

  public Set<PType> getCandidateTypes() {
    return candidateTypes;
  }

  public VmValue getRootValue() {
    return rootValue;
  }

  public List<Access> getPath() {
    return path;
  }

  // simplifies a type by:
  // * erasing constraints
  // * transforming T? into T|Null
  // * dereferencing aliases (except for well-known stdlib alias types)
  // * flattening unions
  // * when moduleClass is supplied, replace PType.MODULE with appropriate PType.Class
  // * drop PType.NOTHING, PType.Function, and PType.TypeVariable
  private static Stream<PType> simplifyType(PType type, @Nullable PClass moduleClass) {
    if (type == PType.UNKNOWN || type instanceof PType.StringLiteral) {
      return Stream.of(type);
    } else if (type instanceof PType.Class klass) {
      return Stream.of(
          klass.getTypeArguments().isEmpty()
              ? klass
              : new PType.Class(
                  klass.getPClass(),
                  klass.getTypeArguments().stream()
                      .map(
                          (it) -> {
                            var tt = simplifyType(it, moduleClass).toList();
                            return (tt.size() == 1 ? tt.getFirst() : new PType.Union(tt));
                          })
                      .toList()));
    } else if (type instanceof PType.Nullable nullable) {
      return Stream.concat(simplifyType(nullable.getBaseType(), moduleClass), Stream.of(nullType));
    } else if (type instanceof PType.Constrained constrained) {
      return simplifyType(constrained.getBaseType(), moduleClass);
    } else if (type instanceof PType.Alias alias) {
      return preservedAliasTypes.contains(alias.getTypeAlias())
          ? Stream.of(alias)
          : simplifyType(alias.getAliasedType(), alias.getTypeAlias().getModuleClass());
    } else if (type instanceof PType.Union union) {
      return union.getElementTypes().stream().flatMap((it) -> simplifyType(it, moduleClass));
    } else if (type == PType.MODULE && moduleClass != null) {
      return Stream.of(new PType.Class(moduleClass));
    }
    return Stream.empty();
  }

  public @Nullable VmReference withPropertyAccess(Identifier property) {
    var candidates =
        candidateTypes.stream()
            .flatMap((it) -> getCandidatePropertyType(it, property.toString()))
            .collect(Collectors.toUnmodifiableSet());
    if (candidates.isEmpty()) {
      return null;
    } else if (candidates.contains(PType.UNKNOWN)) {
      candidates = Set.of(PType.UNKNOWN);
    }
    return new VmReference(
        candidates, rootValue, path.append(Access.property(property.toString())));
  }

  public @Nullable VmReference withSubscriptAccess(Object key) {
    var candidates =
        candidateTypes.stream()
            .flatMap((it) -> getCandidateSubscriptType(it, key))
            .collect(Collectors.toUnmodifiableSet());
    if (candidates.isEmpty()) {
      return null;
    } else if (candidates.contains(PType.UNKNOWN)) {
      candidates = Set.of(PType.UNKNOWN);
    }
    return new VmReference(candidates, rootValue, path.append(Access.subscript(key)));
  }

  @SuppressWarnings("DuplicatedCode")
  private static Stream<PType> getCandidatePropertyType(PType type, String property) {
    if (type == PType.UNKNOWN) {
      return Stream.of(type);
    }
    if (!(type instanceof PType.Class klass)) {
      return Stream.empty();
    }
    if (klass.getPClass().getInfo() == PClassInfo.Dynamic) {
      return Stream.of(PType.UNKNOWN);
    }
    if (klass.getPClass().getInfo() == PClassInfo.Listing
        || klass.getPClass().getInfo() == PClassInfo.Mapping) {
      return Stream.empty();
    }
    // Typed
    var prop = klass.getPClass().getAllProperties().get(property);
    if (prop == null || prop.getModifiers().contains(Modifier.EXTERNAL)) {
      return Stream.empty();
    }
    return simplifyType(prop.getType(), klass.getPClass().getModuleClass());
  }

  @SuppressWarnings("DuplicatedCode")
  private static Stream<PType> getCandidateSubscriptType(PType type, Object key) {
    if (type == PType.UNKNOWN) {
      return Stream.of(type);
    }
    if (!(type instanceof PType.Class klass)) {
      return Stream.empty();
    }
    if (klass.getPClass().getInfo() == PClassInfo.Dynamic) {
      return Stream.of(PType.UNKNOWN);
    }
    if (klass.getPClass().getInfo() == PClassInfo.Listing) {
      if (key instanceof Long) {
        var typeArgs = klass.getTypeArguments();
        return simplifyType(typeArgs.get(0), klass.getPClass().getModuleClass());
      } else {
        return Stream.empty();
      }
    }
    if (klass.getPClass().getInfo() == PClassInfo.Mapping) {
      var typeArgs = klass.getTypeArguments();
      return simplifyType(typeArgs.get(0), klass.getPClass().getModuleClass())
              .anyMatch(
                  (it) ->
                      it == PType.UNKNOWN
                          || (it instanceof PType.Class klazz
                              && klazz.getPClass().getInfo() == PClassInfo.forValue(key))
                          || (it instanceof PType.StringLiteral stringLiteral
                              && stringLiteral.getLiteral().equals(key)))
          ? simplifyType(typeArgs.get(1), klass.getPClass().getModuleClass())
          : Stream.empty();
    }

    // Typed
    return Stream.empty();
  }

  public boolean checkType(PType type, @Nullable PClass moduleClass) {
    // fast path: if this could be unknown, any type is accepted
    if (candidateTypes.contains(PType.UNKNOWN)) {
      return true;
    }

    //  should this be any or all?
    return simplifyType(type, moduleClass)
        .anyMatch((t) -> candidateTypes.stream().anyMatch((c) -> isSubtype(c, t)));
  }

  private static boolean isSubtype(PType a, PType b) {
    if (a instanceof PType.StringLiteral aStr && b instanceof PType.StringLiteral bStr) {
      return aStr.getLiteral().equals(bStr.getLiteral());
    } else if (a instanceof PType.Alias aAlias) {
      var aa = aAlias.getTypeAlias();
      if (intAliasTypes.contains(aa)) {
        // special casing for stdlib Int typealiases
        if (b instanceof PType.Class bClass) {
          // a is an int alias, b is a Number
          return isSubtype(bClass.getPClass(), BaseModule.getNumberClass().export());
        } else if (b instanceof PType.Alias bAlias) {
          var bb = bAlias.getTypeAlias();
          if (aa == bb) {
            return true;
          }
          if (aa == BaseModule.getInt8TypeAlias().export()) {
            return bb == BaseModule.getInt16TypeAlias().export()
                || bb == BaseModule.getInt32TypeAlias().export();
          } else if (aa == BaseModule.getInt16TypeAlias().export()) {
            return bb == BaseModule.getInt32TypeAlias().export();
          } else if (aa == BaseModule.getUInt8TypeAlias().export()) {
            return bb == BaseModule.getInt16TypeAlias().export()
                || bb == BaseModule.getInt32TypeAlias().export()
                || bb == BaseModule.getUInt16TypeAlias().export()
                || bb == BaseModule.getUInt32TypeAlias().export()
                || bb == BaseModule.getUIntTypeAlias().export();
          } else if (aa == BaseModule.getUInt16TypeAlias().export()) {
            return bb == BaseModule.getInt32TypeAlias().export()
                || bb == BaseModule.getUInt32TypeAlias().export()
                || bb == BaseModule.getUIntTypeAlias().export();
          } else if (aa == BaseModule.getUInt32TypeAlias().export()) {
            return bb == BaseModule.getUIntTypeAlias().export();
          }
        }
      }
    } else if (a instanceof PType.Class aClass && b instanceof PType.Class bClass) {
      if (!isSubtype(aClass.getPClass(), bClass.getPClass())) {
        return false;
      }
      var aArgs = aClass.getTypeArguments();
      var bArgs = bClass.getTypeArguments();
      var bParams = bClass.getPClass().getTypeParameters();
      if (aArgs.size() != bArgs.size()) {
        return true;
      }
      for (var i = 0; i < aArgs.size(); i++) {
        if (!isSubtype(aArgs.get(i), bArgs.get(i), bParams.get(i).getVariance())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean isSubtype(PType a, PType b, Variance variance) {
    return switch (variance) {
      case INVARIANT -> a == b;
      case COVARIANT -> isSubtype(a, b);
      case CONTRAVARIANT -> isSubtype(b, a);
    };
  }

  private static boolean isSubtype(PClass a, PClass b) {
    return a == b || a.getSuperclass() != null && isSubtype(a.getSuperclass(), b);
  }

  @Override
  public VmClass getVmClass() {
    return BaseModule.getReferenceClass();
  }

  @Override
  public void force(boolean allowUndefinedValues) {
    if (forced) return;

    forced = true;

    rootValue.force(allowUndefinedValues);
    for (var elem : path) {
      VmValue.force(elem, allowUndefinedValues);
    }
  }

  @Override
  public Composite export() {
    var pathList = new ArrayList<>(path.size());
    for (Access elem : path) {
      pathList.add(elem.export());
    }

    return new PObject(
        getVmClass().getPClassInfo(),
        Map.of(
            "candidateTypes", candidateTypes,
            "rootValue", rootValue.export(),
            "path", pathList));
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
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof VmReference other)) return false;

    return Objects.equals(candidateTypes, other.getCandidateTypes())
        && rootValue.equals(other.getRootValue())
        && path.equals(other.getPath());
  }

  public static class Access extends VmValue {
    private final @Nullable String property;
    private final @Nullable Object key;

    public static Access property(String property) {
      return new Access(property, null);
    }

    public static Access subscript(Object key) {
      return new Access(null, key);
    }

    private Access(@Nullable String property, @Nullable Object key) {
      this.property = property;
      this.key = key;
    }

    public String getProperty() {
      assert property != null;
      return property;
    }

    public Object getKey() {
      assert key != null;
      return key;
    }

    public boolean isProperty() {
      return property != null;
    }

    public boolean isSubscript() {
      return key != null;
    }

    @Override
    public VmClass getVmClass() {
      return BaseModule.getReferenceAccessClass();
    }

    @Override
    public void force(boolean allowUndefinedValues) {
      if (key != null) {
        VmValue.force(key, allowUndefinedValues);
      }
    }

    @Override
    public Object export() {
      return new PObject(
          getVmClass().getPClassInfo(),
          Map.of(
              "property",
              property == null ? PNull.getInstance() : property,
              "key",
              key == null ? PNull.getInstance() : VmValue.export(key)));
    }

    @Override
    public void accept(VmValueVisitor visitor) {
      visitor.visitReferenceAccess(this);
    }

    @Override
    public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
      return converter.convertReferenceAccess(this, path);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof Access other)) return false;
      return Objects.equals(property, other.getProperty()) && Objects.equals(key, other.getKey());
    }
  }
}
