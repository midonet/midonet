/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.cli.commands.objects

import java.lang.reflect.Field

import org.midonet.cluster.data.ZoomObject

object Obj {

    def getFields(clazz: Class[_]): Seq[Field] = {
        val thisFields = clazz.getDeclaredFields
            .filter(_.getAnnotation(classOf[CliName]) ne null)
            .map(f => { f.setAccessible(true); f })
            .toSeq
        if (clazz.getSuperclass != classOf[ZoomObject]) {
            val superFields = getFields(clazz.getSuperclass)
            val annotation = clazz.getSuperclass.getAnnotation(classOf[CliName])
            if (annotation ne null) {
                if (annotation.prepend()) superFields ++ thisFields
                else thisFields ++ superFields
            } else {
                superFields ++ thisFields
            }
        } else thisFields
    }

    def getFieldsMap(clazz: Class[_]): Map[String, Field] = {
        val superFields =
            if (clazz.getSuperclass != classOf[ZoomObject])
                getFieldsMap(clazz.getSuperclass)
            else Map.empty
        superFields ++ clazz.getDeclaredFields
            .filter(_.getAnnotation(classOf[CliName]) ne null)
            .map(f => {
                f.setAccessible(true)
                f.getAnnotation(classOf[CliName]).name() -> f
            }).toMap
    }

    def getFieldsAnnotation(clazz: Class[_]): Seq[CliName] = {
        clazz.getDeclaredFields
            .filter(_.getAnnotation(classOf[CliName]) ne null)
            .map(_.getAnnotation(classOf[CliName]))
    }

}

/** Provides common method for storage objects. */
trait Obj {

    def getFields: Seq[Field] = Obj.getFields(getClass)

    def getFieldsMap: Map[String, Field] = Obj.getFieldsMap(getClass)

    override def toString = {
        val builder = new StringBuilder(s"${getClass.getSimpleName} ")
        for (field <- getFields) {
            builder ++= s"${field.getAnnotation(classOf[CliName]).name()}=" +
                        s"${field.get(this)} "
        }
        builder.toString()
    }
}
