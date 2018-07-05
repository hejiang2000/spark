/**
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

package cn.gbase.hejiang;

import java.beans.DefaultPersistenceDelegate;
import java.beans.Encoder;
import java.beans.Expression;
import java.beans.Statement;
import java.beans.XMLDecoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Iterator;

import org.antlr.runtime.CommonToken;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.ColumnInfo;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.plan.AbstractOperatorDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.ReduceWork;
import org.apache.hadoop.hive.ql.plan.SparkEdgeProperty;
import org.apache.hadoop.hive.ql.plan.SparkWork;
import org.apache.hadoop.hive.ql.plan.TableDesc;
//import com.esotericsoftware.shaded.org.objenesis.strategy.StdInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;

/**
 * KryoUtils.
 *
 */
public final class KryoUtils {

  private KryoUtils() {
    // prevent instantiation
  }

  /**
   * Serializes expression via Kryo.
   *
   * @param expr
   *            Expression.
   * @return Bytes.
   */
  public static byte[] serializeExpressionToKryo(ExprNodeGenericFuncDesc expr) {
    return serializeObjectToKryo(expr);
  }

  /**
   * Deserializes expression from Kryo.
   *
   * @param bytes
   *            Bytes containing the expression.
   * @return Expression; null if deserialization succeeded, but the result type is
   *         incorrect.
   */
  public static ExprNodeGenericFuncDesc deserializeExpressionFromKryo(byte[] bytes) {
    return deserializeObjectFromKryo(bytes, ExprNodeGenericFuncDesc.class);
  }

  public static String serializeExpression(ExprNodeGenericFuncDesc expr) {
    try {
      return new String(Base64.encodeBase64(serializeExpressionToKryo(expr)), "UTF-8");
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException("UTF-8 support required", ex);
    }
  }

  public static ExprNodeGenericFuncDesc deserializeExpression(String s) {
    byte[] bytes;
    try {
      bytes = Base64.decodeBase64(s.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException("UTF-8 support required", ex);
    }
    return deserializeExpressionFromKryo(bytes);
  }

  private static byte[] serializeObjectToKryo(Serializable object) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Output output = new Output(baos);
    runtimeSerializationKryo.get().writeObject(output, object);
    output.close();
    return baos.toByteArray();
  }

  private static <T extends Serializable> T deserializeObjectFromKryo(byte[] bytes,
        Class<T> clazz) {
    Input inp = new Input(new ByteArrayInputStream(bytes));
    T func = runtimeSerializationKryo.get().readObject(inp, clazz);
    inp.close();
    return func;
  }

  public static String serializeObject(Serializable expr) {
    try {
      return new String(Base64.encodeBase64(serializeObjectToKryo(expr)), "UTF-8");
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException("UTF-8 support required", ex);
    }
  }

  public static <T extends Serializable> T deserializeObject(String s, Class<T> clazz) {
    try {
      return deserializeObjectFromKryo(Base64.decodeBase64(s.getBytes("UTF-8")), clazz);
    } catch (UnsupportedEncodingException ex) {
      throw new RuntimeException("UTF-8 support required", ex);
    }
  }

  public static class CollectionPersistenceDelegate extends DefaultPersistenceDelegate {
    @Override
    protected Expression instantiate(Object oldInstance, Encoder out) {
      return new Expression(oldInstance, oldInstance.getClass(), "new", null);
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected void initialize(Class type, Object oldInstance, Object newInstance, Encoder out) {
      Iterator ite = ((Collection) oldInstance).iterator();
      while (ite.hasNext()) {
        out.writeStatement(new Statement(oldInstance, "add", new Object[] { ite.next() }));
      }
    }
  }

  /**
   * Kryo serializer for timestamp.
   */
  private static class TimestampSerializer extends com.esotericsoftware.kryo.Serializer<Timestamp> {

    @Override
    public Timestamp read(Kryo kryo, Input input, Class<Timestamp> clazz) {
      Timestamp ts = new Timestamp(input.readLong());
      ts.setNanos(input.readInt());
      return ts;
    }

    @Override
    public void write(Kryo kryo, Output output, Timestamp ts) {
      output.writeLong(ts.getTime());
      output.writeInt(ts.getNanos());
    }
  }

