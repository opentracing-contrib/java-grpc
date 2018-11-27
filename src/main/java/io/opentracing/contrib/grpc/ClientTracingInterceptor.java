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

import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * An interceptor that applies tracing via OpenTracing to all client requests.
 */
public class ClientTracingInterceptor implements ClientInterceptor {

  private final Tracer tracer;
  private final OperationNameConstructor operationNameConstructor;
  private final boolean streaming;
  private final boolean verbose;
  private final Set<ClientRequestAttribute> tracedAttributes;
  private final ActiveSpanSource activeSpanSource;
  private final ActiveSpanContextSource activeSpanContextSource;
  private final ClientSpanDecorator clientSpanDecorator;
  private final ClientCloseDecorator clientCloseDecorator;

  /**
   * Instantiate interceptor using GlobalTracer to get tracer
   */
  public ClientTracingInterceptor() {
    this(GlobalTracer.get());
  }

  /**
   * @param tracer to use to trace requests
   */
  public ClientTracingInterceptor(Tracer tracer) {
    this.tracer = tracer;
    this.operationNameConstructor = OperationNameConstructor.DEFAULT;
    this.streaming = false;
    this.verbose = false;
    this.tracedAttributes = new HashSet<>();
    this.activeSpanSource = ActiveSpanSource.GRPC_CONTEXT;
    this.activeSpanContextSource = null;
    this.clientSpanDecorator = null;
    this.clientCloseDecorator = null;
  }

  private ClientTracingInterceptor(Tracer tracer, OperationNameConstructor operationNameConstructor,
      boolean streaming,
      boolean verbose, Set<ClientRequestAttribute> tracedAttributes,
      ActiveSpanSource activeSpanSource, ActiveSpanContextSource activeSpanContextSource,
      ClientSpanDecorator clientSpanDecorator,
      ClientCloseDecorator clientCloseDecorator) {
    this.tracer = tracer;
    this.operationNameConstructor = operationNameConstructor;
    this.streaming = streaming;
    this.verbose = verbose;
    this.tracedAttributes = tracedAttributes;
    this.activeSpanSource = activeSpanSource;
    this.activeSpanContextSource = activeSpanContextSource;
    this.clientSpanDecorator = clientSpanDecorator;
    this.clientCloseDecorator = clientCloseDecorator;
  }

  /**
   * Use this interceptor to trace all requests made by this client channel.
   *
   * @param channel to be traced
   * @return intercepted channel
   */
  public Channel intercept(Channel channel) {
    return ClientInterceptors.intercept(channel, this);
  }

