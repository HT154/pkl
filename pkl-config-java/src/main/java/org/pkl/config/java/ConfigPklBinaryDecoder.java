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
package org.pkl.config.java;

import java.io.ByteArrayInputStream;
import java.util.Map;
import org.msgpack.core.MessageUnpacker;
import org.pkl.config.java.mapper.ValueMapper;
import org.pkl.config.java.mapper.ValueMapperBuilder;
import org.pkl.core.Composite;
import org.pkl.core.PklBinaryDecoder;

public class ConfigPklBinaryDecoder {
  private final ValueMapper mapper;

  /** Shorthand for {@code new ConfigPklBinaryDecoder(ValueMapperBuilder.preconfigured().build()) */
  static ConfigPklBinaryDecoder preconfigured() {
    return new ConfigPklBinaryDecoder(ValueMapperBuilder.preconfigured().build());
  }

  public ConfigPklBinaryDecoder(ValueMapper mapper) {
    this.mapper = mapper;
  }

  public ValueMapper getValueMapper() {
    return mapper;
  }

  public ConfigPklBinaryDecoder setValueMapper(ValueMapper mapper) {
    return new ConfigPklBinaryDecoder(mapper);
  }

  /**
   * Decode a config from the supplied byte array.
   *
   * @return the encoded config
   */
  public Config decode(byte[] bytes) {
    return makeConfig(PklBinaryDecoder.decode(bytes));
  }

  /**
   * Decode a config from the supplied {@link ByteArrayInputStream}.
   *
   * @return the encoded config
   */
  public Config decode(ByteArrayInputStream inputStream) {
    return makeConfig(PklBinaryDecoder.decode(inputStream));
  }

  /**
   * Decode a config from the supplied {@link MessageUnpacker}.
   *
   * @return the encoded config
   */
  public Config decode(MessageUnpacker unpacker) {
    return makeConfig(PklBinaryDecoder.decode(unpacker));
  }

  private Config makeConfig(Object decoded) {
    if (decoded instanceof Composite composite) {
      return new CompositeConfig("", mapper, composite);
    }
    if (decoded instanceof Map<?, ?> map) {
      return new MapConfig("", mapper, map);
    }
    return new LeafConfig("", mapper, decoded);
  }
}
