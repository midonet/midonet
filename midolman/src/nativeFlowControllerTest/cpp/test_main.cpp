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

TEST(FlowTagIndexer, test_flow_removed) {
  FlowTagIndexer indexer;
  FlowId id = 0xdeadbeef;
  FlowTag tag = 0xcafebeef;
  indexer.index_flow_tags(id, {tag});
  auto flows_to_remove = indexer.invalidate(tag);
  ASSERT_EQ(flows_to_remove.size(), 1);
  ASSERT_EQ(flows_to_remove.at(0), id);
}

TEST(FlowTagIndexer, test_multiple_tags) {
  FlowId id = 0xdeadbeef;
  FlowTag tag1 = 0xd00dbeef;
  FlowTag tag2 = 0xbabebeef;
  FlowTagIndexer indexer;
  indexer.index_flow_tags(id, {tag1, tag2});
  auto flows_to_remove = indexer.invalidate(tag1);
  ASSERT_EQ(flows_to_remove.size(), 1);
  ASSERT_EQ(flows_to_remove.at(0), id);

  flows_to_remove = indexer.invalidate(tag2);
  ASSERT_EQ(flows_to_remove.size(), 0);

  ASSERT_EQ(indexer.flows_for_tag(tag1).size(), 0);
  ASSERT_EQ(indexer.flows_for_tag(tag2).size(), 0);
}

bool find_flow(std::vector<FlowId> haystack, FlowId needle) {
  auto iter = haystack.begin();
  while (iter != haystack.end()) {
    if (*iter == needle) {
      return true;
    }
    iter++;
  }
  return false;
}

TEST(FlowTagIndexer, test_multiple_flows_invalidated) {
  FlowId id1 = 0xdeadbeef;
  FlowId id2 = 0xfeedbeef;
  FlowTag tag1 = 0xd00dbeef;
  FlowTag tag2 = 0xbabebeef;
  FlowTagIndexer indexer;

  indexer.index_flow_tags(id1, {tag1});
  indexer.index_flow_tags(id2, {tag1, tag2});

  auto flows_to_remove = indexer.invalidate(tag1);
  ASSERT_EQ(flows_to_remove.size(), 2);
  ASSERT_EQ(find_flow(flows_to_remove, id1), true);
  ASSERT_EQ(find_flow(flows_to_remove, id2), true);

  flows_to_remove = indexer.invalidate(tag2);
  ASSERT_EQ(flows_to_remove.size(), 0);

  ASSERT_EQ(indexer.flows_for_tag(tag1).size(), 0);
  ASSERT_EQ(indexer.flows_for_tag(tag2).size(), 0);
}

TEST(FlowTagIndexer, test_flow_removed_from_tag_list) {
  FlowId id1 = 0xdeadbeef;
  FlowId id2 = 0xfeedbeef;
  FlowTag tag1 = 0xd00dbeef;
  FlowTag tag2 = 0xbabebeef;
  FlowTagIndexer indexer;

  indexer.index_flow_tags(id1, {tag1, tag2});
  indexer.index_flow_tags(id2, {tag1, tag2});

  auto flows_for_tag1 = indexer.flows_for_tag(tag1);
  ASSERT_EQ(flows_for_tag1.size(), 2);
  ASSERT_EQ(find_flow(flows_for_tag1, id1), true);
  ASSERT_EQ(find_flow(flows_for_tag1, id2), true);

  auto flows_for_tag2 = indexer.flows_for_tag(tag2);
  ASSERT_EQ(flows_for_tag2.size(), 2);
  ASSERT_EQ(find_flow(flows_for_tag2, id1), true);
  ASSERT_EQ(find_flow(flows_for_tag2, id2), true);

  indexer.remove_flow(id1);

  flows_for_tag1 = indexer.flows_for_tag(tag1);
  ASSERT_EQ(flows_for_tag1.size(), 1);
  ASSERT_EQ(find_flow(flows_for_tag1, id2), true);

  flows_for_tag2 = indexer.flows_for_tag(tag2);
  ASSERT_EQ(flows_for_tag2.size(), 1);
  ASSERT_EQ(find_flow(flows_for_tag2, id2), true);
}

TEST(FlowTagIndexer, test_tag_removed_when_no_more_flows) {
  FlowId id1 = 0xdeadbeef;
  FlowId id2 = 0xfeedbeef;
  FlowTag tag = 0xd00dbeef;
  FlowTagIndexer indexer;

  indexer.index_flow_tags(id1, {tag});
  indexer.index_flow_tags(id2, {tag});
  ASSERT_EQ(indexer.tag_count(), 1);

  indexer.remove_flow(id1);
  ASSERT_EQ(indexer.tag_count(), 1);

  indexer.remove_flow(id2);
  ASSERT_EQ(indexer.tag_count(), 0);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
