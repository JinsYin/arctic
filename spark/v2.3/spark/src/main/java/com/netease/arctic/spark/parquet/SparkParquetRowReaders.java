/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.netease.arctic.spark.parquet;

import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Schema;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.parquet.ParquetValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders;
import org.apache.iceberg.parquet.ParquetValueReaders.FloatAsDoubleReader;
import org.apache.iceberg.parquet.ParquetValueReaders.IntAsLongReader;
import org.apache.iceberg.parquet.ParquetValueReaders.PrimitiveReader;
import org.apache.iceberg.parquet.ParquetValueReaders.RepeatedKeyValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders.RepeatedReader;
import org.apache.iceberg.parquet.ParquetValueReaders.ReusableEntry;
import org.apache.iceberg.parquet.ParquetValueReaders.StructReader;
import org.apache.iceberg.parquet.ParquetValueReaders.UnboxedReader;
import org.apache.iceberg.parquet.TypeWithSchemaVisitor;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Type.TypeID;
import org.apache.iceberg.types.Types;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.DecimalMetadata;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericRow;
import org.apache.spark.sql.catalyst.util.ArrayBasedMapData;
import org.apache.spark.sql.catalyst.util.ArrayData;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.catalyst.util.GenericArrayData;
import org.apache.spark.sql.catalyst.util.MapData;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.unsafe.types.CalendarInterval;
import org.apache.spark.unsafe.types.UTF8String;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.collection.mutable.ArrayBuffer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class SparkParquetRowReaders {
  private SparkParquetRowReaders() {
  }

  public static ParquetValueReader<Row> buildReader(Schema expectedSchema,
                                                    MessageType fileSchema) {
    return buildReader(expectedSchema, fileSchema, ImmutableMap.of());
  }

  @SuppressWarnings("unchecked")
  public static ParquetValueReader<Row> buildReader(Schema expectedSchema,
                                                    MessageType fileSchema,
                                                    Map<Integer, ?> idToConstant) {
    if (ParquetSchemaUtil.hasIds(fileSchema)) {
      return (ParquetValueReader<Row>)
          TypeWithSchemaVisitor.visit(expectedSchema.asStruct(), fileSchema,
              new ReadBuilder(fileSchema, idToConstant));
    } else {
      return (ParquetValueReader<Row>)
          TypeWithSchemaVisitor.visit(expectedSchema.asStruct(), fileSchema,
              new FallbackReadBuilder(fileSchema, idToConstant));
    }
  }

  private static class FallbackReadBuilder extends ReadBuilder {
    FallbackReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      super(type, idToConstant);
    }

    @Override
    public ParquetValueReader<?> message(Types.StructType expected, MessageType message,
                                         List<ParquetValueReader<?>> fieldReaders) {
      // the top level matches by ID, but the remaining IDs are missing
      return super.struct(expected, message, fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(Types.StructType ignored, GroupType struct,
                                        List<ParquetValueReader<?>> fieldReaders) {
      // the expected struct is ignored because nested fields are never found when the
      List<ParquetValueReader<?>> newFields = Lists.newArrayListWithExpectedSize(
          fieldReaders.size());
      List<Type> types = Lists.newArrayListWithExpectedSize(fieldReaders.size());
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = fields.get(i);
        int fieldD = type().getMaxDefinitionLevel(path(fieldType.getName())) - 1;
        newFields.add(ParquetValueReaders.option(fieldType, fieldD, fieldReaders.get(i)));
        types.add(fieldType);
      }

      return new RowReader(types, newFields);
    }
  }

  private static class ReadBuilder extends TypeWithSchemaVisitor<ParquetValueReader<?>> {
    private final MessageType type;
    private final Map<Integer, ?> idToConstant;

    ReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      this.type = type;
      this.idToConstant = idToConstant;
    }

    @Override
    public ParquetValueReader<?> message(Types.StructType expected, MessageType message,
                                         List<ParquetValueReader<?>> fieldReaders) {
      return struct(expected, message.asGroupType(), fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(Types.StructType expected, GroupType struct,
                                        List<ParquetValueReader<?>> fieldReaders) {
      // match the expected struct's order
      Map<Integer, ParquetValueReader<?>> readersById = Maps.newHashMap();
      Map<Integer, Type> typesById = Maps.newHashMap();
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = fields.get(i);
        int fieldD = type.getMaxDefinitionLevel(path(fieldType.getName())) - 1;
        if (fieldType.getId() != null) {
          int id = fieldType.getId().intValue();
          readersById.put(id, ParquetValueReaders.option(fieldType, fieldD, fieldReaders.get(i)));
          typesById.put(id, fieldType);
        }
      }

      List<Types.NestedField> expectedFields = expected != null ?
          expected.fields() : ImmutableList.of();
      List<ParquetValueReader<?>> reorderedFields = Lists.newArrayListWithExpectedSize(
          expectedFields.size());
      List<Type> types = Lists.newArrayListWithExpectedSize(expectedFields.size());
      for (Types.NestedField field : expectedFields) {
        int id = field.fieldId();
        if (idToConstant.containsKey(id)) {
          // containsKey is used because the constant may be null
          reorderedFields.add(ParquetValueReaders.constant(idToConstant.get(id)));
          types.add(null);
        } else if (id == MetadataColumns.ROW_POSITION.fieldId()) {
          reorderedFields.add(ParquetValueReaders.position());
          types.add(null);
        } else if (id == MetadataColumns.IS_DELETED.fieldId()) {
          reorderedFields.add(ParquetValueReaders.constant(false));
          types.add(null);
        } else {
          ParquetValueReader<?> reader = readersById.get(id);
          if (reader != null) {
            reorderedFields.add(reader);
            types.add(typesById.get(id));
          } else {
            reorderedFields.add(ParquetValueReaders.nulls());
            types.add(null);
          }
        }
      }

      return new RowReader(types, reorderedFields);
    }

    @Override
    public ParquetValueReader<?> list(Types.ListType expectedList, GroupType array,
                                      ParquetValueReader<?> elementReader) {
      GroupType repeated = array.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type elementType = repeated.getType(0);
      int elementD = type.getMaxDefinitionLevel(path(elementType.getName())) - 1;

      return new ArrayReaderScala<>(repeatedD, repeatedR,
          ParquetValueReaders.option(elementType, elementD, elementReader));
    }

    @Override
    public ParquetValueReader<?> map(Types.MapType expectedMap, GroupType map,
                                     ParquetValueReader<?> keyReader,
                                     ParquetValueReader<?> valueReader) {
      GroupType repeatedKeyValue = map.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type keyType = repeatedKeyValue.getType(0);
      int keyD = type.getMaxDefinitionLevel(path(keyType.getName())) - 1;
      Type valueType = repeatedKeyValue.getType(1);
      int valueD = type.getMaxDefinitionLevel(path(valueType.getName())) - 1;

      return new MapReaderScala<>(repeatedD, repeatedR,
          ParquetValueReaders.option(keyType, keyD, keyReader),
          ParquetValueReaders.option(valueType, valueD, valueReader));
    }

    @Override
    public ParquetValueReader<?> primitive(org.apache.iceberg.types.Type.PrimitiveType expected,
                                           PrimitiveType primitive) {
      ColumnDescriptor desc = type.getColumnDescription(currentPath());

      if (primitive.getOriginalType() != null) {
        switch (primitive.getOriginalType()) {
          case ENUM:
          case JSON:
          case UTF8:
            return new StringReader(desc);
          case INT_8:
          case INT_16:
          case INT_32:
            if (expected != null && expected.typeId() == Types.LongType.get().typeId()) {
              return new IntAsLongReader(desc);
            } else {
              return new UnboxedReader(desc);
            }
          case DATE:
          case INT_64:
          case TIMESTAMP_MICROS:
            return new UnboxedReader<>(desc);
          case TIMESTAMP_MILLIS:
            return new TimestampMillisReader(desc);
          case DECIMAL:
            DecimalMetadata decimal = primitive.getDecimalMetadata();
            switch (primitive.getPrimitiveTypeName()) {
              case BINARY:
              case FIXED_LEN_BYTE_ARRAY:
                return new BinaryDecimalReader(desc, decimal.getScale());
              case INT64:
                return new LongDecimalReader(desc, decimal.getPrecision(), decimal.getScale());
              case INT32:
                return new IntegerDecimalReader(desc, decimal.getPrecision(), decimal.getScale());
              default:
                throw new UnsupportedOperationException(
                    "Unsupported base type for decimal: " + primitive.getPrimitiveTypeName());
            }
          case BSON:
            return new ParquetValueReaders.ByteArrayReader(desc);
          default:
            throw new UnsupportedOperationException(
                "Unsupported logical type: " + primitive.getOriginalType());
        }
      }

      switch (primitive.getPrimitiveTypeName()) {
        case FIXED_LEN_BYTE_ARRAY:
        case BINARY:
          return new ParquetValueReaders.ByteArrayReader(desc);
        case INT32:
          if (expected != null && expected.typeId() == TypeID.LONG) {
            return new IntAsLongReader(desc);
          } else {
            return new UnboxedReader<>(desc);
          }
        case FLOAT:
          if (expected != null && expected.typeId() == TypeID.DOUBLE) {
            return new FloatAsDoubleReader(desc);
          } else {
            return new UnboxedReader<>(desc);
          }
        case BOOLEAN:
        case INT64:
        case DOUBLE:
          return new UnboxedReader<>(desc);
        case INT96:
          // Impala & Spark used to write timestamps as INT96 without a logical type. For backwards
          // compatibility we try to read INT96 as timestamps.
          return new TimestampInt96Reader(desc);
        default:
          throw new UnsupportedOperationException("Unsupported type: " + primitive);
      }
    }

    protected MessageType type() {
      return type;
    }
  }

  private static class BinaryDecimalReader extends PrimitiveReader<Decimal> {
    private final int scale;

    BinaryDecimalReader(ColumnDescriptor desc, int scale) {
      super(desc);
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      Binary binary = column.nextBinary();
      return Decimal.fromDecimal(new BigDecimal(new BigInteger(binary.getBytes()), scale));
    }
  }

  private static class IntegerDecimalReader extends PrimitiveReader<Decimal> {
    private final int precision;
    private final int scale;

    IntegerDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      return Decimal.apply(column.nextInteger(), precision, scale);
    }
  }

  private static class LongDecimalReader extends PrimitiveReader<Decimal> {
    private final int precision;
    private final int scale;

    LongDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      return Decimal.apply(column.nextLong(), precision, scale);
    }
  }

  private static class TimestampMillisReader extends UnboxedReader<Timestamp> {
    TimestampMillisReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public Timestamp read(Timestamp ignored) {
      return DateTimeUtils.toJavaTimestamp(readLong());
    }

    @Override
    public long readLong() {
      return 1000 * column.nextLong();
    }
  }

  private static class TimestampInt96Reader extends UnboxedReader<Timestamp> {
    private static final long UNIX_EPOCH_JULIAN = 2_440_588L;

    TimestampInt96Reader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public Timestamp read(Timestamp ignored) {
      return DateTimeUtils.toJavaTimestamp(readLong());
    }

    @Override
    public long readLong() {
      final ByteBuffer byteBuffer = column.nextBinary().toByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
      final long timeOfDayNanos = byteBuffer.getLong();
      final int julianDay = byteBuffer.getInt();

      return TimeUnit.DAYS.toMicros(julianDay - UNIX_EPOCH_JULIAN) +
          TimeUnit.NANOSECONDS.toMicros(timeOfDayNanos);
    }
  }

  private static class StringReader extends PrimitiveReader<String> {
    StringReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public String read(String reuse) {
      Binary binary = column.nextBinary();
      ByteBuffer buffer = binary.toByteBuffer();
      if (buffer.hasArray()) {
        return UTF8String.fromBytes(
            buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining()).toString();
      } else {
        return UTF8String.fromBytes(binary.getBytes()).toString();
      }
    }
  }

  private static class ArrayReaderScala<E> extends RepeatedReader<ArrayBuffer, ArrayBuffer, E> {

    private int readPos = 0;
    private int writePos = 0;

    protected ArrayReaderScala(int definitionLevel, int repetitionLevel, ParquetValueReader<E> reader) {
      super(definitionLevel, repetitionLevel, reader);
    }

    @Override
    protected ArrayBuffer newListData(ArrayBuffer reuse) {
      this.readPos = 0;
      this.writePos = 0;
      return new ArrayBuffer();
    }

    @Override
    protected E getElement(ArrayBuffer list) {
      E value = null;
      if (readPos < list.size()) {
        value = (E) list.apply(readPos);
      }

      readPos += 1;

      return value;
    }

    @Override
    protected void addElement(ArrayBuffer list, E element) {
      Seq seq = JavaConverters.asScalaIteratorConverter(Arrays.asList(element).iterator()).asScala().toSeq();
      list.insert(writePos, seq);

      writePos += 1;
    }

    @Override
    protected ArrayBuffer buildList(ArrayBuffer list) {
      return list;
    }


  }

  private static class MapReaderScala<K, V> extends
      RepeatedKeyValueReader<scala.collection.mutable.Map, scala.collection.mutable.Map, K, V> {
    private int readPos = 0;
    private int writePos = 0;

    private final ReusableEntry<K, V> entry = new ReusableEntry<>();
    private final ReusableEntry<K, V> nullEntry = new ReusableEntry<>();

    protected MapReaderScala(int definitionLevel, int repetitionLevel,
                             ParquetValueReader<K> keyReader, ParquetValueReader<V> valueReader) {
      super(definitionLevel, repetitionLevel, keyReader, valueReader);
    }

    @Override
    protected scala.collection.mutable.Map newMapData(scala.collection.mutable.Map reuse) {
      this.readPos = 0;
      this.writePos = 0;

      return new scala.collection.mutable.HashMap();
    }

    @Override
    protected Map.Entry<K, V> getPair(scala.collection.mutable.Map map) {
      Map.Entry<K, V> kv = nullEntry;
      if (readPos < map.size()) {
        entry.set((K) map.keys().toSeq().apply(readPos), (V) map.values().toSeq().apply(readPos));
        kv = entry;
      }

      readPos += 1;

      return kv;
    }

    @Override
    protected void addPair(scala.collection.mutable.Map map, K key, V value) {

      map.update(key, value);

      writePos += 1;
    }

    @Override
    protected scala.collection.mutable.Map buildMap(scala.collection.mutable.Map map) {
      return map;
    }
  }


  private static class RowReader extends StructReader<Row, GenericRow> {
    private final int numFields;

    RowReader(List<Type> types, List<ParquetValueReader<?>> readers) {
      super(types, readers);
      this.numFields = readers.size();
    }

    @Override
    protected GenericRow newStructData(Row reuse) {
      if (reuse instanceof GenericInternalRow) {
        return (GenericRow) reuse;
      } else {
        return new GenericRow(numFields);
      }
    }

    @Override
    protected Object getField(GenericRow intermediate, int pos) {
      return intermediate.get(pos);
    }

    @Override
    protected Row buildStruct(GenericRow struct) {
      return struct;
    }

    @Override
    protected void set(GenericRow struct, int pos, Object value) {
      Object[] values = struct.values();
      values[pos] = value;
      RowFactory.create(values);
    }

  }

  public static class ReusableMapData extends MapData {
    private final ReusableArrayData keys;
    private final ReusableArrayData values;
    private int numElements;

    private ReusableMapData() {
      this.keys = new ReusableArrayData();
      this.values = new ReusableArrayData();
    }

    private void grow() {
      keys.grow();
      values.grow();
    }

    private int capacity() {
      return keys.capacity();
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
      keys.setNumElements(numElements);
      values.setNumElements(numElements);
    }

    @Override
    public int numElements() {
      return numElements;
    }

    @Override
    public MapData copy() {
      return new ArrayBasedMapData(keyArray().copy(), valueArray().copy());
    }

    @Override
    public ReusableArrayData keyArray() {
      return keys;
    }

    @Override
    public ReusableArrayData valueArray() {
      return values;
    }
  }

  public static class ReusableArrayData extends ArrayData {
    private static final Object[] EMPTY = new Object[0];

    private Object[] values = EMPTY;
    private int numElements = 0;

    private void grow() {
      if (values.length == 0) {
        this.values = new Object[20];
      } else {
        Object[] old = values;
        this.values = new Object[old.length << 2];
        // copy the old array in case it has values that can be reused
        System.arraycopy(old, 0, values, 0, old.length);
      }
    }

    private int capacity() {
      return values.length;
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
    }

    @Override
    public Object get(int ordinal, DataType dataType) {
      return values[ordinal];
    }

    @Override
    public int numElements() {
      return numElements;
    }

    @Override
    public ArrayData copy() {
      return new GenericArrayData(array());
    }

    @Override
    public Object[] array() {
      return Arrays.copyOfRange(values, 0, numElements);
    }

    @Override
    public void setNullAt(int i) {
      values[i] = null;
    }

    @Override
    public void update(int ordinal, Object value) {
      values[ordinal] = value;
    }

    @Override
    public boolean isNullAt(int ordinal) {
      return null == values[ordinal];
    }

    @Override
    public boolean getBoolean(int ordinal) {
      return (boolean) values[ordinal];
    }

    @Override
    public byte getByte(int ordinal) {
      return (byte) values[ordinal];
    }

    @Override
    public short getShort(int ordinal) {
      return (short) values[ordinal];
    }

    @Override
    public int getInt(int ordinal) {
      return (int) values[ordinal];
    }

    @Override
    public long getLong(int ordinal) {
      return (long) values[ordinal];
    }

    @Override
    public float getFloat(int ordinal) {
      return (float) values[ordinal];
    }

    @Override
    public double getDouble(int ordinal) {
      return (double) values[ordinal];
    }

    @Override
    public Decimal getDecimal(int ordinal, int precision, int scale) {
      return (Decimal) values[ordinal];
    }

    @Override
    public UTF8String getUTF8String(int ordinal) {
      return (UTF8String) values[ordinal];
    }

    @Override
    public byte[] getBinary(int ordinal) {
      return (byte[]) values[ordinal];
    }

    @Override
    public CalendarInterval getInterval(int ordinal) {
      return (CalendarInterval) values[ordinal];
    }

    @Override
    public InternalRow getStruct(int ordinal, int numFields) {
      return (InternalRow) values[ordinal];
    }

    @Override
    public ArrayData getArray(int ordinal) {
      return (ArrayData) values[ordinal];
    }

    @Override
    public MapData getMap(int ordinal) {
      return (MapData) values[ordinal];
    }
  }
}
