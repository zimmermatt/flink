/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.orc;

import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.typeutils.MapTypeInfo;
import org.apache.flink.api.java.typeutils.ObjectArrayTypeInfo;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.expressions.EqualTo;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.GetCompositeField;
import org.apache.flink.table.expressions.GreaterThan;
import org.apache.flink.table.expressions.ItemAt;
import org.apache.flink.table.expressions.Literal;
import org.apache.flink.table.expressions.ResolvedFieldReference;
import org.apache.flink.types.Row;

import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit Tests for {@link OrcTableSource}.
 */
public class OrcTableSourceTest {

	private static final String TEST_FILE_NESTED = "test-data-nested.orc";
	private static final String TEST_SCHEMA_NESTED =
		"struct<" +
			"boolean1:boolean," +
			"byte1:tinyint," +
			"short1:smallint," +
			"int1:int," +
			"long1:bigint," +
			"float1:float," +
			"double1:double," +
			"bytes1:binary," +
			"string1:string," +
			"middle:struct<" +
				"list:array<" +
					"struct<" +
						"int1:int," +
						"string1:string" +
					">" +
				">" +
			">," +
			"list:array<" +
				"struct<" +
					"int1:int," +
					"string1:string" +
				">" +
			">," +
			"map:map<" +
				"string," +
				"struct<" +
					"int1:int," +
					"string1:string" +
				">" +
			">" +
		">";

	@Test
	public void testGetReturnType() throws Exception {

		OrcTableSource orc = OrcTableSource.builder()
			.path(getPath(TEST_FILE_NESTED))
			.forOrcSchema(TEST_SCHEMA_NESTED)
			.build();

		TypeInformation<Row> returnType = orc.getReturnType();
		assertNotNull(returnType);
		assertTrue(returnType instanceof RowTypeInfo);
		RowTypeInfo rowType = (RowTypeInfo) returnType;

		RowTypeInfo expected = Types.ROW_NAMED(getNestedFieldNames(), getNestedFieldTypes());
		assertEquals(expected, rowType);
	}

	@Test
	public void testGetTableSchema() throws Exception {

		OrcTableSource orc = OrcTableSource.builder()
			.path(getPath(TEST_FILE_NESTED))
			.forOrcSchema(TEST_SCHEMA_NESTED)
			.build();

		TableSchema schema = orc.getTableSchema();
		assertNotNull(schema);
		assertArrayEquals(getNestedFieldNames(), schema.getColumnNames());
		assertArrayEquals(getNestedFieldTypes(), schema.getTypes());
	}

	@Test
	public void testProjectFields() throws Exception {

		OrcTableSource orc = OrcTableSource.builder()
			.path(getPath(TEST_FILE_NESTED))
			.forOrcSchema(TEST_SCHEMA_NESTED)
			.build();

		OrcTableSource projected = (OrcTableSource) orc.projectFields(new int[]{3, 5, 1, 0});

		// ensure copy is returned
		assertTrue(orc != projected);

		// ensure table schema is identical
		assertEquals(orc.getTableSchema(), projected.getTableSchema());

		// ensure return type was adapted
		String[] fieldNames = getNestedFieldNames();
		TypeInformation[] fieldTypes = getNestedFieldTypes();
		assertEquals(
			Types.ROW_NAMED(
				new String[] {fieldNames[3], fieldNames[5], fieldNames[1], fieldNames[0]},
				new TypeInformation[] {fieldTypes[3], fieldTypes[5], fieldTypes[1], fieldTypes[0]}),
			projected.getReturnType());

		// ensure IF is configured with selected fields
		OrcTableSource spyTS = spy(projected);
		OrcRowInputFormat mockIF = mock(OrcRowInputFormat.class);
		doReturn(mockIF).when(spyTS).buildOrcInputFormat();
		spyTS.getDataSet(mock(ExecutionEnvironment.class));
		verify(mockIF).selectFields(eq(3), eq(5), eq(1), eq(0));
	}

