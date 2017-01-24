package org.midonet.cluster.data.storage

import rx.Observable

import org.midonet.cluster.data._

trait StorageInternals {

    /**
      * Provides an internal observable to the object with the specified class,
      * identifier and storage version.
      */
    protected def internalObservable[T](clazz: Class[T], id: ObjId,
                                        onClose: => Unit): Observable[T]

}
