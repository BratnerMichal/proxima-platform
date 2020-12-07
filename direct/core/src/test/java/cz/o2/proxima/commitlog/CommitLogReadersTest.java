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
package cz.o2.proxima.commitlog;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cz.o2.proxima.direct.commitlog.CommitLogReader;
import cz.o2.proxima.direct.commitlog.CommitLogReaders;
import cz.o2.proxima.direct.commitlog.LogObserver;
import cz.o2.proxima.storage.Partition;
import cz.o2.proxima.storage.ThroughputLimiter;
import cz.o2.proxima.storage.commitlog.Position;
import java.time.Duration;
import java.util.Arrays;
import org.junit.Test;
import org.mockito.Mockito;

public class CommitLogReadersTest {
  private static class NoOpThroughputLimiter implements ThroughputLimiter {
    @Override
    public Duration getPauseTime(ThroughputLimiter.Context context) {
      return Duration.ZERO;
    }

    @Override
    public void close() {
      // no-op
    }
  }

  @Test
  public void testLimitedReaderInstantiation() {
    final CommitLogReader mockReader = mock(CommitLogReader.class);
    // Get Partitions may not be supported in test environment, because it may issue remote
    // fetches...
    Mockito.doThrow(UnsupportedOperationException.class).when(mockReader).getPartitions();
    CommitLogReaders.withThroughputLimit(mockReader, new NoOpThroughputLimiter());
  }

  @Test
  public void testLimitedReaderPartitionCaching() {
    final CommitLogReader mockReader = mock(CommitLogReader.class);
    doReturn(Arrays.asList(Partition.of(0), Partition.of(1))).when(mockReader).getPartitions();
    final CommitLogReader reader =
        CommitLogReaders.withThroughputLimit(mockReader, new NoOpThroughputLimiter());
    reader.observe("test", Position.OLDEST, mock(LogObserver.class));
    reader.observe("test", Position.OLDEST, mock(LogObserver.class));
    reader.observe("test", Position.OLDEST, mock(LogObserver.class));
    verify(mockReader, times(1)).getPartitions();
  }
}