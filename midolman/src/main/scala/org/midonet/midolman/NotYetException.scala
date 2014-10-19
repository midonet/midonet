/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable

case class NotYetException(waitFor: Future[_],
        msg: String = "Async computation in progress") extends Exception(msg) {
    override def fillInStackTrace(): Throwable = this
}
