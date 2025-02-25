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

package org.eclipse.edc.test.system.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.specification.RequestSpecification;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.eclipse.edc.connector.contract.spi.ContractOfferId;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.connector.contract.spi.types.negotiation.ContractNegotiationStates.FINALIZED;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.CONTEXT;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.ID;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.VOCAB;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.DCAT_DATASET_ATTRIBUTE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_ASSIGNER_ATTRIBUTE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_POLICY_ATTRIBUTE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_TARGET_ATTRIBUTE;
import static org.eclipse.edc.spi.CoreConstants.EDC_NAMESPACE;

/**
 * Essentially a wrapper around the management API enabling to test interactions with other participants, eg. catalog, transfer...
 */
public class Participant {

    protected String id;
    protected String name;
    protected Endpoint managementEndpoint;
    protected Endpoint protocolEndpoint;
    protected JsonLd jsonLd;
    protected ObjectMapper objectMapper;

    protected Duration timeout = Duration.ofSeconds(30);

    protected String protocol = "dataspace-protocol-http";

    protected Participant() {
    }

    public String getName() {
        return name;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getProtocol() {
        return protocol;
    }

    public Endpoint getProtocolEndpoint() {
        return protocolEndpoint;
    }

    public Endpoint getManagementEndpoint() {
        return managementEndpoint;
    }

    /**
     * Create a new {@link org.eclipse.edc.spi.types.domain.asset.Asset}.
     *
     * @param assetId               asset id
     * @param properties            asset properties
     * @param dataAddressProperties data address properties
     * @return id of the created asset.
     */
    public String createAsset(String assetId, Map<String, Object> properties, Map<String, Object> dataAddressProperties) {
        var requestBody = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(ID, assetId)
                .add("properties", createObjectBuilder(properties))
                .add("dataAddress", createObjectBuilder(dataAddressProperties))
                .build();

        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v3/assets")
                .then()
                .log().ifError()
                .statusCode(200)
                .contentType(JSON)
                .extract().jsonPath().getString(ID);
    }

    /**
     * Create a new {@link org.eclipse.edc.connector.policy.spi.PolicyDefinition}.
     *
     * @param policy Json-LD representation of the policy
     * @return id of the created policy.
     */
    public String createPolicyDefinition(JsonObject policy) {
        var requestBody = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "PolicyDefinitionDto")
                .add("policy", policy)
                .build();

        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/policydefinitions")
                .then()
                .log().ifError()
                .statusCode(200)
                .contentType(JSON)
                .extract().jsonPath().getString(ID);
    }

    /**
     * Create a new {@link org.eclipse.edc.connector.contract.spi.types.offer.ContractDefinition}.
     *
     * @param assetId          asset id
     * @param definitionId     contract definition id
     * @param accessPolicyId   access policy id
     * @param contractPolicyId contract policy id
     * @return id of the created contract definition.
     */
    public String createContractDefinition(String assetId, String definitionId, String accessPolicyId, String contractPolicyId) {
        var requestBody = createObjectBuilder()
                .add(ID, definitionId)
                .add(TYPE, EDC_NAMESPACE + "ContractDefinition")
                .add(EDC_NAMESPACE + "accessPolicyId", accessPolicyId)
                .add(EDC_NAMESPACE + "contractPolicyId", contractPolicyId)
                .add(EDC_NAMESPACE + "assetsSelector", Json.createArrayBuilder()
                        .add(createObjectBuilder()
                                .add(TYPE, "Criterion")
                                .add(EDC_NAMESPACE + "operandLeft", EDC_NAMESPACE + "id")
                                .add(EDC_NAMESPACE + "operator", "=")
                                .add(EDC_NAMESPACE + "operandRight", assetId)
                                .build())
                        .build())
                .build();

        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/contractdefinitions")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString(ID);
    }

