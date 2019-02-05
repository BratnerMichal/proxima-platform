/**
 * Copyright 2017-2019 O2 Czech Republic, a.s.
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
package cz.o2.proxima.beam.direct.io;

import cz.o2.proxima.direct.commitlog.LogObserver;
import cz.o2.proxima.storage.StreamElement;
import cz.o2.proxima.util.Pair;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link LogObserver} that caches data in {@link BlockingQueue}.
 */
@Slf4j
class BlockingQueueLogObserver implements LogObserver {

  static BlockingQueueLogObserver create(String name, long limit) {
    return new BlockingQueueLogObserver(name, limit);
  }

  private final String name;
  private final AtomicReference<Throwable> error = new AtomicReference<>();
  private final BlockingQueue<Pair<StreamElement, OnNextContext>> queue;
  @Getter
  private OnNextContext lastContext;
  private long limit;

  private BlockingQueueLogObserver(String name, long limit) {
    this.name = name;
    this.limit = limit;
    queue = new SynchronousQueue<>();
  }

  @Override
  public boolean onError(Throwable error) {
    this.error.set(error);
    return false;
  }

  @Override
  public boolean onNext(StreamElement ingest, OnNextContext context) {
    log.debug("Received next element {}", ingest);
    try {
      if (limit-- > 0) {
        queue.put(Pair.of(ingest, context));
        context.getOffset();
        return true;
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  @Override
  public void onCancelled() {
    log.debug("Cancelled {} consumption by request.", name);
  }

  @Override
  public void onCompleted() {
    try {
      queue.put(Pair.of(null, null));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while passing end-of-stream.", ex);
    }
  }

  @Nullable
  StreamElement take() throws InterruptedException {
    Pair<StreamElement, OnNextContext> taken = queue.take();
    if (taken.getFirst() != null) {
      lastContext = taken.getSecond();
      return taken.getFirst();
    }
    return null;
  }

  @Nullable
  Throwable getError() {
    return error.get();
  }

}
