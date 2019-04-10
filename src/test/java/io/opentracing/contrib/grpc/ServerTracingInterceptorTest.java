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
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.testing.GrpcServerRule;
import io.opentracing.Span;
import io.opentracing.log.Fields;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracerTestUtil;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class ServerTracingInterceptorTest {

  private static final Map<String, Object> BASE_TAGS = ImmutableMap.<String, Object>builder()
      .put(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
      .put(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name())
      .build();

  private static final Map<String, Object> BASE_SERVER_TAGS = ImmutableMap.<String, Object>builder()
      .putAll(BASE_TAGS)
      .put(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
      .build();

  private static final Set<String> SERVER_ATTRIBUTE_TAGS = ImmutableSet.of(
      GrpcTags.GRPC_METHOD_TYPE.getKey(),
      GrpcTags.GRPC_METHOD_NAME.getKey(),
      GrpcTags.GRPC_CALL_ATTRIBUTES.getKey(),
      GrpcTags.GRPC_HEADERS.getKey(),
      GrpcTags.PEER_ADDRESS.getKey());

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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

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

    service.addGreeterServiceWithInterceptors(
        grpcServer.getServiceRegistry(), secondServerInterceptor, tracingInterceptor);

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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    List<String> events = new ArrayList<>(span.logEntries().size());
    for (MockSpan.LogEntry logEntry : span.logEntries()) {
      events.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(events)
        .as("span should contain verbose log fields")
        .contains(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            GrpcFields.SERVER_CALL_SEND_HEADERS,
            GrpcFields.SERVER_CALL_SEND_MESSAGE,
            GrpcFields.SERVER_CALL_CLOSE,
            GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE);
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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have default name", span.operationName(),
        "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    List<String> events = new ArrayList<>(span.logEntries().size());
    for (MockSpan.LogEntry logEntry : span.logEntries()) {
      events.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(events)
        .as("span should contain streaming log fields")
        .contains(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            GrpcFields.SERVER_CALL_SEND_MESSAGE);
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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

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
    Assertions.assertThat(span.tags().keySet())
        .as("span should have tags for all server request attributes")
        .containsAll(SERVER_ATTRIBUTE_TAGS);
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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

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

    service.addGreeterServiceWithInterceptors(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertTrue("call should complete", client.greet("world"));

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(), 1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    Assertions.assertThat(span.tags())
        .contains(MapEntry.entry("grpc.statusCode", Status.OK.getCode().value()));
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
