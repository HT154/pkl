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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.jspecify.annotations.Nullable;
import org.pkl.core.PType;
import org.pkl.core.ast.type.TypeNode;

public final class VmType extends VmValue {

  private final TypeNode typeNode;
  private final VmClass moduleClass;

  public VmType(TypeNode typeNode, VmClass moduleClass) {
    this.typeNode = typeNode;
    this.moduleClass = moduleClass;
  }

  public TypeNode getTypeNode() {
    return typeNode;
  }

  @Override
  public VmClass getVmClass() {
    return BaseModule.getTypeClass();
  }

  public VmClass getModuleClass() {
    return moduleClass;
  }

  @Override
  public void force(boolean allowUndefinedValues) {}

  @Override
  public PType export() {
    return TypeNode.export(typeNode);
  }

  @Override
  public void accept(VmValueVisitor visitor) {
    visitor.visitType(this);
  }

  @Override
  public <T> T accept(VmValueConverter<T> converter, Iterable<Object> path) {
    return converter.convertType(this, path);
  }

  @Override
  @TruffleBoundary
  public boolean equals(@Nullable Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof VmType other)) return false;

    return typeNode.equals(other.getTypeNode()) && moduleClass.equals(other.getModuleClass());
  }

  @Override
  @TruffleBoundary
  public int hashCode() {
    return typeNode.hashCode();
  }
}
