/**
 * Copyright 2017 O2 Czech Republic, a.s.
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

package cz.o2.proxima.storage;

import cz.o2.proxima.repository.AttributeDescriptor;
import cz.o2.proxima.repository.EntityDescriptor;
import cz.o2.proxima.storage.commitlog.CommitLogReader;
import cz.o2.proxima.storage.commitlog.LogObserver;
import cz.o2.proxima.storage.randomaccess.KeyValue;
import cz.o2.proxima.storage.randomaccess.RandomAccessReader;
import cz.o2.proxima.storage.randomaccess.RandomAccessReader.Offset;
import cz.o2.proxima.util.Pair;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;
import cz.o2.proxima.storage.commitlog.BulkLogObserver;
import cz.o2.proxima.storage.commitlog.Cancellable;
import cz.o2.proxima.view.PartitionedLogObserver;
import cz.o2.proxima.view.PartitionedView;
import cz.seznam.euphoria.core.client.dataset.Dataset;
import cz.seznam.euphoria.core.client.flow.Flow;
import cz.seznam.euphoria.core.client.io.DataSource;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;

/**
 * InMemStorage for testing purposes.
 */
public class InMemStorage extends StorageDescriptor<InMemStorage.Writer> {

  private static class RawOffset implements Offset {

    final String raw;

    RawOffset(String key) {
      raw = key;
    }

  }

  @FunctionalInterface
  private interface InMemIngestWriter extends Serializable {
    void write(StreamElement data);
  }

  public final class Writer
      extends AbstractOnlineAttributeWriter {

    private Writer(EntityDescriptor entityDesc, URI uri) {
      super(entityDesc, uri);
    }


    @Override
    public void write(StreamElement data, CommitCallback statusCallback) {
      InMemStorage.this.data.put(
          getURI().getPath() + "/" + data.getKey() + "#" + data.getAttribute(),
          data.getValue());

      synchronized (observers) {
        observers.values().forEach(o -> o.write(data));
      }
      statusCallback.commit(true, null);
    }
  }

  private static class InMemCommitLogReader
      extends AbstractStorage
      implements CommitLogReader, PartitionedView {

    private final NavigableMap<Integer, InMemIngestWriter> observers;

    private InMemCommitLogReader(
        EntityDescriptor entityDesc, URI uri,
        NavigableMap<Integer, InMemIngestWriter> observers) {

      super(entityDesc, uri);
      this.observers = observers;
    }

    @Override
    public void close() {

    }

    @Override
    public List<Partition> getPartitions() {
      return Collections.singletonList(() -> 0);
    }

    @Override
    public Cancellable observe(
        String name,
        Position position,
        LogObserver observer) {

      if (position != Position.NEWEST) {
        throw new UnsupportedOperationException(
            "Cannot read from position " + position);
      }
      final int id;
      synchronized (observers) {
        id = observers.isEmpty() ? 0 : observers.lastKey();
        observers.put(id, elem -> observer.onNext(elem, () -> 0, (succ, exc) -> { }));
      }
      return () -> {
        observers.remove(id);
        observer.onCancelled();
      };
    }

    @Override
    public Cancellable observePartitions(
        Collection<Partition> partitions,
        Position position,
        boolean stopAtCurrent,
        LogObserver observer) {

      if (stopAtCurrent) {
        throw new UnsupportedOperationException("Cannot stop at current with this reader");
      }
      return observe(null, position, observer);
    }

    @Override
    public Cancellable observeBulk(
        String name,
        Position position,
        BulkLogObserver observer) {

      if (position != Position.NEWEST) {
        throw new UnsupportedOperationException(
            "Cannot read from position " + position);
      }
      final int id;
      synchronized (observers) {
        id = observers.isEmpty() ? 0 : observers.lastKey();
        observers.put(id, elem -> observer.onNext(elem, () -> 0, (succ, exc) -> { }));
      }
      return () -> {
        observers.remove(id);
        observer.onCancelled();
      };
    }

    @Override
    public <T> Dataset<T> observePartitions(
        Flow flow,
        Collection<Partition> partitions,
        PartitionedLogObserver<T> observer) {

      if (partitions.size() != 1 || partitions.stream().findFirst().get().getId() != 0) {
        throw new IllegalArgumentException(
            "This fake implementation has only single partition");
      }

      SynchronousQueue<T> queue = new SynchronousQueue<>();
      observe("partitionedView-" + flow.getName(), new LogObserver() {
        @Override
        public boolean onNext(StreamElement ingest, LogObserver.ConfirmCallback confirm) {
          observer.onNext(ingest, confirm::confirm, () -> 0, e -> {
            try {
              queue.put(e);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          });
          return true;
        }

        @Override
        public void onError(Throwable error) {
          throw new RuntimeException(error);
        }

        @Override
        public void close() throws Exception {

        }

      });

      return flow.createInput(new DataSource<T>() {

        @Override
        public List<cz.seznam.euphoria.core.client.io.Partition<T>> getPartitions() {
          return Arrays.asList(new cz.seznam.euphoria.core.client.io.Partition<T>() {
            @Override
            public Set<String> getLocations() {
              return Collections.singleton("local");
            }

            @Override
            public cz.seznam.euphoria.core.client.io.Reader<T> openReader() throws IOException {

              return new cz.seznam.euphoria.core.client.io.Reader<T>() {
                @Override
                public void close() throws IOException {

                }

                @Override
                public boolean hasNext() {
                  return true;
                }

                @Override
                @SuppressWarnings("unchecked")
                public T next() {
                  try {
                    return queue.take();
                  } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                  }
                }
              };
            }

          });
        }

        @Override
        public boolean isBounded() {
          return false;
        }

      });

    }

    @Override
    public <T> Dataset<T> observe(
        Flow flow,
        String name,
        PartitionedLogObserver<T> observer) {

      // FIXME
      throw new UnsupportedOperationException("Unsupported yet.");
    }

  }

