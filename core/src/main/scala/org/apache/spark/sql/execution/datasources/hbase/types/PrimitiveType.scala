/*
 * (C) 2017 Hortonworks, Inc. All rights reserved. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. This file is licensed to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.hbase.types

import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.sql.execution.datasources.hbase._

class PrimitiveType(f:Option[Field] = None) extends SHCDataType {

  private def fromBytes(src: HBaseType, dt: DataType): Any  = {
    if (src == null || src.length == 0)  {
      null
    } else {
      try {
        dt match {
          case BooleanType => toBoolean(src)
          case ByteType => src(0)
          case DoubleType => Bytes.toDouble(src)
          case FloatType => Bytes.toFloat(src)
          case IntegerType => Bytes.toInt(src)
          case LongType => Bytes.toLong(src)
          case ShortType => Bytes.toShort(src)
          case StringType => toUTF8String(src, src.length)
          case BinaryType => src
          // this block MapType in future if connector want to support it
          case m: MapType => fromBytes(src, m.valueType)
          case _ => {
            if(dt.typeName.toLowerCase.startsWith("decimal")){
              castDecimal(src, dt)
            }else {
              throw new UnsupportedOperationException(s"unsupported data type ${f.get.dt} ${dt.typeName}")
            }
          }
        }
      }catch {
        case ex: Exception => {
          throw new RuntimeException("hbase convert exception" + " " + new String(src), ex);
        }
      }
    }
  }

  private def castDecimal(src: HBaseType, dt: DataType): Any = {
    try{
      Decimal.apply(Bytes.toString(src)) //现在decimal在hbase统一用字符串存
    }catch {
      case e : Exception => {
        //兼容原来用double存储的类型
        val typeInJson = dt.typeName.toLowerCase;
        val begin = typeInJson.indexOf("(")
        val end = typeInJson.indexOf(")")
        val sep = typeInJson.indexOf(",")
        if(begin > 0 && end > 0 && sep > 0){
          val precision =  typeInJson.substring(begin + 1, sep).trim.toInt
          val scale = typeInJson.substring(sep+1, end).trim.toInt
          if(scale == 0){
            if(precision < 11){
              Decimal.apply(Bytes.toInt(src))
            }else {
              Decimal.apply(Bytes.toLong(src))
            }
          }else{
            Decimal.apply(Bytes.toDouble(src))
          }
        }else{
          Decimal.apply(Bytes.toDouble(src))
        }
      }
    }
  }

  def fromBytes(src: HBaseType): Any = {
    if (f.isDefined) {
      fromBytes(src, f.get.dt)
    } else {
      throw new UnsupportedOperationException(
        "PrimitiveType coder: without field metadata, " +
          "'fromBytes' conversion can not be supported")
    }
  }

  def toBytes(input: Any): Array[Byte] = {
    input match {
      case data: Boolean => Bytes.toBytes(data)
      case data: Byte => Array(data)
      case data: Array[Byte] => data
      case data: Double => Bytes.toBytes(data)
      case data: Float => Bytes.toBytes(data)
      case data: java.math.BigDecimal => Bytes.toBytes(data.toString)
      case data: Int => Bytes.toBytes(data)
      case data: Long => Bytes.toBytes(data)
      case data: Short => Bytes.toBytes(data)
      case data: UTF8String => data.getBytes
      case data: String => Bytes.toBytes(data)
      case null => "".getBytes
      case _ => throw new
          UnsupportedOperationException(s"PrimitiveType coder: unsupported data type $input ${input.getClass.toGenericString}")
    }
  }

  override def isRowKeySupported(): Boolean = true

  override def isCompositeKeySupported(): Boolean = true

  override def decodeCompositeRowKey(row: Array[Byte], keyFields: Seq[Field]): Map[Field, Any] = {
    keyFields.foldLeft((0, Seq[(Field, Any)]()))((state, field) => {
      val idx = state._1
      val parsed = state._2
      if (field.length != -1) {
        val value = fromBytes(field, row, idx, field.length)
        // Return the new index and appended value
        (idx + field.length, parsed ++ Seq((field, value)))
      } else {
        // This is the last dimension.
        val value = fromBytes(field, row, idx, row.length - idx)
        (row.length + 1, parsed ++ Seq((field, value)))
      }
    })._2.toMap
  }

  private def fromBytes(field: Field, src: HBaseType, offset: Int, length: Int): Any = {
    field.dt match {
      case BooleanType => toBoolean(src, offset)
      case ByteType => src(offset)
      case DoubleType => Bytes.toDouble(src, offset)
      case FloatType => Bytes.toFloat(src, offset)
      case IntegerType => Bytes.toInt(src, offset)
      case LongType => Bytes.toLong(src, offset)
      case ShortType => Bytes.toShort(src, offset)
      case StringType => toUTF8String(src, length, offset)
      case BinaryType =>
        val newArray = new Array[Byte](length)
        System.arraycopy(src, offset, newArray, 0, length)
        newArray
      case _ =>{
        if(field.dt.typeName.toLowerCase.startsWith("decimal")){
          castDecimal(src, field.dt)
        }else{
          throw new
              UnsupportedOperationException(s"PrimitiveType coder: unsupported data type ${field.dt} ${field.dt.typeName}")
        }
      }
    }
  }

  override def encodeCompositeRowKey(rkIdxedFields:Seq[(Int, Field)], row: Row): Seq[Array[Byte]] = {
    rkIdxedFields.map { case (x, y) =>
      SHCDataTypeFactory.create(y).toBytes(row(x))
    }
  }

  private def toBoolean(input: HBaseType, offset: Int = 0): Boolean = {
    input(offset) != 0
  }

  private def toUTF8String(input: HBaseType, length: Int, offset: Int = 0): UTF8String = {
    UTF8String.fromBytes(input.slice(offset, offset + length))
  }
}
