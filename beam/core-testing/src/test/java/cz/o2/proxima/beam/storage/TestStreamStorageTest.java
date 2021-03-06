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
package cz.o2.proxima.beam.storage;

import static org.junit.Assert.assertNotNull;

import com.typesafe.config.ConfigFactory;
import cz.o2.proxima.beam.core.BeamDataOperator;
import cz.o2.proxima.beam.core.io.StreamElementCoder;
import cz.o2.proxima.repository.AttributeDescriptor;
import cz.o2.proxima.repository.AttributeFamilyDescriptor;
import cz.o2.proxima.repository.EntityDescriptor;
import cz.o2.proxima.repository.Repository;
import cz.o2.proxima.storage.StreamElement;
import cz.o2.proxima.storage.commitlog.Position;
import java.util.UUID;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Test {@link TestStreamStorage}. */
public class TestStreamStorageTest {

  private final Repository repo =
      Repository.of(
          TestStreamStorage.replaceStorages(ConfigFactory.load("test-reference.conf").resolve()));
  private final EntityDescriptor gateway = repo.getEntity("gateway");
  private final AttributeDescriptor<byte[]> status = gateway.getAttribute("status");
  private final AttributeFamilyDescriptor family =
      repo.getFamiliesForAttribute(status)
          .stream()
          .filter(af -> af.getAccess().canReadCommitLog())
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("Missing commit log for " + status));
  private final long now = System.currentTimeMillis();

  private BeamDataOperator beam;

  @Before
  public void setUp() {
    beam = repo.getOrCreateOperator(BeamDataOperator.class);
  }

  @After
  public void tearDown() {
    beam.close();
  }

  @Test
  public void testReadingFromTestStream() {
    TestStream<StreamElement> input =
        TestStream.create(StreamElementCoder.of(repo))
            .addElements(newUpsert(), newUpsert())
            .advanceWatermarkToInfinity();
    TestStreamStorage.putStream(repo, family, input);
    Pipeline p = Pipeline.create();
    PCollection<StreamElement> data = beam.getStream(p, Position.OLDEST, false, false, status);
    PCollection<Long> count =
        data.apply(
                Window.<StreamElement>into(new GlobalWindows())
                    .triggering(AfterWatermark.pastEndOfWindow())
                    .discardingFiredPanes())
            .apply(Count.globally());
    PAssert.that(count).containsInAnyOrder(2L);
    assertNotNull(p.run());
  }

  private StreamElement newUpsert() {
    String uuid = UUID.randomUUID().toString();
    return StreamElement.upsert(gateway, status, uuid, uuid, status.getName(), now, new byte[] {1});
  }
}
