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
package cz.o2.proxima.util;

import cz.o2.proxima.direct.commitlog.LogObserver;
import cz.o2.proxima.direct.commitlog.ObserveHandle;
import cz.o2.proxima.direct.core.DirectAttributeFamilyDescriptor;
import cz.o2.proxima.direct.core.DirectDataOperator;
import cz.o2.proxima.functional.Consumer;
import cz.o2.proxima.repository.Repository;
import cz.o2.proxima.repository.TransformationDescriptor;
import cz.o2.proxima.storage.StreamElement;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Utility class for running transformations locally. */
@Slf4j
public class TransformationRunner {

  /**
   * Run all transformations in given repository.
   *
   * @param repo the repository
   * @param direct the operator to run transformations with
   */
  public static void runTransformations(Repository repo, DirectDataOperator direct) {

    repo.getTransformations()
        .forEach((name, desc) -> runTransformation(direct, name, desc, i -> {}));
  }

  /**
   * Run all transformations in given repository.
   *
   * @param repo the repository
   * @param direct the operator to run transformations with
   * @param onReplicated callback to be called before write to replicated target
   */
  public static void runTransformations(
      Repository repo, DirectDataOperator direct, Consumer<StreamElement> onReplicated) {

    repo.getTransformations()
        .entrySet()
        .stream()
        .map(
            entry ->
                Pair.of(
                    entry.getKey(),
                    runTransformation(direct, entry.getKey(), entry.getValue(), onReplicated)))
        .forEach(
            p -> {
              ExceptionUtils.unchecked(p.getSecond()::waitUntilReady);
              log.info("Started transformation {}", p.getFirst());
            });
  }

  /**
   * Run given transformation in local JVM.
   *
   * @param direct the operator to run transformations with
   * @param name name of the transformation
   * @param desc the transformation to run
   * @param onReplicated callback to be called before write to replicated target
   * @return {@link ObserveHandle} of the transformation
   */
  public static ObserveHandle runTransformation(
      DirectDataOperator direct,
      String name,
      TransformationDescriptor desc,
      Consumer<StreamElement> onReplicated) {

    return desc.getAttributes()
        .stream()
        .flatMap(
            attr ->
                direct
                    .getFamiliesForAttribute(attr)
                    .stream()
                    .filter(af -> af.getDesc().getAccess().canReadCommitLog()))
        .collect(Collectors.toSet())
        .stream()
        .findAny()
        .flatMap(DirectAttributeFamilyDescriptor::getCommitLogReader)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No commit log reader for attributes of transformation " + desc))
        .observe(
            name,
            new LogObserver() {
              @Override
              public boolean onNext(StreamElement ingest, OnNextContext context) {
                desc.getTransformation()
                    .asElementWiseTransform()
                    .apply(
                        ingest,
                        transformed -> {
                          log.debug(
                              "Transformation {}: writing original {} transformed {}",
                              name,
                              ingest,
                              transformed);
                          onReplicated.accept(transformed);
                          direct
                              .getWriter(transformed.getAttributeDescriptor())
                              .get()
                              .write(transformed, context::commit);
                        });
                return true;
              }

              @Override
              public boolean onError(Throwable error) {
                log.error("Error in transformer {}", name, error);
                throw new RuntimeException(error);
              }
            });
  }

  private TransformationRunner() {}
}
