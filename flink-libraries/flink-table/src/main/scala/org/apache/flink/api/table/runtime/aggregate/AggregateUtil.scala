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
package org.apache.flink.api.table.runtime.aggregate

import java.util

import org.apache.calcite.rel.`type`._
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.`type`.SqlTypeName._
import org.apache.calcite.sql.`type`.{SqlTypeFactoryImpl, SqlTypeName}
import org.apache.calcite.sql.fun._
import org.apache.flink.api.common.functions.{GroupReduceFunction, MapFunction}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.table.typeutils.TypeConverter
import org.apache.flink.api.table.typeutils.RowTypeInfo
import org.apache.flink.api.table.{TableException, Row, TableConfig}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

object AggregateUtil {

  type CalcitePair[T, R] = org.apache.calcite.util.Pair[T, R]
  type JavaList[T] = java.util.List[T]

  /**
   * Create Flink operator functions for aggregates. It includes 2 implementations of Flink 
   * operator functions:
   * [[org.apache.flink.api.common.functions.MapFunction]] and 
   * [[org.apache.flink.api.common.functions.GroupReduceFunction]](if it's partial aggregate,
   * should also implement [[org.apache.flink.api.common.functions.CombineFunction]] as well). 
   * The output of [[org.apache.flink.api.common.functions.MapFunction]] contains the 
   * intermediate aggregate values of all aggregate function, it's stored in Row by the following
   * format:
   *
   * {{{
   *                   avg(x) aggOffsetInRow = 2          count(z) aggOffsetInRow = 5
   *                             |                          |
   *                             v                          v
   *        +---------+---------+--------+--------+--------+--------+
   *        |groupKey1|groupKey2|  sum1  | count1 |  sum2  | count2 |
   *        +---------+---------+--------+--------+--------+--------+
   *                                              ^
   *                                              |
   *                               sum(y) aggOffsetInRow = 4
   * }}}
   *
   */
  def createOperatorFunctionsForAggregates(namedAggregates: Seq[CalcitePair[AggregateCall, String]],
      inputType: RelDataType, outputType: RelDataType,
      groupings: Array[Int],
      config: TableConfig): (MapFunction[Any, Row], GroupReduceFunction[Row, Row] ) = {

    val aggregateFunctionsAndFieldIndexes =
      transformToAggregateFunctions(namedAggregates.map(_.getKey), inputType, groupings.length)
    // store the aggregate fields of each aggregate function, by the same order of aggregates.
    val aggFieldIndexes = aggregateFunctionsAndFieldIndexes._1
    val aggregates = aggregateFunctionsAndFieldIndexes._2

    val mapReturnType: RowTypeInfo =
      createAggregateBufferDataType(groupings, aggregates, inputType)

    val mapFunction = new AggregateMapFunction[Row, Row](
        aggregates, aggFieldIndexes, groupings,
        mapReturnType.asInstanceOf[RowTypeInfo]).asInstanceOf[MapFunction[Any, Row]]

    // the mapping relation between field index of intermediate aggregate Row and output Row.
    val groupingOffsetMapping = getGroupKeysMapping(inputType, outputType, groupings)

    // the mapping relation between aggregate function index in list and its corresponding
    // field index in output Row.
    val aggOffsetMapping = getAggregateMapping(namedAggregates, outputType)

    if (groupingOffsetMapping.length != groupings.length ||
        aggOffsetMapping.length != namedAggregates.length) {
      throw new TableException("Could not find output field in input data type " +
          "or aggregate functions.")
    }

    val allPartialAggregate = aggregates.map(_.supportPartial).forall(x => x)

    val intermediateRowArity = groupings.length + aggregates.map(_.intermediateDataType.length).sum

    val reduceGroupFunction =
      if (allPartialAggregate) {
        new AggregateReduceCombineFunction(aggregates, groupingOffsetMapping,
          aggOffsetMapping, intermediateRowArity)
      }
      else {
        new AggregateReduceGroupFunction(aggregates, groupingOffsetMapping,
          aggOffsetMapping, intermediateRowArity)
      }

    (mapFunction, reduceGroupFunction)
  }

