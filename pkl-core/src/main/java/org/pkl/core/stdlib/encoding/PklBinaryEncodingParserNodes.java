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
import org.pkl.core.PClassInfo;
import org.pkl.core.http.HttpClientInitException;
import org.pkl.core.packages.PackageLoadError;
import org.pkl.core.runtime.BaseModule;
import org.pkl.core.runtime.Identifier;
import org.pkl.core.runtime.VmBytes;
import org.pkl.core.runtime.VmClass;
import org.pkl.core.runtime.VmContext;
import org.pkl.core.runtime.VmLanguage;
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
      return doParse(bytes.getBytes());
    }

    @Specialization
    protected Object eval(
        VmTyped self, VmTyped resource, @Cached("create()") IndirectCallNode callNode) {
      var bytes = (VmBytes) VmUtils.readMember(resource, Identifier.BYTES, callNode);
      return doParse(bytes.getBytes());
    }

    @TruffleBoundary
    private Object doParse(byte[] bytes) {
      return VmPklBinaryDecoder.decode(bytes, new Importer());
    }

    private class Importer implements VmPklBinaryDecoder.Importer {

      private final VmContext context;
      private final VmLanguage language;

      private Importer() {
        this.language = VmLanguage.get(parse.this);
        this.context = VmContext.get(parse.this);
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
        try {
          // XXX: Intentionally not calling SecurityManager.checkImportModule.
          // It is not straightforward to get the calling module's URI.
          // We intend to re-evaluate the implementation of trust levels in the future.
          var moduleToImport = context.getModuleResolver().resolve(importUri, parse.this);
          return language.loadModule(moduleToImport, parse.this);
        } catch (PackageLoadError | HttpClientInitException e) {
          throw parse.this.exceptionBuilder().withCause(e).build();
        }
      }
    }
  }
}
