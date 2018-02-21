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
package cz.o2.proxima.view;

import cz.o2.proxima.functional.BiConsumer;
import cz.o2.proxima.storage.OnlineAttributeWriter;
import cz.o2.proxima.storage.Partition;
import cz.o2.proxima.storage.StreamElement;
import cz.o2.proxima.storage.randomaccess.RandomAccessReader;
import cz.o2.proxima.util.Pair;
import java.util.Collection;

/**
 * A view of commit-log that caches (and potentially checkpoints) data
 * from partitions and makes in random accessible.
 *
 * Client can provide callbacks to be called on data updates.
 * Client might also update the view, all updates must be persisted in a way that
 * will guarantee that subsequent reloads will contain the new data and
 * that read-write operations have happens-before relation semantics.
 */
public interface PartitionedCachedView
    extends RandomAccessReader, OnlineAttributeWriter {

  /**
   * Assign and make given partitions accessible by random reads.
   * If the view contains any partitions not listed in the collection,
   * these partitions are dropped.
   * @param partitions the partitions to cache locally
   */
  default void assign(Collection<Partition> partitions) {
    assign(partitions, (e, c) -> { });
  }

  /**
   * Assign and make given partitions accessible by random reads.
   * If the view contains any partitions not listed in the collection,
   * these partitions are dropped.
   * @param partitions the partitions to cache locally
   * @param updateCallback function that is called when cache gets updated
   *                       the function takes the new ingest element and pair of
   *                       old object that was associated with the key and timestamp
   *                       of the old record
   */
  void assign(
      Collection<Partition> partitions,
      BiConsumer<StreamElement, Pair<Long, Object>> updateCallback);

  /**
   * Retrieve currently assigned partitions.
   * @return currently assigned partitions
   */
  Collection<Partition> getAssigned();

}