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

import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.grpc.MethodDescriptor;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.GlobalTracerTestUtil;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TracingInterceptorsTest {

  private int port = 50050;
  private final MockTracer clientTracer = new MockTracer();
  private final MockTracer serverTracer = new MockTracer();
  private TracedService service;

  @Before
  public void before() {
    port++;
    GlobalTracerTestUtil.resetGlobalTracer();
    clientTracer.reset();
    serverTracer.reset();
    service = new TracedService();
  }

  @After
  public void after() {
    if (service != null) {
      service.stop();
    }
  }

  @Test
  public void TestTracedServerBasic() throws IOException {
    TracedClient client = new TracedClient("localhost", port, null);

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor(serverTracer);

    service.startWithInterceptor(tracingInterceptor, port);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertTrue("span should have no logs", span.logEntries().isEmpty());
    assertTrue("span should have no tags", span.tags().isEmpty());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedServerWithVerbosity() throws IOException {
    TracedClient client = new TracedClient("localhost", port, null);

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withVerbosity()
        .build();

    service.startWithInterceptor(tracingInterceptor, port);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should log onMessage and onComplete", 2, span.logEntries().size());
    assertTrue("span should have no tags", span.tags().isEmpty());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedServerWithStreaming() throws IOException {
    TracedClient client = new TracedClient("localhost", port, null);

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withStreaming()
        .build();

    service.startWithInterceptor(tracingInterceptor, port);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should log onMessage and onHalfClose", span.logEntries().size(), 2);
    assertTrue("span should have no tags", span.tags().isEmpty());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedServerWithCustomOperationName() throws IOException {
    final String PREFIX = "testing-";
    TracedClient client = new TracedClient("localhost", port, null);

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withOperationName(new OperationNameConstructor() {
          @Override
          public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
            return PREFIX + method.getFullMethodName();
          }
        })
        .build();

    service.startWithInterceptor(tracingInterceptor, port);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(),
        PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    assertTrue("span should have no tags", span.tags().isEmpty());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedServerWithTracedAttributes() throws IOException {
    TracedClient client = new TracedClient("localhost", port, null);

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withTracedAttributes(ServerTracingInterceptor.ServerRequestAttribute.values())
        .build();

    service.startWithInterceptor(tracingInterceptor, port);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    assertEquals("span should have a tag for each traced attribute",
        ServerTracingInterceptor.ServerRequestAttribute.values().length, span.tags().size());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientBasic() throws IOException {

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    service.start(port);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    assertEquals("span should have no tags", span.tags().size(), 0);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientWithVerbosity() throws IOException {

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withVerbosity()
        .build();
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    service.start(port);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    System.out.println(span.logEntries());
    assertEquals("span should have logs for start, onHeaders, onMessage, onClose, sendMessage", 5,
        span.logEntries().size());
    assertEquals("span should have no tags", span.tags().size(), 0);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientWithStreaming() throws IOException {

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withStreaming()
        .build();
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    service.start(port);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have log for onMessage, halfClose, sendMessage", 3,
        span.logEntries().size());
    assertEquals("span should have no tags", span.tags().size(), 0);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientWithOperationName() throws IOException {
    final String PREFIX = "testing-";

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withOperationName(new OperationNameConstructor() {
          @Override
          public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
            return PREFIX + method.getFullMethodName();
          }
        })
        .build();
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    service.start(port);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(),
        PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    assertEquals("span should have no tags", span.tags().size(), 0);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientWithTracedAttributes() throws IOException {

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withTracedAttributes(ClientTracingInterceptor.ClientRequestAttribute.values())
        .build();
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    service.start(port);

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    assertEquals("span should have tags for all client request attributes",
        ClientTracingInterceptor.ClientRequestAttribute.values().length, span.tags().size());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void TestTracedClientAndServer() throws IOException {
    // register server tracer to verify active span on server side
    GlobalTracer.register(serverTracer);

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient("localhost", port, tracingInterceptor);

    ServerTracingInterceptor serverTracingInterceptor = new ServerTracingInterceptor(serverTracer);

    service.startWithInterceptor(serverTracingInterceptor, port);

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