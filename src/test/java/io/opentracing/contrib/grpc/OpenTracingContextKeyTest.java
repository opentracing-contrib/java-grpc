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
import io.grpc.Context.Key;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.mock.MockTracer;
import org.junit.Before;
import org.junit.Test;

public class OpenTracingContextKeyTest {

  private final MockTracer tracer = new MockTracer();

  @Before
  public void before() {
    tracer.reset();
  }

  @Test
  public void testGetKey() {
    Context.Key<Span> key = OpenTracingContextKey.getKey();
    assertEquals("Key should have correct name", key.toString(), (OpenTracingContextKey.KEY_NAME));
  }

  @Test
  public void testGetSpanContextKey() {
    Key<SpanContext> key = OpenTracingContextKey.getSpanContextKey();
    assertEquals("Key should have correct name", key.toString(),
        OpenTracingContextKey.KEY_CONTEXT_NAME);
  }

  @Test
  public void testNoActiveSpan() {
    assertNull("activeSpan() should return null when no span is active",
        OpenTracingContextKey.activeSpan());
  }

  @Test
  public void testNoActiveSpanContext() {
    assertNull("activeSpanContext() should return null when no span context is active",
        OpenTracingContextKey.activeSpanContext());
  }

  @Test
  public void testGetActiveSpan() {
    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getKey(), span);
    Context previousCtx = ctx.attach();

    assertEquals(OpenTracingContextKey.activeSpan(), span);

    ctx.detach(previousCtx);
    span.finish();

    assertNull(OpenTracingContextKey.activeSpan());
  }

  @Test
  public void testGetActiveSpanContext() {
    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getSpanContextKey(),
        span.context());
    Context previousCtx = ctx.attach();

    assertEquals(OpenTracingContextKey.activeSpanContext(), span.context());

    ctx.detach(previousCtx);
    span.finish();

    assertNull(OpenTracingContextKey.activeSpanContext());
  }

  @Test
  public void testMultipleContextLayers() {
    Span parentSpan = tracer.buildSpan("s0").start();
    Context parentCtx = Context.current().withValue(OpenTracingContextKey.getKey(), parentSpan);
    Context previousCtx = parentCtx.attach();

    Span childSpan = tracer.buildSpan("s1").start();
    Context childCtx = Context.current().withValue(OpenTracingContextKey.getKey(), childSpan);
    parentCtx = childCtx.attach();

    assertEquals(OpenTracingContextKey.activeSpan(), childSpan);

    childCtx.detach(parentCtx);
    childSpan.finish();

    assertEquals(OpenTracingContextKey.activeSpan(), parentSpan);

    parentCtx.detach(previousCtx);
    parentSpan.finish();

    assertNull(OpenTracingContextKey.activeSpan());
  }

  @Test
  public void testMultipleContextLayersForSpanContext() {
    Span parentSpan = tracer.buildSpan("s0").start();
    Context parentCtx = Context.current().withValue(OpenTracingContextKey.getSpanContextKey(),
        parentSpan.context());
    Context previousCtx = parentCtx.attach();

    Span childSpan = tracer.buildSpan("s1").start();
    Context childCtx = Context.current().withValue(OpenTracingContextKey.getSpanContextKey(),
        childSpan.context());
    parentCtx = childCtx.attach();

    assertEquals(OpenTracingContextKey.activeSpanContext(), childSpan.context());

    childCtx.detach(parentCtx);
    childSpan.finish();

    assertEquals(OpenTracingContextKey.activeSpanContext(), parentSpan.context());

    parentCtx.detach(previousCtx);
    parentSpan.finish();

    assertNull(OpenTracingContextKey.activeSpanContext());
  }
}
