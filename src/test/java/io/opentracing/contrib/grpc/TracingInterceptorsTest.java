/*
 * Copyright 2017-2020 The OpenTracing Authors
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
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.testing.GrpcServerRule;
import io.opentracing.References;
import io.opentracing.log.Fields;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.GlobalTracerTestUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TracingInterceptorsTest {

  private final MockTracer clientTracer = new MockTracer();
  private final MockTracer serverTracer = new MockTracer();

  @Rule
  public GrpcServerRule grpcServer = new GrpcServerRule();

  @Before
  public void setUp() {
    GlobalTracerTestUtil.resetGlobalTracer();
    clientTracer.reset();
    serverTracer.reset();
    // register server tracer to verify active span on server side
    GlobalTracer.registerIfAbsent(serverTracer);
  }

  @Test
  public void testTracedClientAndServerSuccess() {
    TracingClientInterceptor clientInterceptor =
        TracingClientInterceptor.newBuilder()
            .withTracer(clientTracer)
            .withVerbosity()
            .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), clientInterceptor);

    TracingServerInterceptor serverInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withVerbosity()
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), serverInterceptor);

    assertEquals("call should complete successfully", "Hello world", client.greet().getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));

    assertEquals(
        "a client span should have been created for the request",
        1,
        clientTracer.finishedSpans().size());
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    List<String> clientEvents = new ArrayList<>(clientSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : clientSpan.logEntries()) {
      clientEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(clientEvents)
        .as("client span should contain verbose log fields")
        .containsExactly(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_HEADERS,
            GrpcFields.CLIENT_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE);
    Assertions.assertThat(clientSpan.tags())
        .as("client span grpc.status tag should equal OK")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name());

    assertEquals(
        "a server span should have been created for the request",
        1,
        serverTracer.finishedSpans().size());
    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    List<String> serverEvents = new ArrayList<>(serverSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : serverSpan.logEntries()) {
      serverEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(serverEvents)
        .as("server span should contain verbose log fields")
        .containsExactly(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            GrpcFields.SERVER_CALL_SEND_HEADERS,
            GrpcFields.SERVER_CALL_SEND_MESSAGE,
            GrpcFields.SERVER_CALL_CLOSE,
            GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE);
    Assertions.assertThat(serverSpan.tags())
        .as("server span grpc.status tag should equal OK")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name());

    assertEquals(
        "client and server spans should be part of the same trace",
        clientSpan.context().traceId(),
        serverSpan.context().traceId());
    assertEquals(
        "client span should be parent of server span",
        clientSpan.context().spanId(),
        serverSpan.parentId());
    assertEquals(
        "server span should be child of client span",
        References.CHILD_OF,
        serverSpan.references().get(0).getReferenceType());

    assertTrue(
        "client span should be longer than the server span",
        (clientSpan.finishMicros() - clientSpan.startMicros())
            >= (serverSpan.finishMicros() - serverSpan.startMicros()));
  }

  @Test
  public void testTracedClientSendError() {
    TracingClientInterceptor clientInterceptor =
        TracingClientInterceptor.newBuilder()
            .withTracer(clientTracer)
            .withVerbosity()
            .build();
    ClientInterceptor clientSendError =
        new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
              @Override
              public void sendMessage(ReqT message) {
                throw new RuntimeException("client send error");
              }
            };
          }
        };
    TracedClient client =
        new TracedClient(grpcServer.getChannel(), clientSendError, clientInterceptor);

    TracingServerInterceptor serverInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withVerbosity()
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), serverInterceptor);

    assertNull("call should return null", client.greet());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));

    assertEquals(
        "a client span should have been created for the request",
        1,
        clientTracer.finishedSpans().size());
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    List<String> clientEvents = new ArrayList<>(clientSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : clientSpan.logEntries()) {
      clientEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(clientEvents)
        .as("client span should contain verbose log fields")
        .containsExactly(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_CANCEL,
            // TODO: onClose is not called due to bug: https://github.com/grpc/grpc-java/issues/5576
            // GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE,
            GrpcFields.ERROR);
    Assertions.assertThat(clientSpan.tags())
        .as("client span grpc.status tag should equal UNKNOWN")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.UNKNOWN.name());

    assertEquals(
        "a server span should have been created for the request",
        1,
        serverTracer.finishedSpans().size());
    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    List<String> serverEvents = new ArrayList<>(serverSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : serverSpan.logEntries()) {
      serverEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(serverEvents)
        .as("server span should contain verbose log fields")
        .containsExactly(GrpcFields.SERVER_CALL_LISTENER_ON_CANCEL);
    Assertions.assertThat(serverSpan.tags())
        .as("server span grpc.status tag should equal CANCELLED")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.CANCELLED.name());
  }

  @Test
  public void testTracedClientReceiveError() {
    TracingClientInterceptor clientInterceptor =
        TracingClientInterceptor.newBuilder()
            .withTracer(clientTracer)
            .withVerbosity()
            .build();
    ClientInterceptor clientReceiveError =
        new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
              @Override
              public void start(Listener<RespT> responseListener, Metadata headers) {
                delegate()
                    .start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                            responseListener) {
                          @Override
                          public void onMessage(RespT message) {
                            throw new RuntimeException("client receive error");
                          }
                        },
                        headers);
              }
            };
          }
        };
    TracedClient client =
        new TracedClient(grpcServer.getChannel(), clientReceiveError, clientInterceptor);

    TracingServerInterceptor serverInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withVerbosity()
            .build();
    TracedService.addGeeterService(grpcServer.getServiceRegistry(), serverInterceptor);

    assertNull("call should return null", client.greet());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));

    assertEquals(
        "a client span should have been created for the request",
        1,
        clientTracer.finishedSpans().size());
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    List<String> clientEvents = new ArrayList<>(clientSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : clientSpan.logEntries()) {
      clientEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(clientEvents)
        .as("client span should contain verbose log fields")
        .containsExactly(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_HEADERS,
            GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE,
            GrpcFields.ERROR);
    Assertions.assertThat(clientSpan.tags())
        .as("client span grpc.status tag should equal CANCELLED")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.CANCELLED.name());

    assertEquals(
        "a server span should have been created for the request",
        1,
        serverTracer.finishedSpans().size());
    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    List<String> serverEvents = new ArrayList<>(serverSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : serverSpan.logEntries()) {
      serverEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(serverEvents)
        .as("server span should contain verbose log fields")
        .containsExactly(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            GrpcFields.SERVER_CALL_SEND_HEADERS,
            GrpcFields.SERVER_CALL_SEND_MESSAGE,
            GrpcFields.SERVER_CALL_CLOSE,
            GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE);
    Assertions.assertThat(serverSpan.tags())
        .as("server span grpc.status tag should equal OK")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name());
  }

  @Test
  public void testTracedServerReceiveError() {
    TracingClientInterceptor clientInterceptor =
        TracingClientInterceptor.newBuilder()
            .withTracer(clientTracer)
            .withVerbosity()
            .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), clientInterceptor);

    TracingServerInterceptor serverInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withVerbosity()
            .build();
    ServerInterceptor serverReceiveError =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                next.startCall(call, headers)) {
              @Override
              public void onMessage(ReqT message) {
                throw new RuntimeException("server receive error");
              }
            };
          }
        };
    TracedService.addGeeterService(
        grpcServer.getServiceRegistry(), serverReceiveError, serverInterceptor);

    assertNull("call should return null", client.greet());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));

    assertEquals(
        "a client span should have been created for the request",
        1,
        clientTracer.finishedSpans().size());
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    List<String> clientEvents = new ArrayList<>(clientSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : clientSpan.logEntries()) {
      clientEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(clientEvents)
        .as("client span should contain verbose log fields")
        .containsExactly(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE,
            GrpcFields.ERROR);
    Assertions.assertThat(clientSpan.tags())
        .as("client span grpc.status tag should equal UNKNOWN")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.UNKNOWN.name());

    assertEquals(
        "a server span should have been created for the request",
        1,
        serverTracer.finishedSpans().size());
    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    List<String> serverEvents = new ArrayList<>(serverSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : serverSpan.logEntries()) {
      serverEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    // The RuntimeException schedules a task to execute the closed/onComplete call path.
    // There is a race between when the closed/onComplete call path is executed and when
    // the halfClose/close call path is executed.  Commenting out the lines below since
    // there is no guarantee that halfClose/close will run and write the error status.
    Assertions.assertThat(serverEvents)
        .as("server span should contain verbose log fields")
        .contains(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            // GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            // GrpcFields.SERVER_CALL_CLOSE,
            // GrpcFields.ERROR,
            GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE);
    Assertions.assertThat(serverSpan.tags())
        .as("server span grpc.status tag should equal INTERNAL")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME);
    // .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.INTERNAL.name());
  }

  @Test
  public void testTracedServerSendError() {
    TracingClientInterceptor clientInterceptor =
        TracingClientInterceptor.newBuilder()
            .withTracer(clientTracer)
            .withVerbosity()
            .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), clientInterceptor);

    TracingServerInterceptor serverInterceptor =
        TracingServerInterceptor.newBuilder()
            .withTracer(serverTracer)
            .withVerbosity()
            .build();
    ServerInterceptor serverSendError =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void sendMessage(RespT message) {
                    throw new RuntimeException("server send error");
                  }
                },
                headers);
          }
        };
    TracedService.addGeeterService(
        grpcServer.getServiceRegistry(), serverSendError, serverInterceptor);

    assertNull("call should return null", client.greet());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(serverTracer), equalTo(1));

    assertEquals(
        "a client span should have been created for the request",
        1,
        clientTracer.finishedSpans().size());
    MockSpan clientSpan = clientTracer.finishedSpans().get(0);
    List<String> clientEvents = new ArrayList<>(clientSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : clientSpan.logEntries()) {
      clientEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(clientEvents)
        .as("client span should contain verbose log fields")
        .containsExactly(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_HEADERS,
            GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE,
            GrpcFields.ERROR);
    Assertions.assertThat(clientSpan.tags())
        .as("client span grpc.status tag should equal UNKNOWN")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .containsEntry(GrpcTags.GRPC_STATUS.getKey(), Status.Code.UNKNOWN.name());

    assertEquals(
        "a server span should have been created for the request",
        1,
        serverTracer.finishedSpans().size());
    MockSpan serverSpan = serverTracer.finishedSpans().get(0);
    List<String> serverEvents = new ArrayList<>(serverSpan.logEntries().size());
    for (MockSpan.LogEntry logEntry : serverSpan.logEntries()) {
      serverEvents.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(serverEvents)
        .as("server span should contain verbose log fields")
        .containsExactly(
            GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE,
            GrpcFields.SERVER_CALL_SEND_HEADERS,
            GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE);
    Assertions.assertThat(serverSpan.tags())
        .as("server span grpc.status tag should be missing")
        .containsEntry(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        .containsEntry(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .doesNotContainKey(GrpcTags.GRPC_STATUS.getKey());
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