  private def transformToAggregateFunctions(
      aggregateCalls: Seq[AggregateCall],
      inputType: RelDataType,
      groupKeysCount: Int): (Array[Int], Array[Aggregate[_ <: Any]]) = {

    // store the aggregate fields of each aggregate function, by the same order of aggregates.
    val aggFieldIndexes = new Array[Int](aggregateCalls.size)
    val aggregates = new Array[Aggregate[_ <: Any]](aggregateCalls.size)

    // set the start offset of aggregate buffer value to group keys' length, 
    // as all the group keys would be moved to the start fields of intermediate
    // aggregate data.
    var aggOffset = groupKeysCount

    // create aggregate function instances by function type and aggregate field data type.
    aggregateCalls.zipWithIndex.foreach { case (aggregateCall, index) =>
      val argList: util.List[Integer] = aggregateCall.getArgList
      if (argList.isEmpty) {
        if (aggregateCall.getAggregation.isInstanceOf[SqlCountAggFunction]) {
          aggFieldIndexes(index) = 0
        } else {
          throw new TableException("Aggregate fields should not be empty.")
        }
      } else {
        if (argList.size() > 1) {
          throw new TableException("Currently, do not support aggregate on multi fields.")
        }
        aggFieldIndexes(index) = argList.get(0)
      }
      val sqlTypeName = inputType.getFieldList.get(aggFieldIndexes(index)).getType.getSqlTypeName
      aggregateCall.getAggregation match {
        case _: SqlSumAggFunction | _: SqlSumEmptyIsZeroAggFunction => {
          aggregates(index) = sqlTypeName match {
            case TINYINT =>
              new ByteSumAggregate
            case SMALLINT =>
              new ShortSumAggregate
            case INTEGER =>
              new IntSumAggregate
            case BIGINT =>
              new LongSumAggregate
            case FLOAT =>
              new FloatSumAggregate
            case DOUBLE =>
              new DoubleSumAggregate
            case DECIMAL =>
              new DecimalSumAggregate
            case sqlType: SqlTypeName =>
              throw new TableException("Sum aggregate does no support type:" + sqlType)
          }
        }
        case _: SqlAvgAggFunction => {
          aggregates(index) = sqlTypeName match {
            case TINYINT =>
               new ByteAvgAggregate
            case SMALLINT =>
              new ShortAvgAggregate
            case INTEGER =>
              new IntAvgAggregate
            case BIGINT =>
              new LongAvgAggregate
            case FLOAT =>
              new FloatAvgAggregate
            case DOUBLE =>
              new DoubleAvgAggregate
            case DECIMAL =>
              new DecimalAvgAggregate
            case sqlType: SqlTypeName =>
              throw new TableException("Avg aggregate does no support type:" + sqlType)
          }
        }
        case sqlMinMaxFunction: SqlMinMaxAggFunction => {
          aggregates(index) = if (sqlMinMaxFunction.isMin) {
            sqlTypeName match {
              case TINYINT =>
                new ByteMinAggregate
              case SMALLINT =>
                new ShortMinAggregate
              case INTEGER =>
                new IntMinAggregate
              case BIGINT =>
                new LongMinAggregate
              case FLOAT =>
                new FloatMinAggregate
              case DOUBLE =>
                new DoubleMinAggregate
              case DECIMAL =>
                new DecimalMinAggregate
              case BOOLEAN =>
                new BooleanMinAggregate
              case sqlType: SqlTypeName =>
                throw new TableException("Min aggregate does no support type:" + sqlType)
            }
          } else {
            sqlTypeName match {
              case TINYINT =>
                new ByteMaxAggregate
              case SMALLINT =>
                new ShortMaxAggregate
              case INTEGER =>
                new IntMaxAggregate
              case BIGINT =>
                new LongMaxAggregate
              case FLOAT =>
                new FloatMaxAggregate
              case DOUBLE =>
                new DoubleMaxAggregate
              case DECIMAL =>
                new DecimalMaxAggregate
              case BOOLEAN =>
                new BooleanMaxAggregate
              case sqlType: SqlTypeName =>
                throw new TableException("Max aggregate does no support type:" + sqlType)
            }
          }
        }
        case _: SqlCountAggFunction =>
          aggregates(index) = new CountAggregate
        case unSupported: SqlAggFunction =>
          throw new TableException("unsupported Function: " + unSupported.getName)
      }
      setAggregateDataOffset(index)
    }

    // set the aggregate intermediate data start index in Row, and update current value.
    def setAggregateDataOffset(index: Int): Unit = {
      aggregates(index).setAggOffsetInRow(aggOffset)
      aggOffset += aggregates(index).intermediateDataType.length
    }

    (aggFieldIndexes, aggregates)
  }

