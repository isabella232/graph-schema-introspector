/*
 * Copyright (c) 2023 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.graph_schema.introspector;

import java.io.IOException;
import java.io.Serial;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.neo4j.cypherdsl.support.schema_name.SchemaNames;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Resource;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.f4b6a3.tsid.TsidFactory;

/**
 * UDF for creating a Graph database schema according to the format defined here:
 *
 * <a href="https://github.com/neo4j/graph-schema-json-js-utils">graph-schema-json-js-utils</a>, see scheme
 * <a href="https://github.com/neo4j/graph-schema-json-js-utils/blob/main/json-schema.json">here</a> and an example
 * <a href="https://github.com/neo4j/graph-schema-json-js-utils/blob/main/test/validation/test-schemas/full.json">here</a>.
 * <p>
 * The instrospector creates JSON ids based on the labels and types by default. It can alternatively use
 * Time-Sorted Unique Identifiers (TSID) for the ids inside the generated schema by calling it via
 * {@code RETURN db.introspect({useConstantIds: false}}
 */
public class Introspect {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TsidFactory TSID_FACTORY = TsidFactory.builder()
		.withRandomFunction(length -> {
			final byte[] bytes = new byte[length];
			ThreadLocalRandom.current().nextBytes(bytes);
			return bytes;
		}).build();
	public static final Pattern ENCLOSING_TICK_MARKS = Pattern.compile("^`(.+)`$");

	public static final Map<String, String> TYPE_MAPPING = Map.of(
		"Long", "integer",
		"Double", "float"
	);

	@Context
	public Transaction transaction;

	@UserFunction(name = "db.introspect")
	@Description("" +
		"Call with {useConstantIds: false} to generate substitute ids for all tokens and use {prettyPrint: true} for enabling pretty printing;" +
		"{quoteTokens: false} will disable quotation of tokens.")
	public String introspectSchema(@Name("params") Map<String, Object> params) throws Exception {

		var useConstantIds = (boolean) params.getOrDefault("useConstantIds", true);
		var prettyPrint = (boolean) params.getOrDefault("prettyPrint", false);
		var quoteTokens = (boolean) params.getOrDefault("quoteTokens", true);

		var nodeLabels = getNodeLabels(useConstantIds, quoteTokens);
		var relationshipTypes = getRelationshipTypes(useConstantIds, quoteTokens);

		var nodeObjectTypeIdGenerator = new CachingUnaryOperator<>(new NodeObjectIdGenerator(useConstantIds));
		var relationshipObjectIdGenerator = new RelationshipObjectIdGenerator(useConstantIds);

		var nodeObjectTypes = getNodeObjectTypes(nodeObjectTypeIdGenerator, nodeLabels);
		var relationshipObjectTypes = getRelationshipObjectTypes(nodeObjectTypeIdGenerator, relationshipObjectIdGenerator, relationshipTypes);

		try (var result = new StringWriter()) {
			try (var gen = OBJECT_MAPPER.createGenerator(result)) {
				if (prettyPrint) {
					gen.useDefaultPrettyPrinter();
				}
				gen.writeStartObject();
				gen.writeObjectFieldStart("graphSchemaRepresentation");
				gen.writeFieldName("graphSchema");
				gen.writeStartObject();
				writeArray(gen, "nodeLabels", nodeLabels.values());
				writeArray(gen, "relationshipTypes", relationshipTypes.values());
				writeArray(gen, "nodeObjectTypes", nodeObjectTypes.values());
				writeArray(gen, "relationshipObjectTypes", relationshipObjectTypes.values());
				gen.writeEndObject();
				gen.writeEndObject();
				gen.writeEndObject();
			}
			result.flush();
			return result.toString();
		}
	}

	record Token(@JsonProperty("$id") String id, @JsonProperty("token") String value) {
	}

	private Map<String, Token> getNodeLabels(boolean useConstantIds, boolean quoteTokens) throws Exception {

		return getToken(transaction.getAllLabelsInUse(), Label::name, quoteTokens, useConstantIds ? "nl:%s"::formatted : ignored -> TSID_FACTORY.create().format("%S"));
	}

	private Map<String, Token> getRelationshipTypes(boolean useConstantIds, boolean quoteTokens) throws Exception {

		return getToken(transaction.getAllRelationshipTypesInUse(), RelationshipType::name, quoteTokens, useConstantIds ? "rt:%s"::formatted : ignored -> TSID_FACTORY.create().format("%S"));
	}

