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
package cz.o2.proxima.utils.zookeeper;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import cz.o2.proxima.functional.Consumer;
import cz.o2.proxima.storage.UriUtil;
import cz.o2.proxima.storage.watermark.GlobalWatermarkTracker;
import cz.o2.proxima.util.ExceptionUtils;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/** A {@link GlobalWatermarkTracker} that stores global information in Apache Zookeeper. */
@Slf4j
public class ZKGlobalWatermarkTracker implements GlobalWatermarkTracker {

  private static final long serialVersionUID = 1L;
  private static final long MAX_WATERMARK = Long.MAX_VALUE;

  static final String CFG_NAME = "name";
  static final String ZK_URI = "zk.url";
  static final String ZK_SESSION_TIMEOUT = "zk.timeout";

  private String trackerName;
  private String zkConnectString;
  private String parentNode;
  private int sessionTimeout;
  private transient volatile ZooKeeper client;

  @GuardedBy("this")
  private final Map<String, Long> partialWatermarks = new HashMap<>();

  private final AtomicLong globalWatermark = new AtomicLong(Long.MIN_VALUE);
  private transient volatile CreateMode parentCreateMode = CreateMode.CONTAINER;
  private transient volatile boolean parentCreated = false;
  @VisibleForTesting transient Map<String, Integer> pathToVersion = new ConcurrentHashMap<>();

  @Override
  public String getName() {
    return trackerName;
  }

  @Override
  public void setup(Map<String, Object> cfg) {
    URI uri = getZkUri(cfg);
    zkConnectString = String.format("%s:%d", uri.getHost(), uri.getPort());
    sessionTimeout = getSessionTimeout(cfg);
    trackerName = getTrackerName(cfg);
    parentNode = "/" + UriUtil.getPathNormalized(uri) + "/";
  }

  @Nonnull
  private String getTrackerName(Map<String, Object> cfg) {
    return Optional.ofNullable(cfg.get(CFG_NAME))
        .map(Object::toString)
        .orElseThrow(() -> new IllegalArgumentException("Missing " + CFG_NAME));
  }

  private int getSessionTimeout(Map<String, Object> cfg) {
    return Optional.ofNullable(cfg.get(ZK_SESSION_TIMEOUT))
        .map(Object::toString)
        .map(Integer::valueOf)
        .orElse(60000);
  }

  private URI getZkUri(Map<String, Object> cfg) {
    URI uri =
        Optional.ofNullable(cfg.get(ZK_URI))
            .map(Object::toString)
            .map(URI::create)
            .orElseThrow(() -> new IllegalArgumentException("Missing configuration " + ZK_URI));
    Preconditions.checkArgument(
        uri.getScheme().equalsIgnoreCase("zk"), "Unexpected scheme in %s, expected zk://", uri);
    return uri;
  }

  @Override
  public void initWatermarks(Map<String, Long> initialWatermarks) {
    CountDownLatch latch = new CountDownLatch(initialWatermarks.size());
    initialWatermarks.forEach(
        (k, v) -> {
          ExceptionUtils.ignoringInterrupted(() -> persistPartialWatermark(k, v).get());
          latch.countDown();
        });
    ExceptionUtils.ignoringInterrupted(latch::await);
  }

  @Override
  public CompletableFuture<Void> update(String processName, long currentWatermark) {
    if (currentWatermark < MAX_WATERMARK) {
      return persistPartialWatermark(processName, currentWatermark);
    }
    return deletePartialWatermark(processName);
  }

  @Override
  public long getGlobalWatermark(@Nullable String processName, long currentWatermark) {
    if (!parentCreated) {
      ExceptionUtils.ignoringInterrupted(this::createParentIfNotExists);
    }
    if (processName != null) {
      updatePartialWatermark(processName, currentWatermark);
    }
    return globalWatermark.get();
  }

  @VisibleForTesting
  static String getNodeName(@Nonnull String path) {
    int lastSlash = path.lastIndexOf("/");
    if (lastSlash < 0) {
      return path;
    }
    return path.substring(lastSlash + 1);
  }

