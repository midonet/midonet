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

#include <chrono>
#include <nativeFlowController.h>
#include <nativeFlowExpirationIndexer.h>

using namespace testing;
using namespace std::chrono;

const long long error_exp = duration_cast<nanoseconds>(seconds(5)).count();
const long long flow_exp = duration_cast<nanoseconds>(minutes(1)).count();
const long long stateful_exp = duration_cast<nanoseconds>(seconds(30)).count();
const long long tunnel_exp = duration_cast<nanoseconds>(minutes(5)).count();

const int error_type = 0;
const int flow_type = 1;
const int stateful_type = 2;
const int tunnel_type = 3;

TEST(FlowExpirationIndexer, test_flow_removed_on_hard_timeout) {
  FlowExpirationIndexer *expirer = new FlowExpirationIndexer();

  FlowId flow1 = 123;
  FlowId result = NULL_ID;

  long long now = 0;

  expirer->enqueue_flow_expiration(flow1, now+flow_exp, flow_type);

  now += duration_cast<nanoseconds>(seconds(59)).count();
  result = expirer->poll_for_expired(now);
  ASSERT_EQ(result, NULL_ID);

  now += duration_cast<nanoseconds>(seconds(2)).count();
  result = expirer->poll_for_expired(now);
  ASSERT_EQ(result, flow1);
  result = expirer->poll_for_expired(now);
  ASSERT_EQ(result, NULL_ID);
}

TEST(FlowExpirationIndexer, test_multiple_expiration_types) {
  FlowExpirationIndexer *expirer = new FlowExpirationIndexer();

  long long now = 0;

  expirer->enqueue_flow_expiration(10, now+error_exp, error_type);
  expirer->enqueue_flow_expiration(20, now+flow_exp, flow_type);
  expirer->enqueue_flow_expiration(30, now+stateful_exp, stateful_type);
  expirer->enqueue_flow_expiration(40, now+tunnel_exp, tunnel_type);

  now += duration_cast<nanoseconds>(minutes(10)).count();

  ASSERT_EQ(expirer->poll_for_expired(now), 10);
  ASSERT_EQ(expirer->poll_for_expired(now), 20);
  ASSERT_EQ(expirer->poll_for_expired(now), 30);
  ASSERT_EQ(expirer->poll_for_expired(now), 40);
  ASSERT_EQ(expirer->poll_for_expired(now), NULL_ID);
}

TEST(FlowExpirationIndexer, test_multiple_expiration_types_in_order) {
  FlowExpirationIndexer *expirer = new FlowExpirationIndexer();

  long long now = 0;

  expirer->enqueue_flow_expiration(10, now+error_exp, error_type);
  expirer->enqueue_flow_expiration(20, now+flow_exp, flow_type);
  expirer->enqueue_flow_expiration(30, now+stateful_exp, stateful_type);
  expirer->enqueue_flow_expiration(40, now+tunnel_exp, tunnel_type);

  now += duration_cast<nanoseconds>(seconds(5)).count();
  ASSERT_EQ(expirer->poll_for_expired(now), 10);

  now += duration_cast<nanoseconds>(seconds(25)).count();
  ASSERT_EQ(expirer->poll_for_expired(now), 30);

  now += duration_cast<nanoseconds>(seconds(30)).count();
  ASSERT_EQ(expirer->poll_for_expired(now), 20);

  now += duration_cast<nanoseconds>(minutes(4)).count();
  ASSERT_EQ(expirer->poll_for_expired(now), 40);
  ASSERT_EQ(expirer->poll_for_expired(now), NULL_ID);
}

TEST(FlowExpirationIndexer, test_evict_flows) {
  FlowExpirationIndexer *expirer = new FlowExpirationIndexer();

  for (int i = 0; i < 3; i++) {
    expirer->enqueue_flow_expiration(i, flow_exp, flow_type);
  }

  ASSERT_EQ(expirer->evict_flow(), 0);
  ASSERT_EQ(expirer->evict_flow(), 1);
  ASSERT_EQ(expirer->evict_flow(), 2);
  ASSERT_EQ(expirer->evict_flow(), NULL_ID);
}
