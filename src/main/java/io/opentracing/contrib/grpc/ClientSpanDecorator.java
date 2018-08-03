package io.opentracing.contrib.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.opentracing.Span;

/**
 * An interface for adding custom span tags to the spans created by
 * {@link ClientTracingInterceptor}
 */
public interface ClientSpanDecorator {
  /**
   * The method of the implementation is executed inside
   * {@link ClientTracingInterceptor#interceptCall(MethodDescriptor, CallOptions, Channel)}
   * @param span The span created by {@link ClientTracingInterceptor}
   * @param callOptions The {@link ServerCall} parameter of
   * {@link ClientTracingInterceptor#interceptCall(MethodDescriptor, CallOptions, Channel)}
   * @param method The {@link MethodDescriptor} parameter of
   * {@link ClientTracingInterceptor#interceptCall(MethodDescriptor, CallOptions, Channel)}
   */
  void interceptCall(Span span, MethodDescriptor method, CallOptions callOptions);
}
