package com.delta.admin.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOCUMENT = """
            {
              "openapi": "3.1.0",
              "servers": [{"url":"http://localhost:8080"}],
              "paths": {"/items": {"post": {
                "summary": "Creates an item",
                "parameters": [{"name":"page","in":"query","schema":{"type":"integer"}},
                               {"name":"size","in":"query","schema":{"type":"integer"}}],
                "responses": {"200": {"description":"Found","content":{"application/json":{
                  "schema":{"$ref":"#/components/schemas/Item"}
                }}}}
              }}},
              "components": {"schemas": {"Item": {
                "type":"object", "required":["description","tags"],
                "properties": {"description":{"type":"string","description":"Public name"},
                               "tags":{"type":"array","items":{"type":"string","enum":["a","b"]}}}
              }}}
            }
            """;

    @Test
    void ignoresDocumentationAndNonStructuralOrdering() throws Exception {
        JsonNode runtime = MAPPER.readTree(DOCUMENT
                .replace("localhost:8080", "localhost:9090")
                .replace("Creates an item", "Create item")
                .replace("Public name", "A display name")
                .replace("[\"description\",\"tags\"]", "[\"tags\",\"description\"]")
                .replace("[\"a\",\"b\"]", "[\"b\",\"a\"]"));
        var parameters = (ArrayNode) runtime.at("/paths/~1items/post/parameters");
        parameters.add(parameters.remove(0));

        OpenApiBaselineTest.assertContract(MAPPER.readTree(DOCUMENT), runtime);
    }

    @ParameterizedTest
    @CsvSource({
            "/paths/~1items, post, missing: POST /items",
            "/paths/~1items/post/parameters/0/schema, type, changed: POST /items",
            "/paths/~1items/post/responses/200/content/application~1json/schema, $ref, changed: POST /items",
            "/components/schemas, Item, missing: Item",
            "/components/schemas/Item, required, changed: Item",
            "/components/schemas/Item/properties, description, changed: Item",
            "/components/schemas/Item/properties/tags/items, enum, changed: Item"
    })
    void rejectsStructuralDrift(String pointer, String field, String message) throws Exception {
        JsonNode runtime = MAPPER.readTree(DOCUMENT);
        ((ObjectNode) runtime.at(pointer)).remove(field);

        assertThatThrownBy(() -> OpenApiBaselineTest.assertContract(MAPPER.readTree(DOCUMENT), runtime))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(message);
    }
}