  @Override
  public synchronized void close() {
    Optional.ofNullable(client)
        .ifPresent(
            c -> {
              // first nullify the client so that concurrent reads will not see closed client
              this.client = null;
              ExceptionUtils.ignoringInterrupted(c::close);
            });
    parentCreated = false;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("trackerName", trackerName)
        .add("zkConnectString", zkConnectString)
        .add("parentNode", parentNode)
        .add("sessionTimeout", sessionTimeout)
        .toString();
  }

  private CompletableFuture<Void> persistPartialWatermark(String name, long watermark) {
    CompletableFuture<Void> persisted = new CompletableFuture<>();
    byte[] bytes = longAsBytes(watermark);
    persistPartialWatermarkIntoFuture(name, bytes, persisted);
    return persisted;
  }

  private void persistPartialWatermarkIntoFuture(
      String name, byte[] bytes, CompletableFuture<Void> res) {
    if (!parentCreated) {
      ExceptionUtils.ignoringInterrupted(this::createParentIfNotExists);
    }
    setNodeDataToFuture(name, bytes, res);
  }

  private void handleError(Throwable err, String logString, CompletableFuture<Void> future) {
    log.warn(logString, err);
    future.completeExceptionally(err);
  }

  private void handleNoParentNode(String name, byte[] bytes, CompletableFuture<Void> res) {
    try {
      parentCreated = false;
      createParentIfNotExists();
      persistPartialWatermarkIntoFuture(name, bytes, res);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      res.completeExceptionally(ex);
    }
  }

  private CompletableFuture<Void> deletePartialWatermark(String name) {
    CompletableFuture<Void> persisted = new CompletableFuture<>();
    deleteNodeToFuture(name, persisted);
    return persisted;
  }

  private void deleteNodeToFuture(String name, CompletableFuture<Void> res) {
    String node = getNodeFromName(name);
    client()
        .delete(
            node,
            updateVersion(node),
            (code, path, ctx) -> {
              if (code == Code.CONNECTIONLOSS.intValue()
                  || code == Code.SESSIONEXPIRED.intValue()) {
                handleConnectionLoss(() -> deleteNodeToFuture(name, res));
              } else if (code == Code.BADVERSION.intValue()) {
                handleBadVersion(path, res, () -> deleteNodeToFuture(name, res));
              } else if (code != Code.OK.intValue() && code != Code.NONODE.intValue()) {
                handleError(
                    new RuntimeException(
                        String.format(
                            "Failed to delete watermark of %s: %s", name, Code.get(code))),
                    "Failed to delete watermark",
                    res);
              } else {
                updatePartialWatermark(name, Long.MAX_VALUE);
                res.complete(null);
              }
            },
            null);
  }

  private String getNodeFromName(String name) {
    return getParentNode() + "/" + name;
  }

  private void setNodeDataToFuture(String name, byte[] bytes, CompletableFuture<Void> res) {
    String path = getNodeFromName(name);
    final int currentVersion = updateVersion(path);
    client()
        .setData(
            path,
            bytes,
            currentVersion,
            (code, p, ctx, stat) -> {
              if (code == Code.CONNECTIONLOSS.intValue()
                  || code == Code.SESSIONEXPIRED.intValue()) {
                handleConnectionLoss(
                    () -> persistPartialWatermarkIntoFuture(getNodeName(path), bytes, res));
              } else if (code == Code.NONODE.intValue()) {
                createNodeIntoFuture(name, bytes, res);
              } else if (code == Code.BADVERSION.intValue()) {
                handleBadVersion(path, res, () -> setNodeDataToFuture(name, bytes, res));
              } else if (code == Code.OK.intValue()) {
                updatePartialWatermark(name, longFromBytes(bytes));
                setUpdateVersion(path, stat.getVersion());
                res.complete(null);
              } else {
                handleError(
                    new RuntimeException(
                        String.format(
                            "Failed to update watermark of %s: %s", path, Code.get(code))),
                    "Error updating watermark",
                    res);
              }
            },
            null);
  }

