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
package cz.o2.proxima.storage.kafka;

import cz.o2.proxima.storage.StreamElement;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.apache.kafka.common.TopicPartition;

/**
 * Consumer of stream elements.
 * The callback may or might not be called depending on the consuming mode
 * (bulk or online).
 */
interface ElementConsumer {

  /**
   * Process the ingest element and return result of {@code onNext} call to the observer.
   * @param element element to process
   * @param tp partition
   * @param offset offset
   * @param errorHandler function to call on error
   * @return result of {@code onNext} call
   */
  boolean consumeWithConfirm(
      @Nullable StreamElement element,
      TopicPartition tp, long offset,
      Consumer<Throwable> errorHandler);
}

