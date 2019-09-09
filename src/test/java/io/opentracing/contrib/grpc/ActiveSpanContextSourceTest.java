/*
 * Copyright 2017-2019 The OpenTracing Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.grpc.Context;
import io.opentracing.Span;
import io.opentracing.mock.MockTracer;
import org.junit.Before;
import org.junit.Test;

public class ActiveSpanContextSourceTest {

  private final MockTracer tracer = new MockTracer();

  @Before
  public void before() {
    tracer.reset();
  }

  @Test
  public void testDefaultNone() {
    ActiveSpanContextSource ss = ActiveSpanContextSource.NONE;
    assertNull("active span context should always be null", ss.getActiveSpanContext());

    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getKey(), span);
    Context previousCtx = ctx.attach();

    assertNull("active span context should always be null", ss.getActiveSpanContext());

    ctx.detach(previousCtx);
    span.finish();
  }

  @Test
  public void testDefaultGrpc() {
    ActiveSpanContextSource ss = ActiveSpanContextSource.GRPC_CONTEXT;
    assertNull(
        "active span context should be null, no span context in OpenTracingContextKey",
        ss.getActiveSpanContext());

    Span span = tracer.buildSpan("s0").start();
    Context ctx =
        Context.current().withValue(OpenTracingContextKey.getSpanContextKey(), span.context());
    Context previousCtx = ctx.attach();

    assertEquals(
        "active span context should be OpenTracingContextKey.activeSpanContext()",
        ss.getActiveSpanContext(),
        span.context());

    ctx.detach(previousCtx);
    span.finish();

    assertNull(
        "active span context should be null, no span context in OpenTracingContextKey",
        ss.getActiveSpanContext());
  }
}