  private static final class Reader
      extends AbstractStorage
      implements RandomAccessReader {

    private final NavigableMap<String, byte[]> data;

    private Reader(
        EntityDescriptor entityDesc, URI uri,
        NavigableMap<String, byte[]> data) {

      super(entityDesc, uri);
      this.data = data;
    }

    @Override
    public Optional<KeyValue<?>> get(
        String key,
        String attribute,
        AttributeDescriptor<?> desc) {

      return data.entrySet().stream()
          .filter(
              e -> e.getKey().equals(
                  getURI().getPath() + "/" + key + "#" + attribute))
          .findFirst()
          .map(kv -> {
            try {
              return KeyValue.of(
                  getEntityDescriptor(),
                  desc, key, attribute,
                  new RawOffset(attribute),
                  kv.getValue());
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void scanWildcard(
        String key,
        AttributeDescriptor<?> wildcard,
        @Nullable Offset offset,
        int limit,
        Consumer<KeyValue<?>> consumer) {

      String off = offset == null ? "" : ((RawOffset) offset).raw;
      String prefix = wildcard.toAttributePrefix(false);
      String start = getURI().getPath() + "/" + key + "#" + prefix;
      int count = 0;
      for (Map.Entry<String, byte[]> e : data.tailMap(start).entrySet()) {
        if (e.getKey().startsWith(start)) {
          int hash = e.getKey().lastIndexOf("#");
          String attribute = e.getKey().substring(hash + 1);
          if (attribute.equals(off)) {
            continue;
          }
          consumer.accept(KeyValue.of(
              getEntityDescriptor(),
              (AttributeDescriptor) wildcard,
              key,
              attribute,
              new RawOffset(attribute),
              wildcard.getValueSerializer().deserialize(e.getValue()),
              e.getValue()));
          if (++count == limit) {
            break;
          }
        } else {
          break;
        }
      }
    }


    @Override
    public void listEntities(
        Offset offset,
        int limit,
        Consumer<Pair<Offset, String>> consumer) {

      throw new UnsupportedOperationException("Unsupported.");
    }

    @Override
    public void close() {

    }

    @Override
    public Offset fetchOffset(Listing type, String key) {
      return new RawOffset(key);
    }

  }


  @Getter
  private final NavigableMap<String, byte[]> data;
  private final NavigableMap<Integer, InMemIngestWriter> observers;

  public InMemStorage() {
    super(Arrays.asList("inmem"));
    this.data = Collections.synchronizedNavigableMap(new TreeMap<>());
    this.observers = Collections.synchronizedNavigableMap(new TreeMap<>());
  }

  @Override
  public DataAccessor<Writer> getAccessor(
      EntityDescriptor entityDesc, URI uri, Map<String, Object> cfg) {

    return new DataAccessor<Writer>() {

      Writer writer = new Writer(entityDesc, uri);
      InMemCommitLogReader commitLogReader = new InMemCommitLogReader(
          entityDesc, uri, observers);
      Reader reader = new Reader(entityDesc, uri, data);

      @Override
      public Optional<Writer> getWriter() {
        return Optional.of(writer);
      }

      @Override
      public Optional<CommitLogReader> getCommitLogReader() {
        return Optional.of(commitLogReader);
      }

      @Override
      public Optional<RandomAccessReader> getRandomAccessReader() {
        return Optional.of(reader);
      }

      @Override
      public Optional<PartitionedView> getPartitionedView() {
        return Optional.of(commitLogReader);
      }

    };
  }

}