  /**
   * Custom Kryo serializer for sql date, otherwise Kryo gets confused between
   * java.sql.Date and java.util.Date while deserializing
   */
  private static class SqlDateSerializer extends
      com.esotericsoftware.kryo.Serializer<java.sql.Date> {

    @Override
    public java.sql.Date read(Kryo kryo, Input input, Class<java.sql.Date> clazz) {
      return new java.sql.Date(input.readLong());
    }

    @Override
    public void write(Kryo kryo, Output output, java.sql.Date sqlDate) {
      output.writeLong(sqlDate.getTime());
    }
  }

  private static class CommonTokenSerializer extends
      com.esotericsoftware.kryo.Serializer<CommonToken> {
    @Override
    public CommonToken read(Kryo kryo, Input input, Class<CommonToken> clazz) {
      return new CommonToken(input.readInt(), input.readString());
    }

    @Override
    public void write(Kryo kryo, Output output, CommonToken token) {
      output.writeInt(token.getType());
      output.writeString(token.getText());
    }
  }

  private static class PathSerializer extends com.esotericsoftware.kryo.Serializer<Path> {

    @Override
    public void write(Kryo kryo, Output output, Path path) {
      output.writeString(path.toUri().toString());
    }

    @Override
    public Path read(Kryo kryo, Input input, Class<Path> type) {
      return new Path(URI.create(input.readString()));
    }
  }

  /**
   * @param plan
   *            Usually of type MapredWork, MapredLocalWork etc.
   * @param out
   *            stream in which serialized plan is written into
   */
  public static void serializeObjectByKryo(Kryo kryo, Object plan, OutputStream out) {
    Output output = new Output(out);
    kryo.writeObject(output, plan);
    output.close();
  }

  /**
   * De-serialize an object. This helper function mainly makes sure that enums,
   * counters, etc are handled properly.
   */
  @SuppressWarnings("unchecked")
  public static <T> T deserializeObjectByJavaXML(InputStream in) {
    XMLDecoder d = null;
    try {
      d = new XMLDecoder(in, null, null);
      return (T) d.readObject();
    } finally {
      if (null != d) {
        d.close();
      }
    }
  }

  public static <T> T deserializeObjectByKryo(Kryo kryo, InputStream in, Class<T> clazz) {
    Input inp = new Input(in);
    T t = kryo.readObject(inp, clazz);
    inp.close();
    return t;
  }

  // Kryo is not thread-safe,
  // Also new Kryo() is expensive, so we want to do it just once.
  public static ThreadLocal<Kryo> runtimeSerializationKryo = new ThreadLocal<Kryo>() {
    @Override
    protected synchronized Kryo initialValue() {
      Kryo kryo = new Kryo();
      kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
      kryo.register(java.sql.Date.class, new SqlDateSerializer());
      kryo.register(java.sql.Timestamp.class, new TimestampSerializer());
      kryo.register(Path.class, new PathSerializer());
      kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
      removeField(kryo, Operator.class, "colExprMap");
      removeField(kryo, ColumnInfo.class, "objectInspector");
      removeField(kryo, AbstractOperatorDesc.class, "statistics");
      return kryo;
    };
  };

  @SuppressWarnings("rawtypes")
  protected static void removeField(Kryo kryo, Class type, String fieldName) {
    FieldSerializer fld = new FieldSerializer(kryo, type);
    fld.removeField(fieldName);
    kryo.register(type, fld);
  }

  public static ThreadLocal<Kryo> sparkSerializationKryo = new ThreadLocal<Kryo>() {
    @Override
    protected synchronized Kryo initialValue() {
      Kryo kryo = new Kryo();
      kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
      kryo.register(java.sql.Date.class, new SqlDateSerializer());
      kryo.register(java.sql.Timestamp.class, new TimestampSerializer());
      kryo.register(Path.class, new PathSerializer());
      kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
      removeField(kryo, Operator.class, "colExprMap");
      removeField(kryo, ColumnInfo.class, "objectInspector");
      kryo.register(SparkEdgeProperty.class);
      kryo.register(MapWork.class);
      kryo.register(ReduceWork.class);
      kryo.register(SparkWork.class);
      kryo.register(TableDesc.class);
      kryo.register(Pair.class);
      return kryo;
    }
  };

}
