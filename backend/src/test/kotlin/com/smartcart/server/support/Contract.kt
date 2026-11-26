package com.smartcart.server.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates real HTTP responses against the frozen OpenAPI contract (contracts/openapi/openapi.yaml).
 *
 * The OpenAPI 3.1 component schemas are re-homed under `$defs` of a JSON Schema 2020-12 document, so
 * every `#/components/schemas/X` reference resolves. Format assertions are on (uuid, email,
 * date-time…) and `writeOnly` properties (passwords) must never appear in a response.
 */
object Contract {
    val contractsDir = File(System.getProperty("contractsDir") ?: "../contracts")
    private val yaml = ObjectMapper(YAMLFactory())
    val json = ObjectMapper()
    val openApi: JsonNode = yaml.readTree(contractsDir.resolve("openapi/openapi.yaml"))

    private val defs: ObjectNode = rewriteRefs(openApi.path("components").path("schemas").deepCopy()) as ObjectNode
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val config = SchemaValidatorsConfig.builder()
        .formatAssertionsEnabled(true)
        .writeOnly(true)
        .build()
    private val cache = ConcurrentHashMap<String, JsonSchema>()

    /** Every operation in the contract as "METHOD /path/{param}". */
    val operations: Set<String> by lazy {
        openApi.path("paths").properties().flatMap { (path, item) ->
            item.properties().map { it.key }.filter { it in HTTP_METHODS }.map { "${it.uppercase()} $path" }
        }.toSet()
    }

    /** Schema for an arbitrary component, e.g. `schemaFor("AuthSession")`. */
    fun componentSchema(name: String): JsonSchema = cache.getOrPut("component:$name") { compile(TextNode("#/\$defs/$name").let { ref -> json.createObjectNode().set<JsonNode>("\$ref", ref) }) }

    /**
     * Problems with a response for [method] [pathTemplate] (as written in the contract) returning [status]
     * with [body]. Empty means it conforms. An undocumented status code is itself a problem.
     */
    fun responseProblems(method: String, pathTemplate: String, status: Int, contentType: String?, body: String): List<String> {
        val op = openApi.path("paths").path(pathTemplate).path(method.lowercase())
        if (op.isMissingNode) return listOf("$method $pathTemplate is not in the contract")
        var resp = op.path("responses").path(status.toString()).let { if (it.isMissingNode) op.path("responses").path("default") else it }
        if (resp.isMissingNode) return listOf("$method $pathTemplate does not document status $status")
        resp.path("\$ref").takeIf { it.isTextual }?.let { resp = resolve(it.asText()) }
        val media = resp.path("content").path("application/json")
        if (media.isMissingNode) {
            return if (body.isBlank()) emptyList() else listOf("status $status has no documented body but one was returned")
        }
        if (contentType == null || !contentType.startsWith("application/json")) return listOf("expected application/json, got $contentType")
        val node = runCatching { json.readTree(body) }.getOrElse { return listOf("body is not JSON: ${it.message}") }
        val schema = cache.getOrPut("$method $pathTemplate $status") { compile(media.path("schema")) }
        return schema.validate(node).map { it.toString() }
    }

    private fun compile(schema: JsonNode): JsonSchema {
        val root = json.createObjectNode()
        root.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        root.set<JsonNode>("\$defs", defs)
        (rewriteRefs(schema.deepCopy()) as ObjectNode).properties().forEach { (k, v) -> root.set<JsonNode>(k, v) }
        return factory.getSchema(root, config)
    }

    private fun resolve(ref: String): JsonNode =
        ref.removePrefix("#/").split("/").fold(openApi) { n, part -> n.path(part) }

    private fun rewriteRefs(node: JsonNode): JsonNode {
        when {
            node is ObjectNode -> {
                val ref = node.get("\$ref")
                if (ref != null && ref.isTextual && ref.asText().startsWith("#/components/schemas/")) {
                    node.put("\$ref", ref.asText().replace("#/components/schemas/", "#/\$defs/"))
                }
                node.properties().forEach { (k, v) -> node.replace(k, rewriteRefs(v)) }
            }
            node.isArray -> node.forEachIndexed { i, v -> (node as com.fasterxml.jackson.databind.node.ArrayNode).set(i, rewriteRefs(v)) }
        }
        return node
    }

    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
}
