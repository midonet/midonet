/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

/** Stateless interface for constructing and serializing POJO application
 *  objects to and from netlink messages. */
public interface Translator<V> extends Reader<V>, Writer<V> { }
