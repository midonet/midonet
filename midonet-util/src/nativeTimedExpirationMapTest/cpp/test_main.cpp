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

#include <algorithm>
#include <memory>
#include "nativeTimedExpirationMap.h"

using namespace testing;

TEST(NativeTimedExpirationMapTests, test_put_and_ref) {
  auto map = new NativeTimedExpirationMap();
  auto prev = map->put_and_ref("A", "X");
  ASSERT_FALSE(prev);
  ASSERT_EQ(map->get("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 1);

  prev = map->put_and_ref("A", "Y");
  ASSERT_EQ(prev.value(), "X");
  ASSERT_EQ(map->get("A").value(), "Y");
  ASSERT_EQ(map->ref_count("A"), 2);
}

TEST(NativeTimedExpirationMapTests, test_put_if_absent_and_ref) {
  auto map = new NativeTimedExpirationMap();
  auto count = map->put_if_absent_and_ref("A", "X");
  ASSERT_EQ(count, 1);
  ASSERT_EQ(map->get("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 1);

  count = map->put_if_absent_and_ref("A", "Y");
  ASSERT_EQ(count, 2);
  ASSERT_EQ(map->get("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 2);
}

TEST(NativeTimedExpirationMapTests, test_iteration) {
  auto map = new NativeTimedExpirationMap();
  map->put_if_absent_and_ref("A", "X");
  map->put_if_absent_and_ref("B", "Y");
  map->put_if_absent_and_ref("C", "Z");

  auto iter = std::unique_ptr<NativeTimedExpirationMap::Iterator>(map->iterator());
  std::string acc;
  while (!iter->at_end()) {
    acc = acc + iter->cur_key() + iter->cur_value();
    iter->next();
  }
  std::sort(acc.begin(), acc.end());
  ASSERT_EQ(acc, "ABCXYZ");
}

TEST(NativeTimedExpirationMapTests, test_ref) {
  auto map = new NativeTimedExpirationMap();
  ASSERT_FALSE(map->ref("A"));
  map->put_if_absent_and_ref("A", "X");
  ASSERT_EQ(map->ref("A").value(), "X");
  ASSERT_EQ(map->get("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 2);
}

TEST(NativeTimedExpirationMap, test_unref) {
  auto map = new NativeTimedExpirationMap();
  ASSERT_FALSE(map->unref("A", 0));
  map->put_if_absent_and_ref("A", "X");
  ASSERT_EQ(map->ref("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 2);
  ASSERT_EQ(map->unref("A", 0).value(), "X");
  ASSERT_EQ(map->ref_count("A"), 1);
  ASSERT_EQ(map->unref("A", 0).value(), "X");
  ASSERT_EQ(map->ref_count("A"), 0);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