  private void createNodeIntoFuture(String name, byte[] bytes, CompletableFuture<Void> res) {
    String node = getNodeFromName(name);
    client()
        .create(
            node,
            bytes,
            Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL,
            (code, path, ctx, stat) -> {
              if (code == Code.SESSIONEXPIRED.intValue()
                  || code == Code.CONNECTIONLOSS.intValue()) {
                handleConnectionLoss(() -> persistPartialWatermarkIntoFuture(name, bytes, res));
              } else if (code == Code.NODEEXISTS.intValue()) {
                setNodeDataToFuture(name, bytes, res);
              } else if (code == Code.NONODE.intValue()) {
                handleNoParentNode(name, bytes, res);
              } else if (code != Code.OK.intValue()) {
                handleError(
                    new RuntimeException(
                        String.format(
                            "Failed to update watermark of %s: %s", name, Code.get(code))),
                    "Failed to update watermark",
                    res);
              } else {
                updatePartialWatermark(name, longFromBytes(bytes));
                setUpdateVersion(path, 0);
                res.complete(null);
              }
            },
            null);
  }

  private CompletableFuture<Integer> getNodeVersion(String path) {
    CompletableFuture<Integer> res = new CompletableFuture<>();
    getNodeVersionToFuture(path, res);
    return res;
  }

  private void getNodeVersionToFuture(String path, CompletableFuture<Integer> res) {
    client()
        .getData(
            path,
            true,
            (code, p, ctx, bytes, stat) -> {
              if (code == Code.CONNECTIONLOSS.intValue()
                  || code == Code.SESSIONEXPIRED.intValue()) {
                handleConnectionLoss(() -> getNodeVersionToFuture(path, res));
              } else if (code != Code.OK.intValue()) {
                res.completeExceptionally(
                    new RuntimeException(
                        String.format("Error fetching version of %s: %d", path, code)));
              } else {
                res.complete(stat.getVersion());
              }
            },
            null);
  }

  private void handleConnectionLoss(Runnable retry) {
    close();
    retry.run();
  }

  private void handleBadVersion(String path, CompletableFuture<Void> res, Runnable onSuccess) {
    getNodeVersion(path)
        .whenComplete(
            (v, exc) -> {
              if (exc != null) {
                res.completeExceptionally(exc);
              } else {
                setUpdateVersion(path, v);
                onSuccess.run();
              }
            });
  }

  @VisibleForTesting
  synchronized void createParentIfNotExists() throws InterruptedException {
    String node = getParentNode();
    if (!parentCreated) {
      try {
        createNodeIfNotExists(node);
        createWatchForChildren(node);
        parentCreated = true;
      } catch (KeeperException ex) {
        if (ex.code() == Code.SESSIONEXPIRED || ex.code() == Code.CONNECTIONLOSS) {
          close();
          createParentIfNotExists();
        } else if (ex.code() != Code.NODEEXISTS) {
          throw new RuntimeException(ex);
        }
      }
    }
  }

  @VisibleForTesting
  void refreshChildren() throws InterruptedException {
    createWatchForChildren(getParentNode());
  }

  private void createWatchForChildren(String node) throws InterruptedException {
    try {
      client()
          .getChildren(getParentNode(), true)
          .forEach(child -> handleWatchOnChildNode(node + "/" + child, false));
    } catch (KeeperException ex) {
      if (ex.code() == Code.SESSIONEXPIRED
          || ex.code() == Code.CONNECTIONLOSS
          || ex.code() == Code.NONODE) {
        handleConnectionLoss(
            () -> ExceptionUtils.ignoringInterrupted(this::createParentIfNotExists));
      } else {
        throw new RuntimeException(ex);
      }
    }
  }

  private void createNodeIfNotExists(String node) throws InterruptedException, KeeperException {
    try {
      Stat exists = client().exists(node, false);
      if (exists == null) {
        client().create(node, new byte[] {}, Ids.OPEN_ACL_UNSAFE, parentCreateMode);
      }
    } catch (KeeperException ex) {
      if (ex.code() == Code.CONNECTIONLOSS || ex.code() == Code.SESSIONEXPIRED) {
        close();
        createNodeIfNotExists(node);
      } else if (ex.code() == Code.NONODE) {
        File f = new File(node);
        // create parent node
        createNodeIfNotExists("/" + UriUtil.getPathNormalized(f.getParentFile().toURI()));
        // create this node
        createNodeIfNotExists(node);
      } else if (ex.code() == Code.UNIMPLEMENTED && parentCreateMode == CreateMode.CONTAINER) {
        parentCreateMode = CreateMode.PERSISTENT;
        log.warn(
            "Unimplemented error creating container node {}, fallback to {}",
            node,
            parentCreateMode,
            ex);
        createNodeIfNotExists(node);
      } else if (ex.code() != Code.NODEEXISTS) {
        throw ex;
      }
    }
  }

