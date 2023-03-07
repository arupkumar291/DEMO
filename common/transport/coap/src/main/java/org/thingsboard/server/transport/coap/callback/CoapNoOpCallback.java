/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.coap.callback;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.thingsboard.server.common.transport.TransportServiceCallback;

public class CoapNoOpCallback implements TransportServiceCallback<Void> {
    private final CoapExchange exchange;

    public CoapNoOpCallback(CoapExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void onSuccess(Void msg) {
    }

    @Override
    public void onError(Throwable e) {
        exchange.respond(CoAP.ResponseCode.INTERNAL_SERVER_ERROR);
    }
}
