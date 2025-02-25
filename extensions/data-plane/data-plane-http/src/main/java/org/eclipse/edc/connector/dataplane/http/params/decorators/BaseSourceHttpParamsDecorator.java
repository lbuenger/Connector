/*
 *  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.connector.dataplane.http.params.decorators;

import org.eclipse.edc.connector.dataplane.http.spi.HttpDataAddress;
import org.eclipse.edc.connector.dataplane.http.spi.HttpParamsDecorator;
import org.eclipse.edc.connector.dataplane.http.spi.HttpRequestParams;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.edc.util.string.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.eclipse.edc.connector.dataplane.spi.schema.DataFlowRequestSchema.BODY;
import static org.eclipse.edc.connector.dataplane.spi.schema.DataFlowRequestSchema.MEDIA_TYPE;
import static org.eclipse.edc.connector.dataplane.spi.schema.DataFlowRequestSchema.METHOD;
import static org.eclipse.edc.connector.dataplane.spi.schema.DataFlowRequestSchema.PATH;
import static org.eclipse.edc.connector.dataplane.spi.schema.DataFlowRequestSchema.QUERY_PARAMS;

public class BaseSourceHttpParamsDecorator implements HttpParamsDecorator {

    private static final String DEFAULT_METHOD = "GET";

    @Override
    public HttpRequestParams.Builder decorate(DataFlowStartMessage request, HttpDataAddress address, HttpRequestParams.Builder params) {
        params.method(extractMethod(address, request));
        params.path(extractPath(address, request));
        params.queryParams(extractQueryParams(address, request));
        Optional.ofNullable(extractContentType(address, request))
                .ifPresent(ct -> {
                    params.contentType(ct);
                    params.body(extractBody(address, request));
                });
        params.nonChunkedTransfer(false);
        return params;
    }

    private @NotNull String extractMethod(HttpDataAddress address, DataFlowStartMessage request) {
        if (Boolean.parseBoolean(address.getProxyMethod()) && "HttpProxy".equals(request.getDestinationDataAddress().getType())) {
            return Optional.ofNullable(request.getProperties().get(METHOD))
                    .orElseThrow(() -> new EdcException(format("DataFlowRequest %s: 'method' property is missing", request.getId())));
        }
        return Optional.ofNullable(address.getMethod()).orElse(DEFAULT_METHOD);
    }

    private @Nullable String extractPath(HttpDataAddress address, DataFlowStartMessage request) {
        return Boolean.parseBoolean(address.getProxyPath()) ? request.getProperties().get(PATH) : address.getPath();
    }

    private @Nullable String extractQueryParams(HttpDataAddress address, DataFlowStartMessage request) {
        var queryParams = Stream.of(address.getQueryParams(), getRequestQueryParams(address, request))
                .filter(s -> !StringUtils.isNullOrBlank(s))
                .collect(Collectors.joining("&"));
        return !queryParams.isEmpty() ? queryParams : null;
    }

    @Nullable
    private String extractContentType(HttpDataAddress address, DataFlowStartMessage request) {
        return Boolean.parseBoolean(address.getProxyBody()) ? request.getProperties().get(MEDIA_TYPE) : address.getContentType();
    }

    @Nullable
    private String extractBody(HttpDataAddress address, DataFlowStartMessage request) {
        return Boolean.parseBoolean(address.getProxyBody()) ? request.getProperties().get(BODY) : null;
    }

    @Nullable
    private String getRequestQueryParams(HttpDataAddress address, DataFlowStartMessage request) {
        return Boolean.parseBoolean(address.getProxyQueryParams()) ? request.getProperties().get(QUERY_PARAMS) : null;
    }
}
