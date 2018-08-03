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

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.opentracing.Span;

/**
 * An interface for adding custom span tags to the spans created by
 * {@link ServerTracingInterceptor}
 */
public interface ServerSpanDecorator {

  /**
   * The method of the implementation is executed inside
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   * @param span The span created by {@link ServerTracingInterceptor}
   * @param call The {@link ServerCall} parameter of
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   * @param headers The {@link Metadata} parameter of
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   */
  void interceptCall(Span span, ServerCall call, Metadata headers);
}
