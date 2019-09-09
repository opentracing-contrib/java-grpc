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

import io.opentracing.Span;

/** An interface that defines how to get the current active span. */
public interface ActiveSpanSource {

  /** ActiveSpanSource implementation that always returns null as the active span. */
  ActiveSpanSource NONE =
      new ActiveSpanSource() {
        @Override
        public Span getActiveSpan() {
          return null;
        }
      };

  /**
   * ActiveSpanSource implementation that returns the current span stored in the GRPC context under
   * {@link OpenTracingContextKey}.
   */
  ActiveSpanSource GRPC_CONTEXT =
      new ActiveSpanSource() {
        @Override
        public Span getActiveSpan() {
          return OpenTracingContextKey.activeSpan();
        }
      };

  /**
   * Retieves the active {@link Span}.
   *
   * @return the active span
   */
  Span getActiveSpan();
}
