/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

/** Interface used when iterating over attributes in a netlink message. */
public interface AttributeHandler {

  /** Called by the attribute scanner when a new attribute is found. It is
   *  assumed that the ByteBuffer position is set at the beginning of the
   *  attribute value, just after the attribute header, and that the ByteBuffer
   *  limit is set to the current position plus the attribute length. id is the
   *  raw id short read from the header, including any flag like "nested". */
  void use(ByteBuffer buffer, short id);
}