    /**
     * Request provider catalog.
     *
     * @param provider data provider
     * @return list of {@link org.eclipse.edc.catalog.spi.Dataset}.
     */
    public JsonArray getCatalogDatasets(Participant provider) {
        return getCatalogDatasets(provider, null);
    }

    /**
     * Request provider catalog.
     *
     * @param provider data provider
     * @return list of {@link org.eclipse.edc.catalog.spi.Dataset}.
     */
    public JsonArray getCatalogDatasets(Participant provider, JsonObject querySpec) {
        var datasetReference = new AtomicReference<JsonArray>();
        var requestBodyBuilder = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "CatalogRequest")
                .add("counterPartyId", provider.id)
                .add("counterPartyAddress", provider.protocolEndpoint.url.toString())
                .add("protocol", protocol);

        if (querySpec != null) {
            requestBodyBuilder.add("querySpec", querySpec);
        }

        await().atMost(timeout).untilAsserted(() -> {
            var response = managementEndpoint.baseRequest()
                    .contentType(JSON)
                    .when()
                    .body(requestBodyBuilder.build())
                    .post("/v2/catalog/request")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .extract().body().asString();

            var responseBody = objectMapper.readValue(response, JsonObject.class);

            var catalog = jsonLd.expand(responseBody).orElseThrow(f -> new EdcException(f.getFailureDetail()));

            var datasets = catalog.getJsonArray(DCAT_DATASET_ATTRIBUTE);
            assertThat(datasets).hasSizeGreaterThan(0);

            datasetReference.set(datasets);
        });

