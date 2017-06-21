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
#include <cstdlib>
#include <ctime>
#include "gtest/gtest.h"

#include "nativeFlowStats.h"

using namespace testing;

class NativeFlowStatsTest: public Test {
    private:
        static bool first_time;
    protected:
        NativeFlowStatsTest() {
            if (first_time) {
                srand(time(NULL));
                first_time = false;
            }
        }
};

bool NativeFlowStatsTest::first_time = true;

TEST_F(NativeFlowStatsTest, test_value_size) {
  ASSERT_EQ(sizeof(uint64_t), 8) << "Wrong size for uint64_t";
}

TEST_F(NativeFlowStatsTest, test_zero_value) {
    NativeFlowStats fs;
    EXPECT_EQ(fs.get_packets(), 0) << "FlowStats packets not zero on init";
    EXPECT_EQ(fs.get_bytes(), 0) << "FlowStats bytes not zero on init";
}

TEST_F(NativeFlowStatsTest, test_regular_constructor) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats fs(PACKETS, BYTES);
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value after init";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value after init";
}

TEST_F(NativeFlowStatsTest, test_copy_constructor) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats source(PACKETS, BYTES);
    NativeFlowStats fs(source);
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value copied on init";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value copied on init";
}

TEST_F(NativeFlowStatsTest, test_copy_operator) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats source(PACKETS, BYTES);
    NativeFlowStats fs;
    fs = source;
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value copied";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value copied";
}

TEST_F(NativeFlowStatsTest, test_copy_self) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats fs(PACKETS, BYTES);
    fs = fs;
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value on self-copy";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value on self-copy";
}

TEST_F(NativeFlowStatsTest, test_update_values) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats fs;
    fs.add(PACKETS, BYTES);
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value after update";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value after update";
}

TEST_F(NativeFlowStatsTest, test_update_stats) {
    const int PACKETS = rand();
    const int BYTES = rand();
    NativeFlowStats fs;
    NativeFlowStats source(PACKETS, BYTES);
    fs.add(source);
    EXPECT_EQ(fs.get_packets(), PACKETS) << "Unexpected packets value after update from stats";
    EXPECT_EQ(fs.get_bytes(), BYTES) << "Unexpected bytes value after update from stats";
}

