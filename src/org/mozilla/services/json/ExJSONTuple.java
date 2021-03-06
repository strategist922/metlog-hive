/*
***** BEGIN LICENSE BLOCK *****

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this file,
You can obtain one at http://mozilla.org/MPL/2.0/.

The Initial Developer of the Original Code is the Mozilla Foundation.
Portions created by the Initial Developer are Copyright (C) 2012
the Initial Developer. All Rights Reserved.

Contributor(s):
    Victor Ng (vng@mozilla.com)

***** END LICENSE BLOCK *****
*/

package org.mozilla.services.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde.Constants;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.io.Text;
import org.json.simple.JSONObject;


/**
 * ExJSONTuple: this
 *
 */
@Description(name = "exjson_tuple",
    value = "_FUNC_(jsonStr, p1, p2, ..., pn) - like get_json_object, but it takes multiple names and return a tuple. " +
            "All the input parameters and output column types are string.")

public class ExJSONTuple extends GenericUDTF {

  private static Log LOG = LogFactory.getLog(ExJSONTuple.class.getName());

  int numCols;    // number of output columns
  String[] paths; // array of path expressions, each of which corresponds to a column
  public Text[] retCols; // array of returned column values
  Text[] cols;    // object pool of non-null Text, avoid creating objects all the time
  Object[] nullCols; // array of null column values
  ObjectInspector[] inputOIs; // input ObjectInspectors
  boolean pathParsed = false;
  boolean seenErrors = false;

  //An LRU cache using a linked hash map
  static class HashCache<K, V> extends LinkedHashMap<K, V> {

    private static final int CACHE_SIZE = 16;
    private static final int INIT_SIZE = 32;
    private static final float LOAD_FACTOR = 0.6f;

    HashCache() {
      super(INIT_SIZE, LOAD_FACTOR);
    }

    private static final long serialVersionUID = 1;

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > CACHE_SIZE;
    }

  }

  static Map<String, FastPath> jsonObjectCache = new HashCache<String, FastPath>();

  @Override
  public void close() throws HiveException {
  }

  @Override
  public StructObjectInspector initialize(ObjectInspector[] args)
      throws UDFArgumentException {

    inputOIs = args;
    numCols = args.length - 1;

    if (numCols < 1) {
      throw new UDFArgumentException("exjson_tuple() takes at least two arguments: " +
              "the json string and a path expression");
    }

    for (int i = 0; i < args.length; ++i) {
      if (args[i].getCategory() != ObjectInspector.Category.PRIMITIVE ||
          !args[i].getTypeName().equals(Constants.STRING_TYPE_NAME)) {
        throw new UDFArgumentException("exjson_tuple()'s arguments have to be string type");
      }
    }

    seenErrors = false;
    pathParsed = false;
    paths = new String[numCols];
    cols = new Text[numCols];
    retCols = new Text[numCols];
    LOG.warn("setting up a new retCols array of size: " + numCols);
    nullCols = new Object[numCols];

    for (int i = 0; i < numCols; ++i) {
      cols[i] = new Text();
      retCols[i] = cols[i];
      nullCols[i] = null;
    }

    // construct output object inspector
    ArrayList<String> fieldNames = new ArrayList<String>(numCols);
    ArrayList<ObjectInspector> fieldOIs = new ArrayList<ObjectInspector>(numCols);
    for (int i = 0; i < numCols; ++i) {
      // column name can be anything since it will be named by UDTF as clause
      fieldNames.add("c" + i);
      // all returned type will be Text
      fieldOIs.add(PrimitiveObjectInspectorFactory.writableStringObjectInspector);
    }
    return ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldOIs);
  }

  @Override
  public void process(Object[] o) throws HiveException {

    if (o[0] == null) {
      forward(nullCols);
      return;
    }
    // get the path expression for the 1st row only
    if (!pathParsed) {
      for (int i = 0;i < numCols; ++i) {
        paths[i] = ((StringObjectInspector) inputOIs[i+1]).getPrimitiveJavaObject(o[i+1]);
      }
      pathParsed = true;
    }

    String jsonStr = ((StringObjectInspector) inputOIs[0]).getPrimitiveJavaObject(o[0]);
    if (jsonStr == null) {
      forward(nullCols);
      return;
    }
    try {
      FastPath fastpath = jsonObjectCache.get(jsonStr);
      if (fastpath == null) {
        fastpath = new FastPath(jsonStr);
        jsonObjectCache.put(jsonStr, fastpath);
      }

      for (int i = 0; i < numCols; ++i) {
          if (fastpath.get(paths[i]) == null) {
              retCols[i] = null;
          } else {
              if (retCols[i] == null) {
                  retCols[i] = cols[i]; // use the object pool rather than creating a new object
              }
              retCols[i].set(fastpath.get(paths[i]));
              LOG.warn("adding string value ["+retCols[i]+"] @ index: " + i);
          }
      }
      forward(retCols);
      return;
    } catch (Throwable e) {
      LOG.error("JSON parsing/evaluation exception" + e);
      forward(nullCols);
    }
  }

  @Override
  public String toString() {
    return "exjson_tuple";
  }
}
