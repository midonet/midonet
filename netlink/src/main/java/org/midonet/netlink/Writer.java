/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

/** Stateless interface for serializing POJO application
 *  objects to ByteBuffer as netlink messages. */
public interface Writer<V> {

  /** Returns the attribute id of a given T value. */
  short attrIdOf(V value);

  /** Serialize into a receiving ByteBuffer a T value and returns the number of
   *  bytes written. The translator should writes the value continously and
   *  should not care about netlink attribute headers. */
  int serializeInto(ByteBuffer receiver, V value);
}
