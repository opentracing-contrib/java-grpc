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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.testing.GrpcServerRule;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.GlobalTracerTestUtil;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class TracingInterceptorsTest {

  private static final Map<String, Object> BASE_TAGS = ImmutableMap.<String, Object>of(
      Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME,
      GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name()
  );

  private static final Map<String, Object> BASE_SERVER_TAGS = ImmutableMap.<String, Object>builder()
      .putAll(BASE_TAGS)
      .put(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
      .build();

  private static final Map<String, Object> BASE_CLIENT_TAGS = ImmutableMap.<String, Object>builder()
      .putAll(BASE_TAGS)
      .put(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
      .build();

  private static final Set<String> CLIENT_ATTRIBUTE_TAGS;

  static {
    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (ClientTracingInterceptor.ClientRequestAttribute attribute : ClientTracingInterceptor.ClientRequestAttribute
        .values()) {
      builder.add(attribute.key);
    }
    CLIENT_ATTRIBUTE_TAGS = builder.build();
  }

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
  public void testTracedServerBasic() {
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor(serverTracer);

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertTrue("span should have no logs", span.logEntries().isEmpty());
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerTwoInterceptors() {
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor(serverTracer);
    SecondServerInterceptor secondServerInterceptor = new SecondServerInterceptor(serverTracer);

    service.addGreeterServiceWithTwoInterceptors(tracingInterceptor, secondServerInterceptor,
        grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertTrue("span should have no logs", span.logEntries().isEmpty());
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithVerbosity() {
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withVerbosity()
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should log onMessage and onComplete", 2, span.logEntries().size());
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithStreaming() {
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withStreaming()
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should log onMessage and onHalfClose", span.logEntries().size(), 2);
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithCustomOperationName() {
    final String PREFIX = "testing-";
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withOperationName(new OperationNameConstructor() {
          @Override
          public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
            return PREFIX + method.getFullMethodName();
          }
        })
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(),
        PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithTracedAttributes() {
    TracedClient client = new TracedClient(grpcServer.getChannel());

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withTracedAttributes(ServerTracingInterceptor.ServerRequestAttribute.values())
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base server tags")
        .containsAllEntriesOf(BASE_SERVER_TAGS);
    Assertions.assertThat(span.tags()).as("span should have a tag for each traced attribute")
        .hasSize(ServerTracingInterceptor.ServerRequestAttribute.values().length + BASE_SERVER_TAGS
            .size());
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithServerSpanDecorator() {
    TracedClient client = new TracedClient(grpcServer.getChannel());
    ServerSpanDecorator serverSpanDecorator = new ServerSpanDecorator() {
      @Override
      public void interceptCall(Span span, ServerCall call, Metadata headers) {
        span.setTag("test_tag", "test_value");
        span.setTag("tag_from_call", call.getAuthority());
        span.setTag("tag_from_headers", headers.toString());

        span.log("A test log");
      }
    };

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withServerSpanDecorator(serverSpanDecorator)
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    Assertions.assertThat(span.logEntries()).as("span should have one log added in the decorator")
        .hasSize(1);
    Assertions.assertThat(span.tags()).as("span should have 3 tags added in the decorator")
        .hasSize(3 + BASE_SERVER_TAGS.size());
    Assertions.assertThat(span.tags()).as("span contains added tags")
        .contains(MapEntry.entry("test_tag", "test_value"))
        .containsKeys("tag_from_call", "tag_from_headers");
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithServerCloseDecorator() {
    TracedClient client = new TracedClient(grpcServer.getChannel());
    ServerCloseDecorator serverCloseDecorator = new ServerCloseDecorator() {
      @Override
      public void close(Span span, Status status, Metadata trailers) {
        span.setTag("grpc.statusCode", status.getCode().value());
      }
    };

    ServerTracingInterceptor tracingInterceptor = new ServerTracingInterceptor
        .Builder(serverTracer)
        .withServerCloseDecorator(serverCloseDecorator)
        .build();

    service.addGreeterServiceWithInterceptor(tracingInterceptor, grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    Assertions.assertThat(span.tags())
        .contains(MapEntry.entry("grpc.statusCode", Status.OK.getCode().value()));
  }

  @Test
  public void testTracedClientBasic() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientTwoInterceptors() {
    SecondClientInterceptor secondClientInterceptor = new SecondClientInterceptor(clientTracer);
    ClientTracingInterceptor clientTracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), secondClientInterceptor, clientTracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithVerbosity() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withVerbosity()
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    System.out.println(span.logEntries());
    assertEquals("span should have logs for start, onHeaders, onMessage, onClose, sendMessage", 5,
        span.logEntries().size());
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithStreaming() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withStreaming()
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have log for onMessage, halfClose, sendMessage", 3,
        span.logEntries().size());
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithOperationName() {
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
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(),
        PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithTracedAttributes() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withTracedAttributes(ClientTracingInterceptor.ClientRequestAttribute.values())
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .containsAllEntriesOf(BASE_CLIENT_TAGS);
    Assertions.assertThat(span.tags().keySet())
        .as("span should have tags for all client request attributes")
        .containsAll(CLIENT_ATTRIBUTE_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithClientSpanDecorator() {
    ClientSpanDecorator clientSpanDecorator = new ClientSpanDecorator() {
      @Override
      public void interceptCall(Span span, MethodDescriptor method, CallOptions callOptions) {
        span.setTag("test_tag", "test_value");
        span.setTag("tag_from_method", method.getFullMethodName());
        span.setTag("tag_from_call_options", callOptions.getCompressor());

        span.log("A test log");
      }
    };

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withClientSpanDecorator(clientSpanDecorator)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have one log from the decorator",
        span.logEntries().size(), 1);
    Assertions.assertThat(span.tags()).as("span should have 3 tags from the decorator")
        .hasSize(3 + BASE_CLIENT_TAGS.size());
    Assertions.assertThat(span.tags()).as("span contains added tags")
        .contains(MapEntry.entry("test_tag", "test_value"))
        .containsKeys("tag_from_method", "tag_from_call_options");
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithClientCloseDecorator() {
    ClientCloseDecorator clientCloseDecorator = new ClientCloseDecorator() {
      @Override
      public void close(Span span, Status status, Metadata trailers) {
        span.setTag("grpc.statusCode", status.getCode().value());
      }
    };

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withClientCloseDecorator(clientCloseDecorator)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    service.addGreeterService(grpcServer.getServiceRegistry());

    assertTrue("call should complete", client.greet("world"));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    Assertions.assertThat(span.tags())
        .contains(MapEntry.entry("grpc.statusCode", Status.OK.getCode().value()));
  }

  @Test
  public void testTracedClientAndServer() {
    // register server tracer to verify active span on server side
    GlobalTracer.register(serverTracer);

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    ServerTracingInterceptor serverTracingInterceptor = new ServerTracingInterceptor(serverTracer);

    service.addGreeterServiceWithInterceptor(serverTracingInterceptor,
        grpcServer.getServiceRegistry());

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
