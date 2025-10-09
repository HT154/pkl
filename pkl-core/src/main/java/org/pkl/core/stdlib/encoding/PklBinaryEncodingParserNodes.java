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
package org.pkl.core.stdlib.encoding;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.pkl.core.PClassInfo;
import org.pkl.core.PklBugException;
import org.pkl.core.runtime.BaseModule;
import org.pkl.core.runtime.Identifier;
import org.pkl.core.runtime.VmBytes;
import org.pkl.core.runtime.VmClass;
import org.pkl.core.runtime.VmContext;
import org.pkl.core.runtime.VmLanguage;
import org.pkl.core.runtime.VmList;
import org.pkl.core.runtime.VmListingOrMapping;
import org.pkl.core.runtime.VmMap;
import org.pkl.core.runtime.VmPklBinaryDecoder;
import org.pkl.core.runtime.VmTypeAlias;
import org.pkl.core.runtime.VmTyped;
import org.pkl.core.runtime.VmUtils;
import org.pkl.core.stdlib.ExternalMethod1Node;
import org.pkl.core.util.Nullable;

public final class PklBinaryEncodingParserNodes {

  public abstract static class parse extends ExternalMethod1Node {
    @Specialization
    protected Object eval(VmTyped self, VmBytes bytes) {
      return doParse(self, bytes.getBytes());
    }

    @Specialization
    protected Object eval(
        VmTyped self, VmTyped resource, @Cached("create()") IndirectCallNode callNode) {
      var bytes = (VmBytes) VmUtils.readMember(resource, Identifier.BYTES, callNode);
      return doParse(self, bytes.getBytes());
    }

    @TruffleBoundary
    private Object doParse(VmTyped self, byte[] bytes) {
      return VmPklBinaryDecoder.decode(bytes, new Importer(getImports(self)));
    }

    private static Map<URI, VmTyped> getImports(VmTyped self) {
      var imports = VmUtils.readMember(self, Identifier.IMPORTS);
      var importMap = new HashMap<URI, VmTyped>();
      Consumer<VmTyped> putMod =
          (mod) -> importMap.put(mod.getModuleInfo().getModuleKey().getUri(), mod);
      if (imports instanceof VmListingOrMapping importsListingOrMapping) {
        importsListingOrMapping.forceAndIterateMemberValues(
            ((key, member, mod) -> {
              putMod.accept((VmTyped) mod);
              return true;
            }));
      } else if (imports instanceof VmList importsList) {
        importsList.forEach((mod) -> putMod.accept((VmTyped) mod));
      } else if (imports instanceof VmMap importsMap) {
        importsMap.forEach((entry) -> putMod.accept((VmTyped) entry.getValue()));
      } else {
        throw PklBugException.unreachableCode();
      }
      return importMap;
    }

    private class Importer implements VmPklBinaryDecoder.Importer {
      private final Map<URI, VmTyped> imports;
      private final VmContext context;
      private final VmLanguage language;

      private Importer(Map<URI, VmTyped> imports) {
        this.imports = imports;
        this.context = VmContext.get(parse.this);
        this.language = VmLanguage.get(parse.this);
      }

      @Override
      public VmClass importClass(String name, URI moduleUri) {
        var module = importModule(moduleUri);
        var identifier = getIdentifier(name, moduleUri);
        if (identifier == null) { // if module class
          return module.getVmClass();
        }

        var clazz = module.getClass(identifier);
        if (clazz == null) {
          throw parse
              .this
              .exceptionBuilder()
              .cannotFindProperty(module, identifier, true, false)
              .build();
        }
        return clazz;
      }

      @Override
      public VmTypeAlias importTypeAlias(String name, URI moduleUri) {
        var module = importModule(moduleUri);
        var identifier = getIdentifier(name, moduleUri);
        assert identifier != null;
        var alias = module.getTypeAlias(identifier);
        if (alias == null) {
          throw parse
              .this
              .exceptionBuilder()
              .cannotFindProperty(module, identifier, true, false)
              .build();
        }
        return alias;
      }

      private @Nullable Identifier getIdentifier(String name, URI moduleUri) {
        // if name is in the format module#identifier, strip to just identifier
        // if no hash, this is a reference to a module class; return null

        // except when the module uri is pkl:base (see PClassInfo.getDisplayName)
        // the display name is used instead of the qualified name
        if (moduleUri.equals(PClassInfo.pklBaseUri)) {
          // except when the class name is ModuleClass, use the class of `pkl:base` itself
          if (name.equals(BaseModule.getModule().getVmClass().getDisplayName())) {
            return null;
          }
          return Identifier.get(name);
        }

        var hashIndex = name.lastIndexOf("#");
        if (hashIndex < 0) {
          return null;
        }
        return Identifier.get(name.substring(hashIndex + 1));
      }

      private VmTyped importModule(URI importUri) {
        if (importUri.getScheme().equals("pkl")) {
          var moduleKey = context.getModuleResolver().resolve(importUri, parse.this);
          return language.loadModule(moduleKey, parse.this);
        }

        var module = imports.get(importUri);
        if (module == null) {
          throw new Importer.Exception(importUri);
        }
        return module;
      }
    }
  }
}
