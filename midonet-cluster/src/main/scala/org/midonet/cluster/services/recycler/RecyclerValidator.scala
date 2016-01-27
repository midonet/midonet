/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.recycler

import org.apache.curator.framework.api.CuratorEvent
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code

import rx.Observable

import org.midonet.cluster.services.recycler.Recycler.RecyclingException
import org.midonet.util.functors.makeFunc1

/**
  * Verifies that the current NSDB is recyclable by checking that the root ZOOM
  * znode was last modified before the current time minus the current recycling
  * interval. If the NSDB is recyclable the object will write to the root znode
  * to update its last modified timestamp.
  */
object RecyclerValidator extends RecyclerCommons {

    private val Data = Array[Byte](1)

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            context.log debug s"Recycling canceled"
            return Observable.empty()
        }
        context.log debug s"Verifying if NSDB is recyclable ${context.step()}"
        asObservable(context, throttle = false) {
            context.curator.getData
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.basePath)
        } flatMap makeFunc1 {
            validate(_, context)
        } flatMap makeFunc1 {
            mark(_, context)
        } flatMap makeFunc1 {
            process(_, context)
        }
    }

    private def validate(event: CuratorEvent, context: RecyclingContext)
    : Observable[CuratorEvent] = {
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue()) {
            if (context.start - event.getStat.getMtime > context.interval.toMillis) {
                Observable.just(event)
            } else {
                context.log debug "Skipping NSDB recycling: already recycled " +
                                  s"at ${event.getStat.getMtime} current " +
                                  s"time is ${context.start}"
                Observable.empty()
            }
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

    private def mark(event: CuratorEvent, context: RecyclingContext)
    : Observable[CuratorEvent] = {
        context.log debug s"Marking NSDB for recycling at ${context.start} " +
                          s"${context.step()}"
        asObservable(context, throttle = false) {
            context.curator.setData()
                   .withVersion(event.getStat.getVersion)
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.basePath, Data)
        }
    }

    private def process(event: CuratorEvent, context: RecyclingContext)
    : Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue()) {
            try {
                context.version =
                    Integer.parseInt(ZKPaths.getNodeFromPath(event.getPath))
                context.log debug s"NSDB version is ${context.version}"
            } catch {
                case e: NumberFormatException =>
                    context.log error s"Invalid NSDB version for path ${event.getPath}"
                    return Observable.error(RecyclingException(context, e))
            }
            context.timestamp = event.getStat.getMtime
            Observable.just(context)
        } else if (event.getResultCode == Code.BADVERSION.intValue()) {
            context.log info "NSDB root has been concurrently modified: canceling"
            Observable.empty()
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

}