	private <T> Map<String, Token> getToken(Iterable<T> tokensInUse, Function<T, String> nameExtractor, boolean quoteTokens, UnaryOperator<String> idGenerator) throws Exception {

		Function<Token, Token> valueMapper = Function.identity();
		if (quoteTokens) {
			valueMapper = token -> new Token(token.id(), SchemaNames.sanitize(token.value()).orElse(token.value));
		}
		try {
			return StreamSupport.stream(tokensInUse.spliterator(), false)
				.map(label -> {
					var tokenValue = nameExtractor.apply(label);
					return new Token(idGenerator.apply(tokenValue), tokenValue);
				})
				.collect(Collectors.toMap(Token::value, valueMapper));
		} finally {
			if (tokensInUse instanceof Resource resource) {
				resource.close();
			}
		}
	}

	@JsonSerialize(using = TypeSerializer.class)
	record Type(String value, String itemType) {
	}

	@JsonPropertyOrder({"token", "type", "mandatory"})
	record Property(
		String token,
		@JsonProperty("type") @JsonSerialize(using = TypeListSerializer.class) List<Type> types,
		@JsonInclude(Include.NON_DEFAULT) boolean mandatory) {
	}

	record NodeObjectType(
		@JsonProperty("$id") String id,
		List<Ref> labels,
		List<Property> properties) {

		NodeObjectType(String id, List<Ref> labels) {
			this(id, labels, new ArrayList<>()); // Mutable on purpose
		}
	}

	private Map<String, NodeObjectType> getNodeObjectTypes(UnaryOperator<String> idGenerator, Map<String, Token> labelIdToToken) throws Exception {

		if (labelIdToToken.isEmpty()) {
			return Map.of();
		}

		// language=cypher
		var query = """
			CALL db.schema.nodeTypeProperties()
			YIELD nodeType, nodeLabels, propertyName, propertyTypes, mandatory
			RETURN *
			ORDER BY nodeType ASC
			""";

		var nodeObjectTypes = new LinkedHashMap<String, NodeObjectType>();
		transaction.execute(query).accept((Result.ResultVisitor<Exception>) resultRow -> {
			@SuppressWarnings("unchecked")
			var nodeLabels = ((List<String>) resultRow.get("nodeLabels")).stream().sorted().toList();

			var id = idGenerator.apply(resultRow.getString("nodeType"));
			var nodeObject = nodeObjectTypes.computeIfAbsent(id, key -> new NodeObjectType(key, nodeLabels
				.stream().map(l -> new Ref(labelIdToToken.get(l).id)).toList()));
			extractProperty(resultRow)
				.ifPresent(nodeObject.properties()::add);

			return true;
		});
		return nodeObjectTypes;
	}

	record Ref(@JsonProperty("$ref") String value) {
	}

	record RelationshipObjectType(@JsonProperty("$id") String id, Ref type, Ref from, Ref to, List<Property> properties) {

		RelationshipObjectType(String id, Ref type, Ref from, Ref to) {
			this(id, type, from, to, new ArrayList<>()); // Mutable on purpose
		}
	}

	private Map<String, RelationshipObjectType> getRelationshipObjectTypes(
		UnaryOperator<String> nodeObjectTypeIdGenerator,
		BinaryOperator<String> idGenerator,
		Map<String, Token> relationshipIdToToken
	) throws Exception {

		if (relationshipIdToToken.isEmpty()) {
			return Map.of();
		}

		// language=cypher
		var query = """
			CALL db.schema.relTypeProperties() YIELD relType, propertyName, propertyTypes, mandatory
			WITH substring(relType, 2, size(relType)-3) AS relType, propertyName, propertyTypes, mandatory
			MATCH (n)-[r]->(m) WHERE type(r) = relType
			WITH DISTINCT labels(n) AS from, labels(m) AS to, relType, propertyName, propertyTypes, mandatory
			RETURN *
			ORDER BY relType ASC
			""";

		var relationshipObjectTypes = new LinkedHashMap<String, RelationshipObjectType>();

		transaction.execute(query).accept((Result.ResultVisitor<Exception>) resultRow -> {
			var relType = resultRow.getString("relType");
			@SuppressWarnings("unchecked")
			var from = nodeObjectTypeIdGenerator.apply(":" + ((List<String>) resultRow.get("from")).stream()
				.sorted()
				.map(v -> "`" + v + "`")
				.collect(Collectors.joining(":")));
			@SuppressWarnings("unchecked")
			var to = nodeObjectTypeIdGenerator.apply(":" + ((List<String>) resultRow.get("to")).stream()
				.sorted()
				.map(v -> "`" + v + "`")
				.collect(Collectors.joining(":")));

			var id = idGenerator.apply(relType, to);
			var relationshipObject = relationshipObjectTypes.computeIfAbsent(id, key ->
				new RelationshipObjectType(key, new Ref(relationshipIdToToken.get(relType).id()), new Ref(from), new Ref(to)));
			extractProperty(resultRow)
				.ifPresent(relationshipObject.properties()::add);

			return true;
		});
		return relationshipObjectTypes;
	}

