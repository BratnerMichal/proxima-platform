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
package cz.o2.proxima.direct.blob;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import cz.o2.proxima.annotations.Internal;
import cz.o2.proxima.direct.bulk.AbstractBulkFileSystemAttributeWriter;
import cz.o2.proxima.direct.bulk.FileSystem;
import cz.o2.proxima.direct.bulk.Path;
import cz.o2.proxima.direct.core.BulkAttributeWriter;
import cz.o2.proxima.direct.core.CommitCallback;
import cz.o2.proxima.direct.core.Context;
import cz.o2.proxima.repository.EntityDescriptor;
import cz.o2.proxima.storage.StreamElement;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** {@link BulkAttributeWriter} for blob storages. */
@Internal
@Slf4j
public abstract class BulkBlobWriter<BlobT extends BlobBase, AccessorT extends BlobStorageAccessor>
    implements BulkAttributeWriter {

  @Getter private final AccessorT accessor;
  @Getter private final Context context;
  private transient BulkAttributeWriter wrap;

  @Getter(AccessLevel.PACKAGE)
  private transient boolean initialized;

  private transient File tmpDir;

  public BulkBlobWriter(AccessorT accessor, Context context) {
    this.accessor = accessor;
    this.context = context;
  }

  /** Retrieve {@link EntityDescriptor} of this {@link BulkAttributeWriter}. */
  public EntityDescriptor getEntityDescriptor() {
    return accessor.getEntityDescriptor();
  }

  @Override
  public URI getUri() {
    return accessor.getUri();
  }

  @Override
  public void write(StreamElement data, long watermark, CommitCallback statusCallback) {
    init();
    wrap.write(data, watermark, statusCallback);
  }

  @Override
  public void updateWatermark(long watermark) {
    wrap.updateWatermark(watermark);
  }

  @Override
  public void rollback() {
    wrap.rollback();
  }

  private void init() {
    if (!initialized) {
      tmpDir = accessor.getTmpDir();
      FileSystem localFs = FileSystem.local(tmpDir, accessor.getNamingConvention());
      wrap =
          new AbstractBulkFileSystemAttributeWriter(
              getEntityDescriptor(),
              accessor.getUri(),
              localFs,
              accessor.getNamingConvention(),
              accessor.getFileFormat(),
              context,
              accessor.getRollPeriod(),
              accessor.getAllowedLateness()) {

            @Override
            protected void flush(Bulk bulk) {
              BulkBlobWriter.this.flush(bulk.getPath(), bulk.getMaxTs());
            }
          };

      if (!tmpDir.exists()) {
        tmpDir.mkdirs();
      } else if (tmpDir.isDirectory()) {
        removeDir(tmpDir);
        tmpDir.mkdirs();
      } else {
        throw new IllegalStateException("Temporary directory " + tmpDir + " is not directory");
      }
      initialized = true;
    }
  }

  private void removeDir(File dir) {
    Preconditions.checkArgument(dir.isDirectory());
    if (dir.exists()) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          if (f.isDirectory()) {
            removeDir(f);
          }
          Preconditions.checkState(f.delete());
        }
      }
      Preconditions.checkState(dir.delete());
    }
  }

  @VisibleForTesting
  void flush() {
    wrap.updateWatermark(Long.MAX_VALUE);
  }

  private void flush(Path file, long bucketEndStamp) {
    @SuppressWarnings("unchecked")
    BlobPath<BlobT> targetPath =
        (BlobPath<BlobT>) accessor.getTargetFileSystem().newPath(bucketEndStamp - 1);
    try (InputStream in = file.reader();
        OutputStream out = targetPath.writer()) {
      ByteStreams.copy(in, out);
      out.close();
      deleteHandlingErrors(file);
    } catch (Exception ex) {
      log.warn("Error while putting object {} to {}", file, targetPath, ex);
      deleteBlobIfExists(targetPath.getBlob());
      throw new IllegalStateException(ex);
    }
    log.info("Flushed source path {} to {}", file, targetPath);
  }

  /** Delete specified blob. */
  protected abstract void deleteBlobIfExists(BlobT blob);

  private void deleteHandlingErrors(Path f) {
    try {
      f.delete();
    } catch (IOException ex) {
      log.warn("Failed to delete {}. Ignoring", f, ex);
    }
  }

  @Override
  public void close() {
    wrap.close();
    if (tmpDir.exists() && tmpDir.isDirectory()) {
      try {
        removeDir(tmpDir);
      } catch (Exception ex) {
        log.error("Failed to remove directory {}. Ignored.", tmpDir, ex);
      }
    }
  }

  @VisibleForTesting
  String toBlobName(long ts) {
    return accessor.getNamingConvention().nameOf(ts);
  }
}
