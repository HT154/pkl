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
import org.pkl.core.PClassInfo;
import org.pkl.core.PObject;
import org.pkl.core.PType;
import org.pkl.core.PklBugException;
import org.pkl.core.util.Nullable;

public final class VmReference extends VmValue {

  private final Set<PType> candidateTypes;
  private final VmValue rootValue;
  private final ImRrbt<Access> path;
  private boolean forced = false;

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

  private static Stream<PType> getCandidateTypes(List<PType> types) {
    return types.stream().flatMap(VmReference::getCandidateTypes);
  }

  private static Stream<PType> getCandidateTypes(PType type) {
    if (type.equals(PType.UNKNOWN)
        || type.equals(PType.NOTHING)
        || type.equals(PType.MODULE)
        || type instanceof PType.StringLiteral
        || type instanceof PType.Class) {
      return Stream.of(type);
    } else if (type instanceof PType.Nullable nullable) {
      return getCandidateTypes(nullable.getBaseType());
    } else if (type instanceof PType.Constrained constrained) {
      return getCandidateTypes(constrained.getBaseType());
    } else if (type instanceof PType.Alias alias) {
      return getCandidateTypes(alias.getAliasedType());
    } else if (type instanceof PType.Function || type instanceof PType.TypeVariable) {
      return Stream.empty();
    } else if (type instanceof PType.Union union) {
      return union.getElementTypes().stream().flatMap(VmReference::getCandidateTypes);
    }
    throw PklBugException.unreachableCode();
  }

  private static Stream<PType> getCandidatePropertyType(PType type, String property) {
    if (type.equals(PType.UNKNOWN)) {
      return Stream.of(type);
    } else if (type instanceof PType.Class klass) {
      if (klass.getPClass().getInfo() == PClassInfo.Dynamic) {
        return Stream.of(PType.UNKNOWN);
      } else if (klass.getPClass().getInfo() == PClassInfo.Listing
          || klass.getPClass().getInfo() == PClassInfo.Mapping) {
        return Stream.empty();
      } else { // Typed
        var prop = klass.getPClass().getAllProperties().get(property);
        return prop == null || prop.getModifiers().contains(Modifier.EXTERNAL)
            ? Stream.empty()
            : getCandidateTypes(prop.getType());
      }
    } else {
      return Stream.empty();
    }
  }

  private static Stream<PType> getCandidateSubscriptType(PType type, Object key) {
    if (type.equals(PType.UNKNOWN)) {
      return Stream.of(type);
    } else if (type instanceof PType.Class klass) {
      if (klass.getPClass().getInfo() == PClassInfo.Dynamic) {
        return Stream.of(PType.UNKNOWN);
      } else if (klass.getPClass().getInfo() == PClassInfo.Listing) {
        if (key instanceof Long) {
          var typeArgs = klass.getTypeArguments();
          return typeArgs.isEmpty() ? Stream.of(PType.UNKNOWN) : getCandidateTypes(typeArgs.get(0));
        } else {
          return Stream.empty();
        }
      } else if (klass.getPClass().getInfo() == PClassInfo.Mapping) {
        var typeArgs = klass.getTypeArguments();
        if (typeArgs.isEmpty()) {
          return Stream.of(PType.UNKNOWN);
        } else { // mapping always has 2 type args if it has any
          return getCandidateTypes(typeArgs.get(0))
                  .anyMatch(
                      (it) ->
                          it.equals(PType.UNKNOWN)
                              || (it instanceof PType.Class klazz
                                  && klazz.getPClass().getInfo() == PClassInfo.forValue(key))
                              || (it instanceof PType.StringLiteral stringLiteral
                                  && stringLiteral.getLiteral().equals(key)))
              ? getCandidateTypes(typeArgs.get(1))
              : Stream.empty();
        }
      } else { // Typed
        return Stream.empty();
      }
    } else {
      return Stream.empty();
    }
  }

  public @Nullable VmReference withPropertyAccess(Identifier property) {
    var candidates =
        candidateTypes.stream()
            .flatMap((it) -> getCandidatePropertyType(it, property.toString()))
            .collect(Collectors.toUnmodifiableSet());
    if (candidates.isEmpty()) {
      return null;
    }
    return new VmReference(
        candidates, rootValue, path.append(new PropertyAccess(property.toString())));
  }

  public @Nullable VmReference withSubscriptAccess(Object key) {
    var candidates =
        candidateTypes.stream()
            .flatMap((it) -> getCandidateSubscriptType(it, key))
            .collect(Collectors.toUnmodifiableSet());
    if (candidates.isEmpty()) {
      return null;
    }

    return new VmReference(candidates, rootValue, path.append(new SubscriptAccess(key)));
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
  
  public boolean checkType(PType type) {
    return getCandidateTypes(type).anyMatch(candidateTypes::contains);
  }

  public abstract static class Access extends VmValue {}

  public static final class PropertyAccess extends Access {
    private final String property;

    public PropertyAccess(String property) {
      this.property = property;
    }

    public String getProperty() {
      return property;
    }

    @Override
    public VmClass getVmClass() {
      return BaseModule.getReferencePropertyAccessClass();
    }

    @Override
    public void force(boolean allowUndefinedValues) {
      // do nothing
    }

    @Override
    public Object export() {
      return new PObject(getVmClass().getPClassInfo(), Map.of("property", property));
    }

    @Override
    public void accept(VmValueVisitor visitor) {
      visitor.visitReferencePropertyAccess(this);
    }

    @Override
    public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
      return converter.convertReferencePropertyAccess(this, path);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof PropertyAccess other)) return false;
      return property.equals(other.getProperty());
    }
  }

  public static final class SubscriptAccess extends Access {
    private final Object key;

    public SubscriptAccess(Object key) {
      this.key = key;
    }

    public Object getKey() {
      return key;
    }

    @Override
    public VmClass getVmClass() {
      return BaseModule.getReferenceSubscriptAccessClass();
    }

    @Override
    public void force(boolean allowUndefinedValues) {
      VmValue.force(key, allowUndefinedValues);
    }

    @Override
    public Object export() {
      return new PObject(getVmClass().getPClassInfo(), Map.of("key", VmValue.export(key)));
    }

    @Override
    public void accept(VmValueVisitor visitor) {
      visitor.visitReferenceSubscriptAccess(this);
    }

    @Override
    public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
      return converter.convertReferenceSubscriptAccess(this, path);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof SubscriptAccess other)) return false;
      return key.equals(other.getKey());
    }
  }
}
