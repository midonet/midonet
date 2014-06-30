/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

/** Stateless interface for constructing POJO application
 *  objects from netlink messages contained in ByteBuffers. */
public interface Reader<V> {

  /** Constructs a new instance of type T from a ByteBuffer. The translator can
   *  assume that the read pointer was moved at the beginning of the bytes
   *  representing the value of type T, after proper parsing of the netlink
   *  attribute header. In the event that a valid value of type T cannot be
   *  built from the ByteBuffer, the translator should throw an exception, but
   *  it should never return null to signal a failure. */
  V deserializeFrom(ByteBuffer source);
}
