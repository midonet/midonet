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

import scala.concurrent.Future

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.{RecyclingContext, RecyclingException}

/**
  * A callback that executes when the recycler completed modifying the root
  * NSDB znode. If the update was successful, the recycler will begin
  * collecting the obsolete znodes.
  */
object RecyclerValidator extends BackgroundCallback {

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug s"Verifying if NSDB is recyclable ${context.step()}"
        context.curator.getData
               .inBackground(RecyclableValidator, context, context.executor)
               .forPath(context.store.basePath)
        context.future
    }

    override def processResult(client: CuratorFramework,
                               event: CuratorEvent): Unit = {
        val context = event.getContext.asInstanceOf[RecyclingContext]
        if (event.getResultCode == Code.OK.intValue()) {
            try {
                context.version =
                    Integer.parseInt(ZKPaths.getNodeFromPath(event.getPath))
                context.log debug s"NSDB version is ${context.version}"
            } catch {
                case e: NumberFormatException =>
                    context.log error s"Invalid NSDB version for path ${event.getPath}"
                    context fail RecyclingException(context, e)
                    return
            }
            context.timestamp = event.getStat.getMtime
            context.success()
        } else if (event.getResultCode == Code.BADVERSION.intValue()) {
            context.log info "NSDB root has been concurrently modified: canceling"
            context.success()
        } else {
            context fail event
        }
    }

}
