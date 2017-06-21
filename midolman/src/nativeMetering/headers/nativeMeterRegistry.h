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
#include <memory>
#include <vector>
#include <jni.h>

#include "nativeMapKeyHasher.h"
#include "nativeFlowStats.h"
#include "nativeOption.h"

using FlowMatch = std::string;
using MeterTag = std::string;

/**
 * A container for flow meter values
 */
class NativeMeterRegistry {
    private:
        class FlowData {
            public:
                std::vector<MeterTag> tags;
                NativeFlowStats stats;
                FlowData(): tags(8), stats() {}
        };

        using FlowDataPtr = std::shared_ptr<FlowData>;
        using FlowMap = tbb::concurrent_hash_map<FlowMatch, FlowDataPtr,
                                                 NativeMapKeyHasher>;
        using MeterMap = tbb::concurrent_hash_map<MeterTag, NativeFlowStats,
                                                  NativeMapKeyHasher>;

        FlowMap flows;
        MeterMap meters;

    public:
        // Constructor
        NativeMeterRegistry();

        // Key handling

        /**
         * Get a list of all known meter keys
         *
         * NOTE: This is method is just used for testing purposes;
         *       therefore, it is not necessarily thread-safe.
         *
         * @return a vector with the list of meter keys
         */
        std::vector<MeterTag> get_meter_keys() const;

        /**
         * Get the flow stats (packets and bytes corresponding to a given key)
         *
         * @return optionally the flow stats (empty if key is not present)
         */

        Option<NativeFlowStats> get_meter(const MeterTag& key) const;

        // Flow handling

        /**
         * @param fm is the string encoding the flow match object
         * @param tags is the set of relevant counter tags
         */
        void track_flow(const FlowMatch& fm,
                        const std::vector<MeterTag>& meter_tags);

        /**
         * @param packet_len is the size of the packet (unsigned, from Java)
         * @param tags is the set of relevant counter tags
         */
        void record_packet(int32_t packet_len,
                           const std::vector<MeterTag>& tags);

        /**
         * @param fm is the string encoding the flow match object
         * @param stats contains the packets and bytes to add to the counters
         */
        void update_flow(const FlowMatch& fm, const NativeFlowStats& stats);

        /**
         * @param fm is the string encoding the flow mathc object
         */
        void forget_flow(const FlowMatch& fm);
};

#endif /* _NATIVE_METER_REGISTRY_H_ */

