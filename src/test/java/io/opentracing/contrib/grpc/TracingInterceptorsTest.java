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

import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.testing.GrpcServerRule;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.GlobalTracerTestUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class TracingInterceptorsTest {

  private final MockTracer clientTracer = new MockTracer();
  private final MockTracer serverTracer = new MockTracer();

  @Rule
  public GrpcServerRule grpcServer = new GrpcServerRule();

  private TracedService service;

  @Before
  public void before() {
    GlobalTracerTestUtil.resetGlobalTracer();
    clientTracer.reset();
    serverTracer.reset();
    service = new TracedService();
  }

  @Test
  public void testTracedClientAndServer() {
    // register server tracer to verify active span on server side
    GlobalTracer.register(serverTracer);

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    ServerTracingInterceptor serverTracingInterceptor = new ServerTracingInterceptor(serverTracer);

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), serverTracingInterceptor);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("a client span should have been created for the request",
        1, clientTracer.finishedSpans().size());

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("a server span should have been created for the request",
        1, serverTracer.finishedSpans().size());
    assertEquals("a client span should have been created for the request",
        1, clientTracer.finishedSpans().size());

    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    // should ideally also make sure that the parent/child relation is there, but the MockTracer
    // doesn't allow for creating new contexts outside of its package to pass in to asChildOf
    assertTrue("client span should start before server span",
        clientSpan.startMicros() <= serverSpan.startMicros());

    // TODO: next assert sometimes fails
    // assertTrue("client span " + clientSpan.finishMicros() + " should end after server span "
    //    + serverSpan.finishMicros(), clientSpan.finishMicros() >= serverSpan.finishMicros());
  }

  private Callable<Integer> reportedSpansSize(final MockTracer mockTracer) {
    return new Callable<Integer>() {
      @Override
      public Integer call() {
        return mockTracer.finishedSpans().size();
      }
    };
  }
}
