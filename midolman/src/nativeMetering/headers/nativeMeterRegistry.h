/*
 * Copyright 2017 Midokura SARL
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
#ifndef _NATIVE_METER_REGISTRY_H_
#define _NATIVE_METER_REGISTRY_H_

#include <cstdint>
#include <string>
#include <vector>
#include <jni.h>

#include "nativeMapKeyHasher.h"
#include "nativeFlowStats.h"

/**
 * A container for flow meter values
 */
class NativeMeterRegistry {
    private:
        using MeterMap = tbb::concurrent_hash_map<std::string, NativeFlowStats,
                                                  NativeMapKeyHasher>;

        MeterMap meters;

    public:
        // Constructor
        NativeMeterRegistry();

        // Key handling
        std::vector<std::string> get_meter_keys() const;
        NativeFlowStats get_meter(const std::string& key) const;

        // Flow handling

        /**
         * @param fm is the string encoding the flow match object
         * @param tags is the set of relevant counter tags
         */
        void track_flow(const std::string& fm,
                        const std::vector<const std::string>& tags);

        /**
         * @param packet_len is the size of the packet
         * @param tags is the set of relevant counter tags
         */
        void record_packet(uint32_t packet_len,
                           const std::vector<const std::string>& tags);

        /**
         * @param fm is the string encoding the flow match object
         * @param stats contains the packets and bytes to add to the counters
         */
        void update_flow(const std::string& fm, const NativeFlowStats& stats);

        /**
         * @param fm is the string encoding the flow mathc object
         */
        void forget_flow(const std::string& fm);
};

#endif /* _NATIVE_METER_REGISTRY_H_ */

