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
package org.pkl.core.ast.type;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import org.jspecify.annotations.Nullable;
import org.pkl.core.ast.ConstantValueNode;
import org.pkl.core.ast.PklNode;
import org.pkl.core.ast.SimpleRootNode;
import org.pkl.core.ast.VmModifier;
import org.pkl.core.ast.expression.primary.GetModuleNode;
import org.pkl.core.ast.member.DefaultPropertyBodyNode;
import org.pkl.core.ast.member.ObjectMember;
import org.pkl.core.ast.member.UntypedObjectMemberNode;
import org.pkl.core.runtime.Identifier;
import org.pkl.core.runtime.VmClass;
import org.pkl.core.runtime.VmDynamic;
import org.pkl.core.runtime.VmFunction;
import org.pkl.core.runtime.VmLanguage;
import org.pkl.core.runtime.VmList;
import org.pkl.core.runtime.VmListing;
import org.pkl.core.runtime.VmMap;
import org.pkl.core.runtime.VmMapping;
import org.pkl.core.runtime.VmNull;
import org.pkl.core.runtime.VmSet;
import org.pkl.core.runtime.VmType;
import org.pkl.core.runtime.VmTyped;
import org.pkl.core.runtime.VmUtils;
import org.pkl.core.util.EconomicMaps;

public abstract class GetDefaultValueForTypeNode extends PklNode {

  protected GetDefaultValueForTypeNode(SourceSection sourceSection) {
    super(sourceSection);
  }

  private GetDefaultValueForTypeNode create(SourceSection sourceSection) {
    return GetDefaultValueForTypeNodeGen.create(sourceSection);
  }

  public abstract @Nullable Object executeGeneric(
      VirtualFrame frame, VmType type, SourceSection headerSection, String qualifiedName);

  @Specialization(guards = "type.isFinalType()")
  protected final Object executeFinalSelf(
      VirtualFrame frame, VmType.SelfType type, SourceSection headerSection, String qualifiedName) {
    return type.getClassRepr().getPrototype();
  }

  @Specialization(guards = "!type.isFinalType()")
  protected final Object executeNonFinalModule(
      VirtualFrame frame,
      VmType.ModuleType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetModuleNode getModuleNode) {
    return ((VmTyped) getModuleNode.executeGeneric(frame)).getVmClass().getPrototype();
  }

  //  @Specialization(guards = "!type.isFinalType()")
  //  protected final Object executeNonFinalThis(
  //      VirtualFrame frame,
  //      VmType.ThisType type,
  //      SourceSection headerSection,
  //      String qualifiedName,
  //      @Cached("create()") GetReceiverNode getReceiverNode) {
  //    return ((VmTyped) getReceiverNode.executeGeneric(frame)).getVmClass().getPrototype();
  //  }

  @Specialization
  protected final @Nullable Object executeClass(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return createDefaultValue(type.getClassRepr());
  }

  @Specialization(guards = "type.getClassRepr().isDynamicClass()")
  protected final Object executeClassDynamic(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return VmDynamic.empty();
  }

  @Specialization(guards = "type.getClassRepr().isCollectionClass()")
  protected final Object executeClassCollection(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return VmList.EMPTY;
  }

  @Specialization(guards = "type.getClassRepr().isListClass()")
  protected final Object executeClassList(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return VmList.EMPTY;
  }

  @Specialization(guards = "type.getClassRepr().isSetClass()")
  protected final Object executeClassSet(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return VmSet.EMPTY;
  }

  @Specialization(guards = "type.getClassRepr().isMapClass()")
  protected final Object executeClassMap(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    return VmMap.EMPTY;
  }

  @Specialization(guards = "type.getClassRepr().isVarArgsClass()")
  protected final Object executeClassVarArgs(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName) {
    throw exceptionBuilder()
        .evalError("internalStdLibClass", "VarArgs")
        .withSourceSection(headerSection)
        .build();
  }

  @Specialization(guards = "type.getClassRepr().isListingClass()")
  protected final Object executeClassListing(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    if (!type.isParametric() || type.getTypeArguments()[0] instanceof VmType.UnknownType)
      return VmListing.empty();

    var defaultMemberValue =
        getElementDefaultNode.executeGeneric(
            frame, type.getTypeArguments()[0], headerSection, qualifiedName);
    var defaultMember =
        createDefaultMember(VmLanguage.get(this), headerSection, qualifiedName, defaultMemberValue);
    return new VmListing(
        VmUtils.createEmptyMaterializedFrame(),
        type.getClassRepr().getPrototype(),
        EconomicMaps.of(Identifier.DEFAULT, defaultMember),
        0);
  }

  @Specialization(guards = "type.getClassRepr().isMappingClass()")
  protected final Object executeClassMapping(
      VirtualFrame frame,
      VmType.ClassType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    if (!type.isParametric() || type.getTypeArguments()[1] instanceof VmType.UnknownType)
      return VmMapping.empty();

    var defaultMemberValue =
        getElementDefaultNode.executeGeneric(
            frame, type.getTypeArguments()[0], headerSection, qualifiedName);
    var defaultMember =
        createDefaultMember(VmLanguage.get(this), headerSection, qualifiedName, defaultMemberValue);
    return new VmMapping(
        VmUtils.createEmptyMaterializedFrame(),
        type.getClassRepr().getPrototype(),
        EconomicMaps.of(Identifier.DEFAULT, defaultMember));
  }