	Optional<Property> extractProperty(Result.ResultRow resultRow) {
		var propertyName = resultRow.getString("propertyName");
		if (propertyName == null) {
			return Optional.empty();
		}

		@SuppressWarnings("unchecked")
		var types = ((List<String>) resultRow.get("propertyTypes")).stream()
			.map(t -> {
				String type;
				String itemType = null;
				if (t.endsWith("Array")) {
					type = "array";
					itemType = t.replace("Array", "");
					itemType = TYPE_MAPPING.getOrDefault(itemType, itemType).toLowerCase(Locale.ROOT);
				} else {
					type = TYPE_MAPPING.getOrDefault(t, t).toLowerCase(Locale.ROOT);
				}
				return new Type(type, itemType);
			}).toList();

		return Optional.of(new Property(propertyName, types, resultRow.getBoolean("mandatory")));
	}

	static String splitStripAndJoin(String value, String prefix) {
		return Arrays.stream(value.split(":"))
			.map(String::trim)
			.filter(Predicate.not(String::isBlank))
			.map(t -> ENCLOSING_TICK_MARKS.matcher(t).replaceAll(m -> m.group(1)))
			.collect(Collectors.joining(":", prefix + ":", ""));
	}

	static class NodeObjectIdGenerator implements UnaryOperator<String> {

		private final boolean useConstantIds;

		NodeObjectIdGenerator(boolean useConstantIds) {
			this.useConstantIds = useConstantIds;
		}

		@Override
		public String apply(String nodeType) {

			if (useConstantIds) {
				return splitStripAndJoin(nodeType, "n");
			}

			return TSID_FACTORY.create().format("%S");
		}
	}

	/**
	 * Not thread safe.
	 */
	static class RelationshipObjectIdGenerator implements BinaryOperator<String> {

		private final boolean useConstantIds;
		private final Map<String, Map<String, Integer>> counter = new HashMap<>();

		RelationshipObjectIdGenerator(boolean useConstantIds) {
			this.useConstantIds = useConstantIds;
		}

		@Override
		public String apply(String relType, String target) {

			if (useConstantIds) {
				var id = splitStripAndJoin(relType, "r");
				var count = counter.computeIfAbsent(id, ignored -> new HashMap<>());
				if (count.isEmpty()) {
					count.put(target, 0);
					return id;
				} else if (count.containsKey(target)) {
					var value = count.get(target);
					return value == 0 ? id : id + "_" + value;
				} else {
					var newValue = count.size();
					count.put(target, newValue);
					return id + "_" + newValue;
				}
			}

			return TSID_FACTORY.create().format("%S");
		}
	}

	/**
	 * Not thread safe.
	 */
	static class CachingUnaryOperator<T> implements UnaryOperator<T> {

		private final Map<T, T> cache = new HashMap<>();
		private final UnaryOperator<T> delegate;

		CachingUnaryOperator(UnaryOperator<T> delegate) {
			this.delegate = delegate;
		}

		@Override
		public T apply(T s) {
			return cache.computeIfAbsent(s, delegate);
		}
	}

	private void writeArray(JsonGenerator gen, String fieldName, Collection<?> items) throws Exception {
		gen.writeArrayFieldStart(fieldName);
		for (Object item : items) {
			gen.writeObject(item);
		}
		gen.writeEndArray();
	}

	static class TypeSerializer extends StdSerializer<Type> {

		@Serial
		private static final long serialVersionUID = -1260953273076427362L;

		TypeSerializer() {
			super(Type.class);
		}

		@Override
		public void serialize(Type type, JsonGenerator gen, SerializerProvider serializerProvider) throws IOException {
			gen.writeStartObject();
			gen.writeStringField("type", type.value());
			if (type.value().equals("array")) {
				gen.writeObjectFieldStart("items");
				gen.writeStringField("type", type.itemType());
				gen.writeEndObject();
			}
			gen.writeEndObject();
		}
	}

	static class TypeListSerializer extends StdSerializer<List<Type>> {

		@Serial
		private static final long serialVersionUID = -8831424337461613203L;

		TypeListSerializer() {
			super(TypeFactory.defaultInstance().constructType(new TypeReference<List<Type>>() {
			}));
		}

		@Override
		public void serialize(List<Type> types, JsonGenerator gen, SerializerProvider serializerProvider) throws IOException {
			if (types.isEmpty()) {
				gen.writeNull();
			} else if (types.size() == 1) {
				gen.writeObject(types.get(0));
			} else {
				gen.writeObject(types);
			}
		}
	}
}

