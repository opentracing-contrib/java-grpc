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
