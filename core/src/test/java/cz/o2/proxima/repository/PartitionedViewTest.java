/**
 * Copyright 2017-2018 O2 Czech Republic, a.s.
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
package cz.o2.proxima.repository;

import com.typesafe.config.ConfigFactory;
import cz.o2.proxima.functional.Consumer;
import cz.o2.proxima.storage.AttributeWriterBase;
import cz.o2.proxima.storage.Partition;
import cz.o2.proxima.storage.StreamElement;
import cz.o2.proxima.util.SerializableCountDownLatch;
import cz.o2.proxima.view.PartitionedLogObserver;
import cz.o2.proxima.view.PartitionedView;
import cz.seznam.euphoria.core.client.dataset.Dataset;
import cz.seznam.euphoria.core.client.io.StdoutSink;
import cz.seznam.euphoria.core.client.operator.MapElements;
import cz.seznam.euphoria.executor.local.LocalExecutor;
import java.io.Serializable;
import java.util.Collection;
import org.junit.After;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 * Test {@link PartitionedView} capability of {@link cz.o2.proxima.storage.InMemStorage}.
 */
public class PartitionedViewTest implements Serializable {

  private final transient Repository repo = ConfigRepository.Builder.of(
      ConfigFactory.load()
          .withFallback(ConfigFactory.load("test-reference.conf"))
          .resolve()).build();

  private transient LocalExecutor executor;
  private final transient EntityDescriptor entity = repo.findEntity("event").get();
  private final transient AttributeDescriptor<byte[]> attr = entity.<byte[]>findAttribute("data").get();

  private transient PartitionedView view;
  private transient AttributeWriterBase<byte[]> writer;

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() {
    executor = new LocalExecutor();
    AttributeFamilyDescriptor family = repo.getAllFamilies()
        .filter(af -> af.getName().equals("event-storage-stream"))
        .findAny()
        .get();

    view = family.getPartitionedView().get();
    writer = (AttributeWriterBase) family.getWriter().get();
  }

  @After
  public void tearDown() {
    executor.abort();
  }

  @Test(timeout = 2000)
  public void testViewConsumption() throws InterruptedException {
    assertEquals(1, view.getPartitions().size());
    SerializableCountDownLatch latch = new SerializableCountDownLatch(1);
    SerializableCountDownLatch start = new SerializableCountDownLatch(1);

    Dataset<String> result = view.observePartitions(
        view.getPartitions(), new PartitionedLogObserver<Object, String>() {

      @Override
      public void onRepartition(Collection<Partition> assigned) {
        start.countDown();
      }

      @Override
      public boolean onNext(
          StreamElement<Object> ingest,
          ConfirmCallback confirm,
          Partition partition,
          Consumer<String> collector) {

        collector.accept(ingest.getKey());
        confirm.confirm();
        return true;
      }

      @Override
      public boolean onError(Throwable error) {
        throw new RuntimeException(error);
      }

    });

    MapElements.of(result)
        .using(e -> {
          latch.countDown();
          return e;
        })
        .output()
        .persist(new StdoutSink<>());

    executor.submit(result.getFlow());

    start.await();

    // write data to event
    writer.online().write(
        StreamElement.update(entity, attr, "uuid", "key", "data",
            System.currentTimeMillis(), new byte[] { }),
        (succ, exc) -> latch.countDown());

    latch.await();
  }

}