  private static byte[] longAsBytes(long watermark) {
    return String.valueOf(watermark).getBytes(StandardCharsets.UTF_8);
  }

  private static long longFromBytes(byte[] bytes) {
    return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
  }

  @VisibleForTesting
  String getParentNode() {
    return parentNode + this.trackerName;
  }

  private synchronized void updatePartialWatermark(String name, long watermark) {
    partialWatermarks.compute(name, (k, value) -> watermark);
    globalWatermark.set(
        partialWatermarks.values().stream().min(Long::compare).orElse(Long.MIN_VALUE));
  }

  private ZooKeeper client() {
    if (client == null) {
      synchronized (this) {
        if (client == null) {
          client = createNewZooKeeper();
        }
      }
    }
    return client;
  }

  @VisibleForTesting
  ZooKeeper createNewZooKeeper() {
    CountDownLatch connectLatch = new CountDownLatch(1);
    ZooKeeper zoo =
        ExceptionUtils.uncheckedFactory(
            () ->
                new ZooKeeper(
                    Objects.requireNonNull(zkConnectString),
                    sessionTimeout,
                    getWatcher(connectLatch)));
    ExceptionUtils.ignoringInterrupted(
        () -> {
          if (!connectLatch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout while connecting to ZK");
          }
        });
    return zoo;
  }

  @VisibleForTesting
  Watcher getWatcher(CountDownLatch connectLatch) {
    return event -> {
      if (event.getState() == KeeperState.SyncConnected && event.getType() == EventType.None) {
        connectLatch.countDown();
      } else {
        watchParentNode(event);
      }
    };
  }

  private void watchParentNode(WatchedEvent watchedEvent) {
    String path = watchedEvent.getPath();
    if (path != null) {
      if (path.equals(getParentNode())) {
        handleWatchOnParentNode();
      } else if (path.length() > getParentNode().length()) {
        handleWatchOnChildNode(path, watchedEvent.getType() == EventType.NodeDeleted);
      }
    }
  }

  private void handleWatchOnParentNode() {
    ExceptionUtils.ignoringInterrupted(() -> createWatchForChildren(getParentNode()));
  }

  private void handleWatchOnChildNode(String path, boolean isDelete) {
    String process = path.startsWith(getParentNode()) ? getNodeName(path) : "";
    if (isDelete && !process.isEmpty()) {
      updatePartialWatermark(process, Long.MAX_VALUE);
    } else {
      final AtomicReference<Runnable> retry = new AtomicReference<>();
      final DataCallback dataCallback =
          (code, p, ctx, data, stat) -> {
            if (code == Code.OK.intValue()) {
              long watermark = longFromBytes(data);
              setUpdateVersion(p, stat.getVersion());
              updatePartialWatermark(process, watermark);
            } else if (code == Code.CONNECTIONLOSS.intValue()
                || code == Code.SESSIONEXPIRED.intValue()) {
              handleConnectionLoss(retry.get());
            } else if (code != Code.NONODE.intValue()) {
              log.warn("Unhandled error in getting node data {}", code);
            }
          };
      retry.set(() -> client().getData(path, true, dataCallback, null));
      retry.get().run();
    }
  }

  int updateVersion(String path) {
    return Optional.ofNullable(pathToVersion.get(path)).orElse(-1);
  }

  private void setUpdateVersion(String path, int version) {
    setUpdateVersion(path, version, tmp -> {});
  }

  private void setUpdateVersion(String path, int version, Consumer<Integer> oldVersionConsumer) {
    pathToVersion.compute(
        path,
        (k, v) -> {
          if (v != null) {
            oldVersionConsumer.accept(v);
          }
          return version;
        });
  }

  protected Object readResolve() {
    pathToVersion = new ConcurrentHashMap<>();
    parentCreateMode = CreateMode.CONTAINER;
    return this;
  }
}
