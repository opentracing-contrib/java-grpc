/*
 * Copyright 2017 The OpenTracing Authors
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

import io.grpc.Context;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockTracer;
import org.junit.Test;

public class ActiveSpanSourceTest {

  Tracer tracer = new MockTracer();

  @Test
  public void TestDefaultNone() {
    ActiveSpanSource ss = ActiveSpanSource.NONE;
    assertEquals("active span should always be null", ss.getActiveSpan(), null);

    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getKey(), span);
    Context previousCtx = ctx.attach();

    assertEquals("active span should always be null", ss.getActiveSpan(), null);

    ctx.detach(previousCtx);
    span.finish();
  }

  @Test
  public void TestDefaultGrpc() {
    ActiveSpanSource ss = ActiveSpanSource.GRPC_CONTEXT;
    assertEquals("active span should be null, no span in OpenTracingContextKey", ss.getActiveSpan(),
        null);

    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getKey(), span);
    Context previousCtx = ctx.attach();

    assertEquals("active span should be OpenTracingContextKey.activeSpan()", ss.getActiveSpan(),
        span);

    ctx.detach(previousCtx);
    span.finish();

    assertEquals("active span should be null, no span in OpenTracingContextKey", ss.getActiveSpan(),
        null);
  }

}