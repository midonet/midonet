/*
 * Copyright 2014 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import java.util.UUID
import java.io.File
import scala.util.Try
import scala.collection.JavaConversions.mapAsScalaMap

import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.LoggerFactory;

object VniMapping {

  class Reader

  val log = LoggerFactory getLogger classOf[Reader]

  def readFromJson(path: String): Map[Int,UUID] =
      Try {
          val mapType = classOf[java.util.Map[String,java.lang.Integer]]
          val file = new File(path)
          val jsonObject = (new ObjectMapper).readValue(file, mapType)
          val vniToUUID = jsonObject.foldLeft(Map[Int,UUID]()) {
              case (map, (k,v)) =>
                  map + (v.toInt -> UUID.fromString(k))
          }
          log.info("parsed uudi -> vni mapping at {} to {}", path, vniToUUID)
          vniToUUID
      } recover {
          case ex: Throwable =>
              log.error(s"failed to parse vni -> uuid mapping at $path", ex)
              Map[Int,UUID]()
      } get

}
