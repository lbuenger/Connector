/*
 *  Copyright (c) 2022 Amadeus
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Amadeus - initial API and implementation
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - improvements
 *
 */

package org.eclipse.edc.connector.dataplane.api.controller;

import io.restassured.specification.RequestSpecification;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.connector.dataplane.spi.resolver.DataAddressResolver;
import org.eclipse.edc.connector.dataplane.util.sink.AsyncStreamingDataSink;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ApiTest
class DataPlanePublicApiControllerTest extends RestControllerTestBase {

    private final PipelineService pipelineService = mock();
    private final DataAddressResolver dataAddressResolver = mock();

    @Test
    void should_returnBadRequest_if_missingAuthorizationHeader() {
        baseRequest()
                .post("/any")
                .then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
                .body("errors[0]", is("Missing token"));
    }

    @Test
    void shouldNotReturn302_whenUrlWithoutTrailingSlash() {
        baseRequest()
                .post("")
                .then()
                .statusCode(not(302));
    }

    @Test
    void should_returnForbidden_if_tokenValidationFails() {
        var token = UUID.randomUUID().toString();
        when(dataAddressResolver.resolve(any())).thenReturn(Result.failure("token is not value"));

        baseRequest()
                .header(AUTHORIZATION, token)
                .post("/any")
                .then()
                .statusCode(Response.Status.FORBIDDEN.getStatusCode())
                .contentType(JSON)
                .body("errors.size()", is(1));

        verify(dataAddressResolver).resolve(token);
    }

    @Test
    void should_returnInternalServerError_if_transferFails() {
        var token = UUID.randomUUID().toString();
        var errorMsg = UUID.randomUUID().toString();
        when(dataAddressResolver.resolve(any())).thenReturn(Result.success(testDestAddress()));
        when(pipelineService.transfer(any(), any()))
                .thenReturn(completedFuture(StreamResult.error(errorMsg)));

        baseRequest()
                .header(AUTHORIZATION, token)
                .when()
                .post("/any")
                .then()
                .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                .contentType(JSON)
                .body("errors[0]", is(errorMsg));
    }

    @Test
    void should_returnInternalServerError_if_transferThrows() {
        var token = UUID.randomUUID().toString();
        var errorMsg = UUID.randomUUID().toString();
        when(dataAddressResolver.resolve(any())).thenReturn(Result.success(testDestAddress()));
        when(pipelineService.transfer(any(DataFlowStartMessage.class), any()))
                .thenReturn(failedFuture(new RuntimeException(errorMsg)));

        baseRequest()
                .header(AUTHORIZATION, token)
                .when()
                .post("/any")
                .then()
                .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                .contentType(JSON)
                .body("errors[0]", is("Unhandled exception occurred during data transfer: " + errorMsg));
    }

    @Test
    void shouldStreamSourceToResponse() {
        when(dataAddressResolver.resolve(any())).thenReturn(Result.success(testDestAddress()));
        when(pipelineService.transfer(any(), any())).thenAnswer(i -> {
            ((AsyncStreamingDataSink) i.getArgument(1)).transfer(new TestDataSource("application/something", "data"));
            return CompletableFuture.completedFuture(StreamResult.success());
        });

        var responseBody = baseRequest()
                .header(AUTHORIZATION, UUID.randomUUID().toString())
                .when()
                .post("/any?foo=bar")
                .then()
                .log().ifError()
                .statusCode(Response.Status.OK.getStatusCode())
                .contentType("application/something")
                .extract().body().asString();

        assertThat(responseBody).isEqualTo("data");
        var requestCaptor = ArgumentCaptor.forClass(DataFlowStartMessage.class);
        verify(pipelineService).transfer(requestCaptor.capture(), any());
        var request = requestCaptor.getValue();
        assertThat(request.getDestinationDataAddress().getType()).isEqualTo(AsyncStreamingDataSink.TYPE);
        assertThat(request.getSourceDataAddress().getType()).isEqualTo("test");
        assertThat(request.getProperties()).containsEntry("method", "POST").containsEntry("pathSegments", "any").containsEntry("queryParams", "foo=bar");
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port)
                .when();
    }

    private DataAddress testDestAddress() {
        return DataAddress.Builder.newInstance().type("test").build();
    }

    @Override
    protected Object controller() {
        return new DataPlanePublicApiController(pipelineService, dataAddressResolver, Executors.newSingleThreadExecutor());
    }

    private record TestDataSource(String mediaType, String data) implements DataSource, DataSource.Part {

        @Override
        public StreamResult<Stream<Part>> openPartStream() {
            return StreamResult.success(Stream.of(this));
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(data.getBytes());
        }

    }

}