	@Test
	public void testApplyPredicate() throws Exception {

		OrcTableSource orc = OrcTableSource.builder()
			.path(getPath(TEST_FILE_NESTED))
			.forOrcSchema(TEST_SCHEMA_NESTED)
			.build();

		// expressions for predicates
		Expression pred1 = new GreaterThan(
			new ResolvedFieldReference("int1", Types.INT),
			new Literal(100, Types.INT));
		Expression pred2 = new EqualTo(
			new ResolvedFieldReference("string1", Types.STRING),
			new Literal("hello", Types.STRING));
		Expression pred3 = new EqualTo(
			new GetCompositeField(
				new ItemAt(
					new ResolvedFieldReference(
						"list",
						ObjectArrayTypeInfo.getInfoFor(
							Types.ROW_NAMED(new String[] {"int1", "string1"}, Types.INT, Types.STRING))),
					new Literal(1, Types.INT)),
				"int1"),
			new Literal(1, Types.INT)
			);
		ArrayList<Expression> preds = new ArrayList<>();
		preds.add(pred1);
		preds.add(pred2);
		preds.add(pred3);

		// apply predicates on TableSource
		OrcTableSource projected = (OrcTableSource) orc.applyPredicate(preds);

		// ensure copy is returned
		assertTrue(orc != projected);

		// ensure table schema is identical
		assertEquals(orc.getTableSchema(), projected.getTableSchema());

		// ensure return type is identical
		assertEquals(
			Types.ROW_NAMED(getNestedFieldNames(), getNestedFieldTypes()),
			projected.getReturnType());

		// ensure IF is configured with supported predicates
		OrcTableSource spyTS = spy(projected);
		OrcRowInputFormat mockIF = mock(OrcRowInputFormat.class);
		doReturn(mockIF).when(spyTS).buildOrcInputFormat();
		spyTS.getDataSet(mock(ExecutionEnvironment.class));

		ArgumentCaptor<OrcRowInputFormat.Predicate> arguments = ArgumentCaptor.forClass(OrcRowInputFormat.Predicate.class);
		verify(mockIF, times(2)).addPredicate(arguments.capture());
		List<String> values = arguments.getAllValues().stream().map(Object::toString).collect(Collectors.toList());
		assertTrue(values.contains(
			new OrcRowInputFormat.Not(new OrcRowInputFormat.LessThanEquals("int1", PredicateLeaf.Type.LONG, 100)).toString()));
		assertTrue(values.contains(
			new OrcRowInputFormat.Equals("string1", PredicateLeaf.Type.STRING, "hello").toString()));

		// ensure filter pushdown is correct
		assertTrue(spyTS.isFilterPushedDown());
		assertFalse(orc.isFilterPushedDown());
	}

	private String getPath(String fileName) {
		return getClass().getClassLoader().getResource(fileName).getPath();
	}

	private String[] getNestedFieldNames() {
		return new String[] {
			"boolean1", "byte1", "short1", "int1", "long1", "float1", "double1", "bytes1", "string1", "middle", "list", "map"
		};
	}

	private TypeInformation[] getNestedFieldTypes() {
		return new TypeInformation[]{
			Types.BOOLEAN, Types.BYTE, Types.SHORT, Types.INT, Types.LONG, Types.FLOAT, Types.DOUBLE,
			PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO, Types.STRING,
			Types.ROW_NAMED(
				new String[]{"list"},
				ObjectArrayTypeInfo.getInfoFor(
					Types.ROW_NAMED(
						new String[]{"int1", "string1"},
						Types.INT, Types.STRING
					)
				)
			),
			ObjectArrayTypeInfo.getInfoFor(
				Types.ROW_NAMED(
					new String[]{"int1", "string1"},
					Types.INT, Types.STRING
				)
			),
			new MapTypeInfo<>(
				Types.STRING,
				Types.ROW_NAMED(
					new String[]{"int1", "string1"},
					Types.INT, Types.STRING
				)
			)
		};
	}

}
