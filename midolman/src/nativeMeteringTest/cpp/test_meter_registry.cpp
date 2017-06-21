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
#include "gtest/gtest.h"

#include "nativeMeterRegistry.h"

using namespace testing;

static NativeFlowStats rndFlowStats() {
    int64_t packets = rand() % 65355 + 1;
    int64_t bytes = rand() % 65355 + packets;
    return NativeFlowStats(packets, bytes);
}

class UtilsTest: public Test {
    private:
        static bool first_time;
    protected:
        UtilsTest() {
            if (first_time) {
                srand(time(NULL));
                first_time = false;
            }
        }
};
bool UtilsTest::first_time = true;

TEST_F(UtilsTest, meter_registry_placeholder_test) {
  ASSERT_EQ(sizeof(int), 4);
}

TEST(NativeMeterRegistryTest, test_regular_constructor) {
    NativeMeterRegistry reg;
    auto keys = reg.get_meter_keys();
    EXPECT_EQ(keys.empty(), true) << "key list not empty on start";
}

TEST(NativeMeterRegistryGetMeterTest, test_empty_registry) {
    const MeterTag KEY("some_key");

    NativeMeterRegistry reg;
    auto fs = reg.get_meter(KEY);
    EXPECT_FALSE(fs.is_defined()) << "non-existent key found";
}

TEST(NativeMeterRegistryTrackFlowTest, test_no_tags) {
    const FlowMatch FM("flow1");
    std::vector<MeterTag> tags;

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_TRUE(keys.empty());
}

TEST(NativeMeterRegistryTrackFlowTest, test_tag) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
}

TEST(NativeMeterRegistryTrackFlowTest, test_tag_multi_flow) {
    const FlowMatch FM1("flow1");
    const FlowMatch FM2("flow2");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    const MeterTag M3("m3");
    const MeterTag M4("m4");
    std::vector<MeterTag> tags1 = {M1, M2};
    std::vector<MeterTag> tags2 = {M3, M4};

    NativeMeterRegistry reg;
    reg.track_flow(FM1, tags1);
    reg.track_flow(FM2, tags2);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 4);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_TRUE(reg.get_meter(M3).is_defined());
    EXPECT_TRUE(reg.get_meter(M4).is_defined());
}

TEST(NativeMeterRegistryTrackFlowTest, test_tag_overlap_flow) {
    const FlowMatch FM1("flow1");
    const FlowMatch FM2("flow2");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    const MeterTag M3("m3");
    std::vector<MeterTag> tags1 = {M1, M2};
    std::vector<MeterTag> tags2 = {M2, M3};

    NativeMeterRegistry reg;
    reg.track_flow(FM1, tags1);
    reg.track_flow(FM2, tags2);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 3);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_TRUE(reg.get_meter(M3).is_defined());
}

TEST(NativeMeterRegistryTrackRecordFlow, test_single_packet) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const uint32_t PACKET_SIZE = rand() % 65535 + 1;

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);
    reg.record_packet(PACKET_SIZE, tags);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), 1);
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), PACKET_SIZE);
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), 1);
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), PACKET_SIZE);
}

TEST(NativeMeterRegistryTrackRecordFlow, test_multi_packet) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    const MeterTag M3("m3");
    std::vector<MeterTag> tags1 = {M1, M2};
    std::vector<MeterTag> tags2 = {M2, M3};
    const uint32_t PACKET_SIZE1 = rand() % 65535 + 1;
    const uint32_t PACKET_SIZE2 = rand() % 65535 + 1;

    NativeMeterRegistry reg;
    // Note, we should automatically add tag M3
    reg.track_flow(FM, tags1);
    reg.record_packet(PACKET_SIZE1, tags1);
    reg.record_packet(PACKET_SIZE2, tags2);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 3);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_TRUE(reg.get_meter(M3).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), 1);
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), PACKET_SIZE1);
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), 2);
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), PACKET_SIZE1 + PACKET_SIZE2);
    EXPECT_EQ(reg.get_meter(M3).value().get_packets(), 1);
    EXPECT_EQ(reg.get_meter(M3).value().get_bytes(), PACKET_SIZE2);
}