        return datasetReference.get();
    }

    /**
     * Get first {@link org.eclipse.edc.catalog.spi.Dataset} from provider matching the given asset id.
     *
     * @param provider data provider
     * @param assetId  asset id
     * @return dataset.
     */
    public JsonObject getDatasetForAsset(Participant provider, String assetId) {
        var datasetReference = new AtomicReference<JsonObject>();
        var requestBody = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "DatasetRequest")
                .add(ID, assetId)
                .add("counterPartyId", provider.id)
                .add("counterPartyAddress", provider.protocolEndpoint.url.toString())
                .add("protocol", protocol)
                .build();

        await().atMost(timeout).untilAsserted(() -> {
            var response = managementEndpoint.baseRequest()
                    .contentType(JSON)
                    .when()
                    .body(requestBody)
                    .post("/v2/catalog/dataset/request")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .extract().body().asString();

            var compacted = objectMapper.readValue(response, JsonObject.class);

            var dataset = jsonLd.expand(compacted).orElseThrow(f -> new EdcException(f.getFailureDetail()));

            datasetReference.set(dataset);
        });

        return datasetReference.get();
    }

    /**
     * Initiate negotiation with a provider for an asset.
     * - Fetches the dataset for the ID
     * - Extracts the first policy
     * - Starts the contract negotiation
     *
     * @param provider data provider
     * @param assetId  asset id
     * @return id of the contract negotiation.
     */
    public String initContractNegotiation(Participant provider, String assetId) {
        var dataset = getDatasetForAsset(provider, assetId);
        var policy = dataset.getJsonArray(ODRL_POLICY_ATTRIBUTE).get(0).asJsonObject();
        var policyWithTarget = createObjectBuilder(policy).add(ODRL_TARGET_ATTRIBUTE, createObjectBuilder().add(ID, dataset.get(ID))).build();
        return initContractNegotiation(provider, policyWithTarget);
    }

    /**
     * Initiate negotiation with a provider given an input policy.
     *
     * @param provider data provider
     * @param policy   policy
     * @return id of the contract negotiation.
     */
    public String initContractNegotiation(Participant provider, JsonObject policy) {
        var requestBody = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "ContractRequestDto")
                .add("providerId", provider.id)
                .add("counterPartyAddress", provider.protocolEndpoint.getUrl().toString())
                .add("protocol", protocol)
                .add("policy", jsonLd.compact(policy).getContent())
                .build();

        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/contractnegotiations")
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().jsonPath().getString(ID);
    }

    /**
     * Initiate negotiation with a provider.
     *
     * @param provider data provider
     * @param policy   policy
     * @return id of the contract agreement.
     */
    public String negotiateContract(Participant provider, JsonObject policy) {

        var negotiationId = initContractNegotiation(provider, policy);

        await().atMost(timeout).untilAsserted(() -> {
            var state = getContractNegotiationState(negotiationId);
            assertThat(state).isEqualTo(FINALIZED.name());
        });

        return getContractAgreementId(negotiationId);
    }

    /**
     * Initiate data transfer.
     *
     * @param provider            data provider
     * @param contractAgreementId contract agreement id
     * @param assetId             asset id
     * @param privateProperties   private properties
     * @param destination         data destination address
     * @param transferType        type of transfer
     * @return id of the transfer process.
     */
    public String initiateTransfer(Participant provider, String contractAgreementId, String assetId, JsonObject privateProperties, JsonObject destination, String transferType) {
        var requestBodyBuilder = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "TransferRequest")
                .add("dataDestination", destination)
                .add("protocol", protocol)
                .add("assetId", assetId)
                .add("contractId", contractAgreementId)
                .add("connectorId", provider.id)
                .add("counterPartyAddress", provider.protocolEndpoint.url.toString())
                .add("privateProperties", privateProperties);

        if (transferType != null) {
            requestBodyBuilder.add("transferType", transferType);
        }

        var requestBody = requestBodyBuilder.build();
        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/transferprocesses")
                .then()
                .log().ifError()
                .statusCode(200)
                .extract().body().jsonPath().getString(ID);
    }

    /**
     * Returns all the transfer processes with empty query
     *
     * @return The transfer processes
     */
    public JsonArray getTransferProcesses() {
        var query = createObjectBuilder()
                .add(CONTEXT, createObjectBuilder().add(VOCAB, EDC_NAMESPACE))
                .add(TYPE, "QuerySpec")
                .build();
        return getTransferProcesses(query);
    }

    /**
     * Returns all the transfer processes matching the input query
     *
     * @param query The input query
     * @return The transfer processes
     */
    public JsonArray getTransferProcesses(JsonObject query) {
        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .body(query)
                .when()
                .post("/v2/transferprocesses/request")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(JsonArray.class);
    }

    /**
     * Request a provider asset:
     * - retrieves the contract definition associated with the asset,
     * - handles the contract negotiation,
     * - initiate the data transfer.
     *
     * @param provider          data provider
     * @param assetId           asset id
     * @param privateProperties private properties of the data request
     * @param destination       data destination
     * @return transfer process id.
     */
    public String requestAsset(Participant provider, String assetId, JsonObject privateProperties, JsonObject destination) {
        return requestAsset(provider, assetId, privateProperties, destination, null);
    }

    /**
     * Request a provider asset:
     * - retrieves the contract definition associated with the asset,
     * - handles the contract negotiation,
     * - initiate the data transfer.
     *
     * @param provider          data provider
     * @param assetId           asset id
     * @param privateProperties private properties of the data request
     * @param destination       data destination
     * @param transferType      transfer type
     * @return transfer process id.
     */
    public String requestAsset(Participant provider, String assetId, JsonObject privateProperties, JsonObject destination, String transferType) {
        var dataset = getDatasetForAsset(provider, assetId);
        var policy = dataset.getJsonArray(ODRL_POLICY_ATTRIBUTE).get(0).asJsonObject();
        var offer = createObjectBuilder(policy)
                .add(ODRL_ASSIGNER_ATTRIBUTE, createObjectBuilder().add(ID, provider.id))
                .add(ODRL_TARGET_ATTRIBUTE, createObjectBuilder().add(ID, dataset.get(ID)))
                .build();
        var contractAgreementId = negotiateContract(provider, offer);
        var transferProcessId = initiateTransfer(provider, contractAgreementId, assetId, privateProperties, destination, transferType);
        assertThat(transferProcessId).isNotNull();
        return transferProcessId;
    }

    /**
     * Get current state of a transfer process.
     *
     * @param id transfer process id
     * @return state of the transfer process.
     */
    public String getTransferProcessState(String id) {
        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .when()
                .get("/v2/transferprocesses/{id}/state", id)
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString("state");
    }

    /**
     * Get current state of a contract negotiation.
     *
     * @param id contract negotiation id
     * @return state of a contract negotiation.
     */
    public String getContractNegotiationState(String id) {
        return getContractNegotiationField(id, "state");
    }

    protected String getContractNegotiationField(String negotiationId, String fieldName) {
        return managementEndpoint.baseRequest()
                .contentType(JSON)
                .when()
                .get("/v2/contractnegotiations/{id}", negotiationId)
                .then()
                .statusCode(200)
                .extract().body().jsonPath()
                .getString(fieldName);
    }

    private ContractOfferId extractContractDefinitionId(JsonObject dataset) {
        var contractId = dataset.getJsonArray(ODRL_POLICY_ATTRIBUTE).get(0).asJsonObject().getString(ID);
        return ContractOfferId.parseId(contractId).orElseThrow(f -> new RuntimeException(f.getFailureDetail()));
    }

    private String getContractAgreementId(String negotiationId) {
        var contractAgreementIdAtomic = new AtomicReference<String>();

        await().atMost(timeout).untilAsserted(() -> {
            var agreementId = getContractNegotiationField(negotiationId, "contractAgreementId");
            assertThat(agreementId).isNotNull().isInstanceOf(String.class);

            contractAgreementIdAtomic.set(agreementId);
        });

        var contractAgreementId = contractAgreementIdAtomic.get();
        assertThat(id).isNotEmpty();
        return contractAgreementId;
    }

    /**
     * Represent an endpoint exposed by a {@link Participant}.
     */
    public static class Endpoint {
        private final URI url;
        private final Map<String, String> headers;

        public Endpoint(URI url) {
            this.url = url;
            this.headers = new HashMap<>();
        }

        public Endpoint(URI url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        public RequestSpecification baseRequest() {
            return given().baseUri(url.toString()).headers(headers);
        }

        public URI getUrl() {
            return url;
        }
    }

    public static class Builder<P extends Participant, B extends Participant.Builder<P, B>> {
        protected final P participant;

        protected Builder(P participant) {
            this.participant = participant;
        }

        public static <B extends Builder<Participant, B>> Builder<Participant, B> newInstance() {
            return new Builder<>(new Participant());
        }

        public B id(String id) {
            participant.id = id;
            return self();
        }

        public B name(String name) {
            participant.name = name;
            return self();
        }

        public B protocol(String protocol) {
            participant.protocol = protocol;
            return self();
        }

        public B timeout(Duration timeout) {
            participant.timeout = timeout;
            return self();
        }

        public B managementEndpoint(Endpoint managementEndpoint) {
            participant.managementEndpoint = managementEndpoint;
            return self();
        }

        public B protocolEndpoint(Endpoint protocolEndpoint) {
            participant.protocolEndpoint = protocolEndpoint;
            return self();
        }

        public B jsonLd(JsonLd jsonLd) {
            participant.jsonLd = jsonLd;
            return self();
        }

        public B objectMapper(ObjectMapper objectMapper) {
            participant.objectMapper = objectMapper;
            return self();
        }

        public Participant build() {
            Objects.requireNonNull(participant.id, "id");
            Objects.requireNonNull(participant.name, "name");
            Objects.requireNonNull(participant.managementEndpoint, "managementEndpoint");
            Objects.requireNonNull(participant.protocolEndpoint, "protocolEndpoint");
            if (participant.jsonLd == null) {
                participant.jsonLd = new TitaniumJsonLd(new ConsoleMonitor());
            }
            if (participant.objectMapper == null) {
                participant.objectMapper = JacksonJsonLd.createObjectMapper();
            }
            return participant;
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }
    }
}
