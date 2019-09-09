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
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

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
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracerTestUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TracingServerInterceptorTest {

  private static final String PREFIX = "testing-";

  private static final Map<String, Object> BASE_TAGS =
      ImmutableMap.<String, Object>builder()
          .put(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
          .put(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name())
          .build();

  private static final Map<String, Object> BASE_SERVER_TAGS =
      ImmutableMap.<String, Object>builder()
          .putAll(BASE_TAGS)
          .put(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
          .build();

  private static final Set<String> SERVER_ATTRIBUTE_TAGS =
      ImmutableSet.of(
          GrpcTags.GRPC_METHOD_TYPE.getKey(),
          GrpcTags.GRPC_METHOD_NAME.getKey(),
          GrpcTags.GRPC_CALL_ATTRIBUTES.getKey(),
          GrpcTags.GRPC_HEADERS.getKey(),
          GrpcTags.PEER_ADDRESS.getKey());

  private final MockTracer serverTracer = new MockTracer();

  @Rule public GrpcServerRule grpcServer = new GrpcServerRule();

  private TracedClient client;

  @Before
  public void setUp() {
    GlobalTracerTestUtil.resetGlobalTracer();
    serverTracer.reset();
    client = new TracedClient(grpcServer.getChannel());
  }

  @Test
  public void testTracedServerBasic() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder().withTracer(serverTracer).build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have default name", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertTrue("span should have no logs", span.logEntries().isEmpty());
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerTwoInterceptors() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder().withTracer(serverTracer).build();
    SecondServerInterceptor secondServerInterceptor = new SecondServerInterceptor(serverTracer);
    TracedService.addGeeterService(
        grpcServer.getServiceRegistry(), secondServerInterceptor, tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have default name", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertTrue("span should have no logs", span.logEntries().isEmpty());
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithVerbosity() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder().withTracer(serverTracer).withVerbosity().build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have default name", span.operationName(), "helloworld.Greeter/SayHello");
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
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithStreaming() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder().withTracer(serverTracer).withStreaming().build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have default name", span.operationName(), "helloworld.Greeter/SayHello");
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
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithCustomOperationName() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withOperationName(
                new OperationNameConstructor() {
                  @Override
                  public <ReqT, RespT> String constructOperationName(
                      MethodDescriptor<ReqT, RespT> method) {
                    return PREFIX + method.getFullMethodName();
                  }
                })
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have prefix", span.operationName(), PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .isEqualTo(BASE_SERVER_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithTracedAttributes() {
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withTracedAttributes(TracingServerInterceptor.ServerRequestAttribute.values())
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags())
        .as("span should have base server tags")
        .containsAllEntriesOf(BASE_SERVER_TAGS);
    Assertions.assertThat(span.tags().keySet())
        .as("span should have tags for all server request attributes")
        .containsAll(SERVER_ATTRIBUTE_TAGS);
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithServerSpanDecorator() {
    ServerSpanDecorator spanTagger =
        new ServerSpanDecorator() {
          @Override
          public void interceptCall(Span span, ServerCall call, Metadata headers) {
            span.setTag("test_tag", "test_value");
            span.setTag("tag_from_call", call.getAuthority());
            span.setTag("tag_from_headers", headers.toString());
          }
        };
    ServerSpanDecorator spanLogger =
        new ServerSpanDecorator() {
          @Override
          public void interceptCall(Span span, ServerCall call, Metadata headers) {
            span.log("A span log");
          }
        };

    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withServerSpanDecorator(spanTagger)
            .withServerSpanDecorator(spanLogger)
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals(
        "span should have one log added in the decorator",
        span.logEntries().get(0).fields().get("event"),
        "A span log");
    Assertions.assertThat(span.tags())
        .as("span should have 3 tags added in the decorator")
        .hasSize(3 + BASE_SERVER_TAGS.size());
    Assertions.assertThat(span.tags())
        .as("span contains added tags")
        .containsEntry("test_tag", "test_value")
        .containsKeys("tag_from_call", "tag_from_headers");
    assertFalse("span should have no baggage", span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedServerWithServerCloseDecorator() {
    ServerCloseDecorator closeTagger =
        new ServerCloseDecorator() {
          @Override
          public void close(Span span, Status status, Metadata trailers) {
            span.setTag("some_tag", "some_value");
          }
        };
    ServerCloseDecorator closeLogger =
        new ServerCloseDecorator() {
          @Override
          public void close(Span span, Status status, Metadata trailers) {
            span.log("A close log");
          }
        };
    TracingServerInterceptor tracingInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withServerCloseDecorator(closeTagger)
            .withServerCloseDecorator(closeLogger)
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));
    assertEquals(
        "one span should have been created and finished for one client request",
        serverTracer.finishedSpans().size(),
        1);

    MockSpan span = serverTracer.finishedSpans().get(0);
    assertEquals(
        "span should have one log from the decorator",
        span.logEntries().get(0).fields().get("event"),
        "A close log");
    Assertions.assertThat(span.tags()).containsEntry("some_tag", "some_value");
  }

  @Test
  public void testGetSpanFromHeaders() {
    long traceID = 1;
    long spanID = 2;
    MockTracer spyTracer = spy(serverTracer);
    doReturn(new MockSpan.MockContext(traceID, spanID, Collections.<String, String>emptyMap()))
        .when(spyTracer)
        .extract(eq(Format.Builtin.HTTP_HEADERS), any(TextMapAdapter.class));

    Span span =
        TracingServerInterceptor.newBuilder()
            .withTracer(spyTracer)
            .build()
            .getSpanFromHeaders(Collections.<String, String>emptyMap(), "operationName");
    assertNotNull("span is not null", span);
    MockSpan mockSpan = (MockSpan) span;
    assertEquals(
        "span parentID is set to extracted span context spanID", spanID, mockSpan.parentId());
    List<MockSpan.LogEntry> logEntries = mockSpan.logEntries();
    assertTrue("span contains no log entries", logEntries.isEmpty());
  }

  @Test
  public void testGetSpanFromHeadersError() {
    MockTracer spyTracer = spy(serverTracer);
    doThrow(IllegalArgumentException.class)
        .when(spyTracer)
        .extract(eq(Format.Builtin.HTTP_HEADERS), any(TextMapAdapter.class));

    Span span =
        TracingServerInterceptor.newBuilder()
            .withTracer(spyTracer)
            .build()
            .getSpanFromHeaders(Collections.<String, String>emptyMap(), "operationName");
    assertNotNull("span is not null", span);
    List<MockSpan.LogEntry> logEntries = ((MockSpan) span).logEntries();
    assertEquals("span contains 1 log entry", 1, logEntries.size());
    assertEquals(
        "span log contains error field",
        GrpcFields.ERROR,
        logEntries.get(0).fields().get(Fields.EVENT));
    assertThat(
        "span log contains error.object field",
        logEntries.get(0).fields().get(Fields.ERROR_OBJECT),
        instanceOf(RuntimeException.class));
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
