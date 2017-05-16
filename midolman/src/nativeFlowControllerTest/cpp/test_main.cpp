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

#include "nativeFlowController.h"

using namespace testing;

TEST(Utils, test_leading_zeros) {
  ASSERT_EQ(sizeof(int), 4);
  ASSERT_EQ(leading_zeros(-2147483648), 0);
  ASSERT_EQ(leading_zeros(0), 32);
  ASSERT_EQ(leading_zeros(1073741824), 1);
  ASSERT_EQ(leading_zeros(297376), 13);
}

TEST(Utils, next_pos_power_of_two) {
  ASSERT_EQ(sizeof(int), 4);
  ASSERT_EQ(next_pos_power_of_two(-2147483648), 1);
  ASSERT_EQ(next_pos_power_of_two(-1), 1);
  ASSERT_EQ(next_pos_power_of_two(0), 1);
  ASSERT_EQ(next_pos_power_of_two(1 << 15), 1 << 15);
  ASSERT_EQ(next_pos_power_of_two(1073741824), 1073741824);
  ASSERT_EQ(next_pos_power_of_two(std::numeric_limits<int>::max()), 1073741824);
}

TEST(FlowTable, test_put_and_get) {
  FlowTable table(4);
  std::string match1("match1");
  auto id = table.put(match1);
  ASSERT_NE(id, NULL_ID);

  ASSERT_EQ(table.get(id).flow_match(), match1);
}

TEST(FlowTable, test_put_to_full_table) {
  FlowTable table(4);
  std::string match1("match1");
  std::string match2("match2");
  std::string match3("match3");
  std::string match4("match4");
  std::string match5("match5");

  ASSERT_NE(table.put(match1), NULL_ID);
  ASSERT_NE(table.put(match2), NULL_ID);
  ASSERT_NE(table.put(match3), NULL_ID);
  ASSERT_NE(table.put(match4), NULL_ID);
  ASSERT_EQ(table.put(match5), NULL_ID);
  ASSERT_EQ(table.occupied(), 4);
}

TEST(FlowTable, test_clear_flow) {
  FlowTable table(4);
  std::string match1("match1");
  std::string match2("match2");
  std::string match3("match3");
  std::string match4("match4");

  auto id1 = table.put(match1);
  auto id2 = table.put(match2);
  auto id3 = table.put(match3);
  auto id4 = table.put(match4);

  ASSERT_EQ(table.occupied(), 4);
  table.clear(id2);
  ASSERT_EQ(table.occupied(), 3);
  ASSERT_EQ(table.get(id2).id(), NULL_ID);
}

TEST(FlowTable, test_eviction_candidate) {
  FlowTable table(4);
  std::string match1("match1");
  std::string match2("match2");
  std::string match3("match3");
  std::string match4("match4");

  auto id1 = table.put(match1);
  auto id2 = table.put(match2);
  auto id3 = table.put(match3);
  auto id4 = table.put(match4);

  ASSERT_EQ(table.occupied(), 4);
  auto evict1 = table.candidate_for_eviction();
  ASSERT_NE(evict1, NULL_ID);
  ASSERT_EQ(evict1, table.candidate_for_eviction());
  table.clear(evict1);

  auto evict2 = table.candidate_for_eviction();
  ASSERT_NE(evict2, NULL_ID);
  ASSERT_EQ(evict2, table.candidate_for_eviction());
  table.clear(evict2);

  auto evict3 = table.candidate_for_eviction();
  ASSERT_NE(evict3, NULL_ID);
  ASSERT_EQ(evict3, table.candidate_for_eviction());
  table.clear(evict3);

  auto evict4 = table.candidate_for_eviction();
  ASSERT_NE(evict4, NULL_ID);
  ASSERT_EQ(evict4, table.candidate_for_eviction());
  table.clear(evict4);

  ASSERT_EQ(table.occupied(), 0);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
