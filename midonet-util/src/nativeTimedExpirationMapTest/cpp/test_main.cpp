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

TEST(NativeTimedExpirationMapTests, test_unref) {
  auto map = new NativeTimedExpirationMap();
  ASSERT_FALSE(map->unref("A", 0, 0));
  map->put_if_absent_and_ref("A", "X");
  ASSERT_EQ(map->ref("A").value(), "X");
  ASSERT_EQ(map->ref_count("A"), 2);
  ASSERT_EQ(map->unref("A", 0, 0).value(), "X");
  ASSERT_EQ(map->ref_count("A"), 1);
  ASSERT_EQ(map->unref("A", 0, 0).value(), "X");
  ASSERT_EQ(map->ref_count("A"), 0);
}

TEST(NativeTimedExpirationMapTests, test_higher_doesnt_prevent_lower) {
  auto map = new NativeTimedExpirationMap();
  map->put_and_ref("high", "Y");
  map->put_and_ref("A", "X");

  ASSERT_EQ(map->unref("high", 5*60*60*1000, 0).value(), "Y");
  ASSERT_EQ(map->unref("A", 0, 0).value(), "X");

  {
    auto iter = std::unique_ptr<NativeTimedExpirationMap::Iterator>(map->obliterate(1));
    std::string acc;
    while (!iter->at_end()) {
      acc = acc + iter->cur_key() + iter->cur_value();
      iter->next();
    }
    std::sort(acc.begin(), acc.end());
    ASSERT_EQ(acc, "AX");
  }

  ASSERT_FALSE(map->get("A"));
  ASSERT_EQ(map->get("high").value(), "Y");
}


std::pair<std::string,int> expire(NativeTimedExpirationMap* map,
                                  long current_time_millis,
                                  int expected_expired) {
  auto iter = std::unique_ptr<NativeTimedExpirationMap::Iterator>(map->obliterate(current_time_millis));
  int count = 0;
  std::string acc;

  while (!iter->at_end()) {
    count++;
    acc = acc + iter->cur_key() + iter->cur_value();
    iter->next();
  }
  std::sort(acc.begin(), acc.end());
  return std::make_pair(acc, count);
}

TEST(NativeTimedExpirationMapTests, test_multiple_queues) {
  auto map = new NativeTimedExpirationMap();

  for (int i = 0; i < 1000; i++) {
    map->put_and_ref("K" + std::to_string(i),
                     "V" + std::to_string(i));
  }
  std::array<std::string, 4> acc;
  for (int i = 0; i < 1000; i++) {
    int expiry = i % 4;
    auto key = "K" + std::to_string(i);
    auto value = map->unref(key, expiry, 0).value();
    acc[expiry] = acc[expiry] + key + value;
  }

  std::sort(acc[0].begin(), acc[0].end());
  auto expired = expire(map, 0, 250);
  ASSERT_EQ(expired.first, acc[0]);
  ASSERT_EQ(expired.second, 250);

  auto combined = acc[1] + acc[2];
  std::sort(combined.begin(), combined.end());
  expired = expire(map, 2, 500);
  ASSERT_EQ(expired.first, combined);
  ASSERT_EQ(expired.second, 500);

  std::sort(acc[3].begin(), acc[3].end());
  expired = expire(map, 3, 250);
  ASSERT_EQ(expired.first, acc[3]);
  ASSERT_EQ(expired.second, 250);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
