/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.brain.services.vxgw;

import java.util.List;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.Entity;

abstract class DeviceMonitorTestBase<KEY,
                                   DEVICE extends Entity.Base<KEY, ?, DEVICE>> {

    Observable<KEY> extractEvent(Observable<EntityIdSetEvent<KEY>> obs,
                                 final EntityIdSetEvent.Type t) {
        return obs.filter(new Func1<EntityIdSetEvent, Boolean>() {
            @Override
            public Boolean call(EntityIdSetEvent event) {
                return event.type == t;
            }
        }).map(new Func1<EntityIdSetEvent<KEY>, KEY>() {
            @Override
            public KEY call(EntityIdSetEvent<KEY> event) {
                return event.value;
            }
        });
    }

    Subscription addIdObservableToList(final Observable<KEY> obs,
                                       final List<KEY> list) {
        return obs.subscribe(new Action1<KEY>() {
            @Override
            public void call(KEY id) {
                list.add(id);
            }
        });
    }


    Subscription addDeviceObservableToList(final Observable<DEVICE> obs,
                                           final List<KEY> list) {
        return obs.subscribe(new Action1<DEVICE>() {
            @Override
            public void call(DEVICE d) {
                list.add(d.getId());
            }
        });
    }


}