  private def createAggregateBufferDataType(
      groupings: Array[Int],
      aggregates: Array[Aggregate[_]],
      inputType: RelDataType): RowTypeInfo = {

    // get the field data types of group keys.
    val groupingTypes: Seq[TypeInformation[_]] = groupings
      .map(inputType.getFieldList.get(_).getType.getSqlTypeName)
      .map(TypeConverter.sqlTypeToTypeInfo)

    val aggPartialNameSuffix = "agg_buffer_"
    val factory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)

    // get all field data types of all intermediate aggregates
    val aggTypes: Seq[TypeInformation[_]] = aggregates.flatMap(_.intermediateDataType)

    // concat group key types and aggregation types
    val allFieldTypes = groupingTypes ++: aggTypes
    val partialType = new RowTypeInfo(allFieldTypes)
    partialType
  }

  // Find the mapping between the index of aggregate list and aggregated value index in output Row.
  private def getAggregateMapping(namedAggregates: Seq[CalcitePair[AggregateCall, String]],
      outputType: RelDataType): Array[(Int, Int)] = {

    // the mapping relation between aggregate function index in list and its corresponding
    // field index in output Row.
    var aggOffsetMapping = ArrayBuffer[(Int, Int)]()

    outputType.getFieldList.zipWithIndex.foreach{
      case (outputFieldType, outputIndex) =>
        namedAggregates.zipWithIndex.foreach {
          case (namedAggCall, aggregateIndex) =>
            if (namedAggCall.getValue.equals(outputFieldType.getName) &&
                namedAggCall.getKey.getType.equals(outputFieldType.getType)) {
              aggOffsetMapping += ((outputIndex, aggregateIndex))
            }
        }
    }
   
    aggOffsetMapping.toArray
  }

  // Find the mapping between the index of group key in intermediate aggregate Row and its index
  // in output Row.
  private def getGroupKeysMapping(inputDatType: RelDataType,
      outputType: RelDataType, groupKeys: Array[Int]): Array[(Int, Int)] = {

    // the mapping relation between field index of intermediate aggregate Row and output Row.
    var groupingOffsetMapping = ArrayBuffer[(Int, Int)]()

    outputType.getFieldList.zipWithIndex.foreach {
      case (outputFieldType, outputIndex) =>
        inputDatType.getFieldList.zipWithIndex.foreach {
          // find the field index in input data type.
          case (inputFieldType, inputIndex) =>
            if (outputFieldType.getName.equals(inputFieldType.getName) &&
                outputFieldType.getType.equals(inputFieldType.getType)) {
              // as aggregated field in output data type would not have a matched field in
              // input data, so if inputIndex is not -1, it must be a group key. Then we can
              // find the field index in buffer data by the group keys index mapping between
              // input data and buffer data.
              for (i <- 0 until groupKeys.length) {
                if (inputIndex == groupKeys(i)) {
                  groupingOffsetMapping += ((outputIndex, i))
                }
              }
            }
        }
    }

    groupingOffsetMapping.toArray
  }
}