  private SpanContext getActiveSpanContext() {
    if (activeSpanSource != null) {
      Span activeSpan = activeSpanSource.getActiveSpan();
      if (activeSpan != null) {
        return activeSpan.context();
      }
    }
    if (activeSpanContextSource != null) {
      return activeSpanContextSource.getActiveSpanContext();
    }
    return null;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next
  ) {
    final String operationName = operationNameConstructor.constructOperationName(method);

    SpanContext activeSpanContext = getActiveSpanContext();
    final Span span = createSpanFromParent(activeSpanContext, operationName);

    if (clientSpanDecorator != null) {
      clientSpanDecorator.interceptCall(span, method, callOptions);
    }

    for (ClientRequestAttribute attr : this.tracedAttributes) {
      switch (attr) {
        case ALL_CALL_OPTIONS:
          span.setTag(attr.key, callOptions.toString());
          break;
        case AUTHORITY:
          span.setTag(attr.key, String.valueOf(callOptions.getAuthority()));
          break;
        case COMPRESSOR:
          span.setTag(attr.key, String.valueOf(callOptions.getCompressor()));
          break;
        case DEADLINE:
          if (callOptions.getDeadline() == null) {
            span.setTag(attr.key, "null");
          } else {
            span.setTag(attr.key,
                callOptions.getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
          }
          break;
        case METHOD_NAME:
          span.setTag(attr.key, method.getFullMethodName());
          break;
        case METHOD_TYPE:
          span.setTag(attr.key, String.valueOf(method.getType()));
          break;
        case HEADERS:
          break;
      }
    }

    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
        next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, final Metadata headers) {
        if (verbose) {
          span.log("Started call");
        }
        if (tracedAttributes.contains(ClientRequestAttribute.HEADERS)) {
          span.setTag(ClientRequestAttribute.HEADERS.key, headers.toString());
        }

        tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new TextMap() {
          @Override
          public void put(String key, String value) {
            Metadata.Key<String> headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            headers.put(headerKey, value);
          }

          @Override
          public Iterator<Entry<String, String>> iterator() {
            throw new UnsupportedOperationException(
                "TextMapInjectAdapter should only be used with Tracer.inject()");
          }
        });

        Listener<RespT> tracingResponseListener = new ForwardingClientCallListener
            .SimpleForwardingClientCallListener<RespT>(responseListener) {

          @Override
          public void onHeaders(Metadata headers) {
            if (verbose) {
              span.log(ImmutableMap.of("Response headers received", headers.toString()));
            }
            delegate().onHeaders(headers);
          }

          @Override
          public void onMessage(RespT message) {
            if (streaming || verbose) {
              span.log("Response received");
            }
            delegate().onMessage(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            if (verbose) {
              if (status.getCode().value() == 0) {
                span.log("Call closed");
              } else {
                if (status.getDescription() == null) {
                  span.log(ImmutableMap.of("Call failed", "null"));
                } else {
                  span.log(ImmutableMap.of("Call failed", status.getDescription()));
                }
              }
            }
            GrpcTags.setStatusTags(span, status);
            if (clientCloseDecorator != null) {
              clientCloseDecorator.close(span, status, trailers);
            }
            span.finish();
            delegate().onClose(status, trailers);
          }
        };
        delegate().start(tracingResponseListener, headers);
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        String errorMessage;
        if (message == null) {
          errorMessage = "Error";
        } else {
          errorMessage = message;
        }
        if (cause == null) {
          span.log(errorMessage);
        } else {
          span.log(ImmutableMap.of(errorMessage, cause.getMessage()));
        }
        delegate().cancel(message, cause);
      }

      @Override
      public void halfClose() {
        if (streaming) {
          span.log("Finished sending messages");
        }
        delegate().halfClose();
      }

      @Override
      public void sendMessage(ReqT message) {
        if (streaming || verbose) {
          span.log("Message sent");
        }
        delegate().sendMessage(message);
      }
    };
  }

  private Span createSpanFromParent(SpanContext parentSpanContext, String operationName) {
    final Tracer.SpanBuilder spanBuilder;
    if (parentSpanContext == null) {
      spanBuilder = tracer.buildSpan(operationName);
    } else {
      spanBuilder = tracer.buildSpan(operationName).asChildOf(parentSpanContext);
    }
    return spanBuilder.withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
      .withTag(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_VALUE)
      .start();
  }

  /**
   * Builds the configuration of a ClientTracingInterceptor.
   */
  public static class Builder {

    private final Tracer tracer;
    private OperationNameConstructor operationNameConstructor;
    private boolean streaming;
    private boolean verbose;
    private Set<ClientRequestAttribute> tracedAttributes;
    private ActiveSpanSource activeSpanSource;
    private ActiveSpanContextSource activeSpanContextSource;
    private ClientSpanDecorator clientSpanDecorator;
    private ClientCloseDecorator clientCloseDecorator;

    /**
     * Creates a Builder using GlobalTracer to get tracer
     */
    public Builder() {
      this(GlobalTracer.get());
    }

    /**
     * @param tracer to use for this interceptor
     * Creates a Builder with default configuration
     */
    public Builder(Tracer tracer) {
      this.tracer = tracer;
      this.operationNameConstructor = OperationNameConstructor.DEFAULT;
      this.streaming = false;
      this.verbose = false;
      this.tracedAttributes = new HashSet<>();
      this.activeSpanSource = ActiveSpanSource.GRPC_CONTEXT;
      this.clientSpanDecorator = null;
    }

    /**
     * @param operationNameConstructor to name all spans created by this interceptor
     * @return this Builder with configured operation name
     */
    public Builder withOperationName(OperationNameConstructor operationNameConstructor) {
      this.operationNameConstructor = operationNameConstructor;
      return this;
    }

    /**
     * Logs streaming events to client spans.
     *
     * @return this Builder configured to log streaming events
     */
    public Builder withStreaming() {
      this.streaming = true;
      return this;
    }

    /**
     * @param tracedAttributes to set as tags on client spans
     * created by this interceptor
     * @return this Builder configured to trace attributes
     */
    public Builder withTracedAttributes(ClientRequestAttribute... tracedAttributes) {
      this.tracedAttributes = new HashSet<>(Arrays.asList(tracedAttributes));
      return this;
    }

    /**
     * Logs all request life-cycle events to client spans.
     *
     * @return this Builder configured to be verbose
     */
    public Builder withVerbosity() {
      this.verbose = true;
      return this;
    }

    /**
     * @param activeSpanSource that provides a method of getting the
     * active span before the client call
     * @return this Builder configured to start client span as children
     * of the span returned by activeSpanSource.getActiveSpan()
     */
    public Builder withActiveSpanSource(ActiveSpanSource activeSpanSource) {
      this.activeSpanSource = activeSpanSource;
      return this;
    }

    /**
     * @param activeSpanContextSource that provides a method of getting the
     * active span context before the client call
     * @return this Builder configured to start client span as children
     * of the span context returned by activeSpanContextSource.getActiveSpanContext()
     */
    public Builder withActiveSpanContextSource(ActiveSpanContextSource activeSpanContextSource) {
      this.activeSpanContextSource = activeSpanContextSource;
      return this;
    }

    /**
     * Decorates the client span with custom data
     *
     * @param clientSpanDecorator used to decorate the client span
     * @return this builder configured to decorate the client span
     */
    public Builder withClientSpanDecorator(ClientSpanDecorator clientSpanDecorator) {
      this.clientSpanDecorator = clientSpanDecorator;
      return this;
    }

    /**
     * Decorates the client span with custom data when the call is closed
     *
     * @param clientCloseDecorator used to decorate the client span when the call is closed
     * @return this builder configured to decorate the client span when the call is closed
     */
    public Builder withClientCloseDecorator(ClientCloseDecorator clientCloseDecorator) {
      this.clientCloseDecorator = clientCloseDecorator;
      return this;
    }

    /**
     * @return a ClientTracingInterceptor with this Builder's configuration
     */
    public ClientTracingInterceptor build() {
      return new ClientTracingInterceptor(this.tracer, this.operationNameConstructor,
          this.streaming, this.verbose, this.tracedAttributes, this.activeSpanSource,
          this.activeSpanContextSource, this.clientSpanDecorator,
          this.clientCloseDecorator);
    }
  }

  public enum ClientRequestAttribute {
    METHOD_TYPE("grpc.method_type"),
    METHOD_NAME("grpc.method_name"),
    DEADLINE("grpc.deadline_millis"),
    COMPRESSOR("grpc.compressor"),
    AUTHORITY("grpc.authority"),
    ALL_CALL_OPTIONS("grpc.call_options"),
    HEADERS("grpc.headers");

    /** The Span tag key for this attribute */
    final String key;

    ClientRequestAttribute(String key) {
      this.key = key;
    }
  }
}