TEST(NativeMeterRegistryUpdateFlow, test_unknown_update) {
    const FlowMatch UNKNOWN("unknown_flow");
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const NativeFlowStats stats = rndFlowStats();

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);
    reg.update_flow(UNKNOWN, stats);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), 0);
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), 0);
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), 0);
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), 0);
}

TEST(NativeMeterRegistryUpdateFlow, test_single_update) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const NativeFlowStats stats = rndFlowStats();

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);
    reg.update_flow(FM, stats);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), stats.get_packets());
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), stats.get_bytes());
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), stats.get_packets());
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), stats.get_bytes());
}

TEST(NativeMeterRegistryUpdateFlow, test_multi_update) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const NativeFlowStats STATS1 = rndFlowStats();
    const NativeFlowStats STATS2 = rndFlowStats();
    NativeFlowStats TOTAL(STATS1);
    TOTAL.add(STATS2);

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);
    reg.update_flow(FM, STATS1);
    reg.update_flow(FM, TOTAL);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), TOTAL.get_packets());
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), TOTAL.get_bytes());
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), TOTAL.get_packets());
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), TOTAL.get_bytes());

    // Reset by sending a smaller number
    reg.update_flow(FM, STATS2);
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), TOTAL.get_packets() + STATS2.get_packets());
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), TOTAL.get_bytes() + STATS2.get_bytes());
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), TOTAL.get_packets() + STATS2.get_packets());
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), TOTAL.get_bytes() + STATS2.get_bytes());
}

TEST(NativeMeterRegistryUpdateFlow, test_multi_flow) {
    const FlowMatch FM1("flow1");
    const FlowMatch FM2("flow2");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const NativeFlowStats STATS1 = rndFlowStats();
    const NativeFlowStats STATS2 = rndFlowStats();
    NativeFlowStats TOTAL(STATS1);
    TOTAL.add(STATS2);

    NativeMeterRegistry reg;
    reg.track_flow(FM1, tags);
    reg.track_flow(FM2, tags);
    reg.update_flow(FM1, STATS1);
    reg.update_flow(FM2, STATS2);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), TOTAL.get_packets());
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), TOTAL.get_bytes());
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), TOTAL.get_packets());
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), TOTAL.get_bytes());
}

TEST(NativeMeterRegistryForgetFlow, test_multi_update) {
    const FlowMatch FM("flow1");
    const MeterTag M1("m1");
    const MeterTag M2("m2");
    std::vector<MeterTag> tags = {M1, M2};
    const NativeFlowStats STATS1 = rndFlowStats();
    const NativeFlowStats STATS2 = rndFlowStats();
    NativeFlowStats TOTAL(STATS1);
    TOTAL.add(STATS2);

    NativeMeterRegistry reg;
    reg.track_flow(FM, tags);
    reg.update_flow(FM, STATS1);

    // forget flow (next updates should be ignored)

    reg.forget_flow(FM);
    reg.update_flow(FM, TOTAL);

    std::vector<MeterTag> keys = reg.get_meter_keys();
    EXPECT_EQ(keys.size(), 2);
    EXPECT_TRUE(reg.get_meter(M1).is_defined());
    EXPECT_TRUE(reg.get_meter(M2).is_defined());
    EXPECT_EQ(reg.get_meter(M1).value().get_packets(), STATS1.get_packets());
    EXPECT_EQ(reg.get_meter(M1).value().get_bytes(), STATS1.get_bytes());
    EXPECT_EQ(reg.get_meter(M2).value().get_packets(), STATS1.get_packets());
    EXPECT_EQ(reg.get_meter(M2).value().get_bytes(), STATS1.get_bytes());
}

