/*
 *  Copyright (c) 2024 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
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

package org.eclipse.edc.connector.dataplane.framework.iam;

import org.assertj.core.api.Assertions;
import org.eclipse.edc.connector.dataplane.spi.AccessTokenData;
import org.eclipse.edc.connector.dataplane.spi.store.AccessTokenDataStore;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.token.spi.TokenDecorator;
import org.eclipse.edc.token.spi.TokenGenerationService;
import org.eclipse.edc.token.spi.TokenValidationService;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.edc.junit.assertions.AbstractResultAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DefaultDataPlaneAccessTokenServiceImplTest {

    private final AccessTokenDataStore store = mock();
    private final TokenGenerationService tokenGenService = mock();
    private final TokenValidationService tokenValidationService = mock();
    private final DefaultDataPlaneAccessTokenServiceImpl accessTokenService = new DefaultDataPlaneAccessTokenServiceImpl(tokenGenService,
            store, mock(), mock(), tokenValidationService, mock());
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Test
    void obtainToken() {
        var params = TokenParameters.Builder.newInstance().claims("foo", "bar").claims("jti", "baz").header("qux", "quz").build();
        var address = DataAddress.Builder.newInstance().type("test-type").build();

        when(tokenGenService.generate(any(), any(TokenDecorator[].class))).thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token("foo-token").build()));
        when(store.store(any(AccessTokenData.class))).thenReturn(StoreResult.success());

        var result = accessTokenService.obtainToken(params, address);
        assertThat(result).isSucceeded().extracting(TokenRepresentation::getToken).isEqualTo("foo-token");

        verify(tokenGenService).generate(any(), any(TokenDecorator[].class));
        verify(store).store(any(AccessTokenData.class));
    }

    @Test
    void obtainToken_invalidParams() {
        assertThatThrownBy(() -> accessTokenService.obtainToken(null, DataAddress.Builder.newInstance().type("foo").build()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accessTokenService.obtainToken(TokenParameters.Builder.newInstance().build(), null))
                .isInstanceOf(NullPointerException.class);

    }

    @Test
    void obtainToken_noTokenId() {
        var params = TokenParameters.Builder.newInstance().claims("foo", "bar")/* missing: .claims("jti", "baz")*/.header("qux", "quz").build();
        var address = DataAddress.Builder.newInstance().type("test-type").build();

        when(tokenGenService.generate(any(), any(TokenDecorator[].class))).thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token("foo-token").build()));
        when(store.store(any(AccessTokenData.class))).thenReturn(StoreResult.success());

        var result = accessTokenService.obtainToken(params, address);
        assertThat(result).isSucceeded().extracting(TokenRepresentation::getToken).isEqualTo("foo-token");

        verify(tokenGenService).generate(any(), any(TokenDecorator[].class));
        verify(store).store(argThat(accessTokenData -> UUID_PATTERN.matcher(accessTokenData.id()).matches()));
    }

    @Test
    void obtainToken_creationFails() {
        var params = TokenParameters.Builder.newInstance().claims("foo", "bar").claims("jti", "baz").header("qux", "quz").build();
        var address = DataAddress.Builder.newInstance().type("test-type").build();

        when(tokenGenService.generate(any(), any(TokenDecorator[].class))).thenReturn(Result.failure("test failure"));

        var result = accessTokenService.obtainToken(params, address);
        assertThat(result).isFailed().detail().isEqualTo("test failure");

        verify(tokenGenService).generate(any(), any(TokenDecorator[].class));
        verifyNoMoreInteractions(store);
    }

    @Test
    void obtainToken_storingFails() {
        var params = TokenParameters.Builder.newInstance().claims("foo", "bar").claims("jti", "baz").header("qux", "quz").build();
        var address = DataAddress.Builder.newInstance().type("test-type").build();

        when(tokenGenService.generate(any(), any(TokenDecorator[].class))).thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token("foo-token").build()));
        when(store.store(any(AccessTokenData.class))).thenReturn(StoreResult.alreadyExists("test failure"));

        var result = accessTokenService.obtainToken(params, address);
        assertThat(result).isFailed().detail().isEqualTo("test failure");

        verify(tokenGenService).generate(any(), any(TokenDecorator[].class));
        verify(store).store(any(AccessTokenData.class));
    }

    @Test
    void resolve() {
        var tokenId = "test-id";
        var claimToken = ClaimToken.Builder.newInstance().claim("jti", tokenId).build();
        when(tokenValidationService.validate(anyString(), any(), anyList()))
                .thenReturn(Result.success(claimToken));
        when(store.getById(eq(tokenId))).thenReturn(new AccessTokenData(tokenId, ClaimToken.Builder.newInstance().build(),
                DataAddress.Builder.newInstance().type("test-type").build()));

        var result = accessTokenService.resolve("some-jwt");
        assertThat(result).isSucceeded()
                .satisfies(atd -> Assertions.assertThat(atd.id()).isEqualTo(tokenId));
        verify(tokenValidationService).validate(eq("some-jwt"), any(), anyList());
        verify(store).getById(eq(tokenId));
    }

    @Test
    void resolve_whenValidationFails() {
        var tokenId = "test-id";
        var claimToken = ClaimToken.Builder.newInstance().claim("jti", tokenId).build();
        when(tokenValidationService.validate(anyString(), any(), anyList()))
                .thenReturn(Result.failure("test-failure"));

        var result = accessTokenService.resolve("some-jwt");
        assertThat(result).isFailed()
                .detail().isEqualTo("test-failure");
        verify(tokenValidationService).validate(eq("some-jwt"), any(), anyList());
        verifyNoInteractions(store);
    }

    @Test
    void resolve_whenTokenIdNotFound() {
        var tokenId = "test-id";
        var claimToken = ClaimToken.Builder.newInstance().claim("jti", tokenId).build();
        when(tokenValidationService.validate(anyString(), any(), anyList()))
                .thenReturn(Result.success(claimToken));
        when(store.getById(eq(tokenId))).thenReturn(null);

        var result = accessTokenService.resolve("some-jwt");
        assertThat(result).isFailed()
                .detail().isEqualTo("AccessTokenData with ID 'test-id' does not exist.");
        verify(tokenValidationService).validate(eq("some-jwt"), any(), anyList());
        verify(store).getById(eq(tokenId));
    }
}