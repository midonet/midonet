/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
