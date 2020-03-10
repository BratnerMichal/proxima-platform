/**
 * Copyright 2017-2020 O2 Czech Republic, a.s.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cz.o2.proxima.direct.kafka;

import cz.o2.proxima.util.TestUtils;
import org.junit.Test;

public class TopicOffsetTest {

  @Test
  public void testHashCodeEquals() {
    TopicOffset off1 = new TopicOffset(0, 1, 2);
    TopicOffset off2 = new TopicOffset(0, 1, 2);
    TestUtils.assertHashCodeAndEquals(off1, off2);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNegativeOffset() {
    TopicOffset off = new TopicOffset(0, -1, 0);
  }
}