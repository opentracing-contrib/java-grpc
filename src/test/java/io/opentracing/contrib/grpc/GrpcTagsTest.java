/*
 * Copyright 2017-2018 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import org.assertj.core.data.MapEntry;
import org.junit.Test;

public class GrpcTagsTest {
  @Test
  public void testStatusOk() {
    final Status status = Status.OK;
    MockSpan span = new MockTracer().buildSpan("").start();
    GrpcTags.setStatusTags(span, status);
    assertThat(span.tags())
        .containsExactly(MapEntry.entry(GrpcTags.GRPC_STATUS.getKey(), status.getCode().name()));
  }

  @Test
  public void testStatusError() {
    final Status status = Status.INTERNAL;
    MockSpan span = new MockTracer().buildSpan("").start();
    GrpcTags.setStatusTags(span, status);
    assertThat(span.tags())
        .containsOnly(
            MapEntry.entry(GrpcTags.GRPC_STATUS.getKey(), status.getCode().name()),
            MapEntry.entry(Tags.ERROR.getKey(), Boolean.TRUE)
        );
  }
}