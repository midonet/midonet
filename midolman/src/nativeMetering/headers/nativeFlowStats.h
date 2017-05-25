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
#ifndef _NATIVE_FLOW_STATS_H_
#define _NATIVE_FLOW_STATS_H_

#include <cstdint>

/*
 * A class to contain flow counters (packets and bytes)
 */
class NativeFlowStats {
    private:
        // Number of matched packets
        uint64_t packets;
        // Number of matched bytes
        uint64_t bytes;

    public:
        // Constructors
        NativeFlowStats(): packets(0), bytes(0) {}
        NativeFlowStats(uint64_t p, uint64_t b): packets(p), bytes(b) {}
        NativeFlowStats(const NativeFlowStats& fs)
            : packets(fs.packets), bytes(fs.bytes) {}

        // Inline accessors
        uint64_t get_packets() const {return packets;}
        uint64_t get_bytes() const {return bytes;}

        // Update
        void add(uint64_t p, uint64_t b);
        void add(const NativeFlowStats& delta) {
            add(delta.packets, delta.bytes);
        }

        // Copy operator
        NativeFlowStats& operator=(const NativeFlowStats& fs);
};

#endif /* _NATIVE_FLOW_STATS_H_ */

