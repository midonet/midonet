package org.midonet.util

import scala.collection.mutable

/**
 * Assorted collection toolbox.
 */
package object collection {

    /**
     * Map links a type with an instance, the first time mapOnce is invoked it
     * will create and store the association. Subsequent calls will receive the
     * first created instance.
     */
    trait MapperToFirstCall {

        val map = mutable.Map[Class[_], AnyRef]()

        def mapOnce[T <: AnyRef](typeObject: Class[T])(call: => T): T = {
            map.get(typeObject) match {
                case Some(instance) =>
                    instance.asInstanceOf[T]
                case None =>
                    val instance = call
                    map.put(typeObject, instance)
                    instance
            }
        }
    }


}
