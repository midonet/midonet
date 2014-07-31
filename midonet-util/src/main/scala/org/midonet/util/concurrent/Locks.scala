/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util.concurrent

import java.util.concurrent.locks.{ReadWriteLock, Lock}

object Locks {

  def withLock[T](lock: Lock)(f: => T) = try {
    lock.lock()
    f
  } finally lock.unlock()

  def withReadLock[T](lock: ReadWriteLock)(f: => T) =
    withLock[T](lock.readLock())(f)

  def withWriteLock[T](lock: ReadWriteLock)(f: => T) =
    withLock[T](lock.writeLock())(f)

}
