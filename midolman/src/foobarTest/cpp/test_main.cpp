#include "gtest/gtest.h"

#include "foobar.h"

using namespace testing;

TEST(FoobarTests, test_foobar) {
  auto a = 1 + 2; // make sure we're using c++11
  auto foo = Foobar();
  ASSERT_TRUE(0 == 1);
}

int main(int argc, char **argv) {
  testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
