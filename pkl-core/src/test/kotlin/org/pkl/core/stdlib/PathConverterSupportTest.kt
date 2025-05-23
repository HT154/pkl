/*
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
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
package org.pkl.core.stdlib

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pkl.core.runtime.Identifier

class PathConverterSupportTest {
  @Test
  fun `exact path matches`() {
    val pathSpec = listOf(Identifier.get("foo"), Identifier.get("bar"), Identifier.get("baz"))
    val pathPartSpec = listOf(Identifier.get("foo"), Identifier.get("bar"), Identifier.get("baz"))
    assertTrue(PathConverterSupport.pathMatches(pathSpec, pathPartSpec))
  }

  @Test
  fun `wildcard properties`() {
    val pathSpec =
      listOf(Identifier.get("foo"), PklConverter.WILDCARD_PROPERTY, Identifier.get("baz"))
    val pathPartSpec = listOf(Identifier.get("foo"), Identifier.get("bar"), Identifier.get("baz"))
    assertTrue(PathConverterSupport.pathMatches(pathSpec, pathPartSpec))
  }

  @Test
  fun `wildcard elements`() {
    val pathSpec =
      listOf(Identifier.get("foo"), PklConverter.WILDCARD_ELEMENT, Identifier.get("baz"))
    val pathPartSpec = listOf(Identifier.get("foo"), 0, Identifier.get("baz"))
    assertTrue(PathConverterSupport.pathMatches(pathSpec, pathPartSpec))
  }
}
