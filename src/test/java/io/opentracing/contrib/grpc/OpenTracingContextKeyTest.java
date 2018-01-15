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

import static org.junit.Assert.assertEquals;

import io.grpc.Context;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockTracer;
import org.junit.Test;

public class OpenTracingContextKeyTest {

  Tracer tracer = new MockTracer();

  @Test
  public void TestGetKey() {
    Context.Key<Span> key = OpenTracingContextKey.getKey();
    assertEquals("Key should have correct name", key.toString(), (OpenTracingContextKey.KEY_NAME));
  }

  @Test
  public void TestNoActiveSpan() {
    assertEquals("activeSpan() should return null when no span is active",
        OpenTracingContextKey.activeSpan(), null);
  }

  @Test
  public void TestGetActiveSpan() {
    Span span = tracer.buildSpan("s0").start();
    Context ctx = Context.current().withValue(OpenTracingContextKey.getKey(), span);
    Context previousCtx = ctx.attach();

    assertEquals(OpenTracingContextKey.activeSpan(), span);

    ctx.detach(previousCtx);
    span.finish();

    assertEquals(OpenTracingContextKey.activeSpan(), null);
  }

  @Test
  public void TestMultipleContextLayers() {
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

    assertEquals(OpenTracingContextKey.activeSpan(), null);
  }

  @Test
  public void TestWrappedCall() {

  }
}