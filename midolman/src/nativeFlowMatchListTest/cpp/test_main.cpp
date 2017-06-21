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

#include "nativeFlowMatchList.h"

using namespace testing;

TEST(FlowMatchList, test_pop_when_empty) {
  FlowMatchList flow_list;
  ASSERT_EQ(flow_list.pop_flow_match(), std::string());
}

TEST(FlowMatchList, test_stack_behaviour) {
  FlowMatchList flow_list;
  std::string match1("match1");
  std::string match2("match2");
  ASSERT_EQ(flow_list.size(), 0);
  flow_list.push_flow_match(match1);
  ASSERT_EQ(flow_list.size(), 1);
  flow_list.push_flow_match(match2);
  ASSERT_EQ(flow_list.size(), 2);
  ASSERT_EQ(flow_list.pop_flow_match(), match2);
  ASSERT_EQ(flow_list.size(), 1);
  ASSERT_EQ(flow_list.pop_flow_match(), match1);
  ASSERT_EQ(flow_list.size(), 0);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