  @Specialization
  protected final @Nullable Object executeNullable(
      VirtualFrame frame,
      VmType.NullableType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    return getElementDefaultNode.executeGeneric(
        frame, type.getElementType(), headerSection, qualifiedName);
  }

  @Specialization(guards = "type.getDefaultIndex() == -1")
  protected final @Nullable Object executeUnionNoDefault(
      VirtualFrame frame,
      VmType.UnionType type,
      SourceSection headerSection,
      String qualifiedName) {
    return null;
  }

  @Specialization(guards = "type.getDefaultIndex() != -1")
  protected final @Nullable Object executeUnion(
      VirtualFrame frame,
      VmType.UnionType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    return getElementDefaultNode.executeGeneric(
        frame, type.getElementTypes()[type.getDefaultIndex()], headerSection, qualifiedName);
  }

  @Specialization(guards = "type.getTypeAliasRepr().isMixinTypeAlias()")
  protected final Object executeTypeAliasMixin(
      VirtualFrame frame,
      VmType.AliasType type,
      SourceSection headerSection,
      String qualifiedName) {
    return newMixin(VmLanguage.get(this), qualifiedName);
  }

  @Specialization(guards = "!type.getTypeAliasRepr().isMixinTypeAlias()")
  protected final @Nullable Object executeTypeAlias(
      VirtualFrame frame,
      VmType.AliasType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    return getElementDefaultNode.executeGeneric(
        frame, type.getAliasedType(), headerSection, qualifiedName);
  }

  protected final @Nullable Object executeConstrained(
      VirtualFrame frame,
      VmType.ConstrainedType type,
      SourceSection headerSection,
      String qualifiedName,
      @Cached("create(sourceSection)") GetDefaultValueForTypeNode getElementDefaultNode) {
    return getElementDefaultNode.executeGeneric(
        frame, type.getBaseType(), headerSection, qualifiedName);
  }

  @Fallback
  protected final @Nullable Object fallback(
      VirtualFrame frame, VmType type, SourceSection headerSection, String qualifiedName) {
    return null;
  }

  private static @Nullable Object createDefaultValue(VmClass clazz) {
    if (clazz.isInstantiable()) {
      if (clazz.isListingClass()) return VmListing.empty();
      if (clazz.isMappingClass()) return VmMapping.empty();
      if (clazz.isDynamicClass()) return VmDynamic.empty();
      return clazz.getPrototype();
    }

    if (clazz.isListClass()) return VmList.EMPTY;
    if (clazz.isSetClass()) return VmSet.EMPTY;
    if (clazz.isMapClass()) return VmMap.EMPTY;
    if (clazz.isCollectionClass()) return VmList.EMPTY;
    if (clazz.isNullClass()) return VmNull.withoutDefault();

    return null;
  }

  @TruffleBoundary
  private VmFunction newMixin(VmLanguage language, String qualifiedName) {
    //noinspection ConstantConditions
    return new VmFunction(
        VmUtils.createEmptyMaterializedFrame(),
        // Assumption: don't need to set the correct `thisValue`
        // because it is guaranteed to be never accessed.
        null,
        1,
        new IdentityMixinNode(
            language,
            new FrameDescriptor(),
            getSourceSection(),
            qualifiedName,
            typeArgumentNodes.length == 1
                ?
                // shouldn't need to deepCopy() this node because it isn't used as @Child
                // anywhere else
                typeArgumentNodes[0]
                : null),
        null);
  }

  // either (if defaultMemberValue != null):
  // x: Listing<Foo> // = new Listing {
  //   default = name -> new Foo {}
  // }
  // or (if defaultMemberValue == null):
  // x: Listing<Int> // = new Listing {
  //   default = Undefined()
  // }
  @TruffleBoundary
  private ObjectMember createDefaultMember(
      VmLanguage language,
      SourceSection headerSection,
      String qualifiedName,
      @Nullable Object defaultMemberValue) {
    var defaultMember =
        new ObjectMember(
            headerSection,
            headerSection,
            VmModifier.HIDDEN,
            Identifier.DEFAULT,
            VmUtils.concat(qualifiedName, ".default"));
    if (defaultMemberValue == null) {
      defaultMember.initMemberNode(
          new UntypedObjectMemberNode(
              language,
              new FrameDescriptor(),
              defaultMember,
              new DefaultPropertyBodyNode(headerSection, Identifier.DEFAULT, null)));
    } else {
      //noinspection ConstantConditions
      defaultMember.initConstantValue(
          new VmFunction(
              // Assumption: don't need to set the correct `thisValue`
              // because it is guaranteed to be never accessed.
              VmUtils.createEmptyMaterializedFrame(),
              null,
              1,
              new SimpleRootNode(
                  language,
                  new FrameDescriptor(),
                  headerSection,
                  VmUtils.concat(defaultMember.getQualifiedName(), ".<function>"),
                  new ConstantValueNode(defaultMemberValue)),
              null));
    }
    return defaultMember;
  }
}
