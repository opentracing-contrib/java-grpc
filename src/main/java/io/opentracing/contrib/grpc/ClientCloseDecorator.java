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

import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.opentracing.Span;

public interface ClientCloseDecorator {
  /**
   * The method of the implementation is executed inside
   * {@link ForwardingClientCallListener#onClose(Status, Metadata)}
   *
   * @param span The span created by {@link ClientTracingInterceptor}
   * @param status The status passed to {@link ForwardingClientCallListener#onClose(Status, Metadata)}.
   * @param trailers The trailing headers passed to {@link ForwardingClientCallListener#onClose(Status, Metadata)}
   */
  void close(Span span, Status status, Metadata trailers);
}
