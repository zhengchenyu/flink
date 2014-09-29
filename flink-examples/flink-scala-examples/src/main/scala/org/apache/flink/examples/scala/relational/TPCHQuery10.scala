/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.examples.scala.relational

import org.apache.flink.api.scala._
import org.apache.flink.util.Collector

import org.apache.flink.api.java.aggregation.Aggregations

/**
 * This program implements a modified version of the TPC-H query 10. 
 * 
 * The original query can be found at
 * [http://www.tpc.org/tpch/spec/tpch2.16.0.pdf](http://www.tpc.org/tpch/spec/tpch2.16.0.pdf)
 * (page 45).
 *
 * This program implements the following SQL equivalent:
 *
 * {{{
 * SELECT 
 *        c_custkey,
 *        c_name, 
 *        c_address,
 *        n_name, 
 *        c_acctbal
 *        SUM(l_extendedprice * (1 - l_discount)) AS revenue,  
 * FROM   
 *        customer, 
 *        orders, 
 *        lineitem, 
 *        nation 
 * WHERE 
 *        c_custkey = o_custkey 
 *        AND l_orderkey = o_orderkey 
 *        AND YEAR(o_orderdate) > '1990' 
 *        AND l_returnflag = 'R' 
 *        AND c_nationkey = n_nationkey 
 * GROUP BY 
 *        c_custkey, 
 *        c_name, 
 *        c_acctbal, 
 *        n_name, 
 *        c_address
 * }}}
 *
 * Compared to the original TPC-H query this version does not print 
 * c_phone and c_comment, only filters by years greater than 1990 instead of
 * a period of 3 months, and does not sort the result by revenue..
 *
 * Input files are plain text CSV files using the pipe character ('|') as field separator 
 * as generated by the TPC-H data generator which is available at 
 * [http://www.tpc.org/tpch/](a href="http://www.tpc.org/tpch/).
 *
 * Usage: 
 * {{{
 *TPCHQuery10 <customer-csv path> <orders-csv path> <lineitem-csv path> <nation path> <result path>
 * }}}
 *  
 * This example shows how to use:
 *  - tuple data types
 *  - build-in aggregation functions
 *  - join with size hints
 *  
 */
object TPCHQuery10 {

  def main(args: Array[String]) {
    if (!parseParameters(args)) {
      return
    }

    // get execution environment
    val env = ExecutionEnvironment.getExecutionEnvironment

    // get customer data set: (custkey, name, address, nationkey, acctbal) 
    val customers = getCustomerDataSet(env)
    // get orders data set: (orderkey, custkey, orderdate)
    val orders = getOrdersDataSet(env)
    // get lineitem data set: (orderkey, extendedprice, discount, returnflag)
    val lineitems = getLineitemDataSet(env)
    // get nation data set: (nationkey, name)    
    val nations = getNationDataSet(env)

    // filter orders by years
    val orders1990 = orders.filter( o => o._3.substring(0,4).toInt > 1990)
                           .map( o => (o._1, o._2))
    
    // filter lineitems by return status
    val lineitemsReturn = lineitems.filter( l => l._4.equals("R"))
                                   .map( l => (l._1, l._2 * (1 - l._3)) )

    // compute revenue by customer
    val revenueByCustomer = orders1990.joinWithHuge(lineitemsReturn).where(0).equalTo(0)
                                        .apply( (o,l) => (o._2, l._2) )
                                      .groupBy(0)
                                      .aggregate(Aggregations.SUM, 1)

    // compute final result by joining customer and nation information with revenue
    val result = customers.joinWithTiny(nations).where(3).equalTo(0)
                            .apply( (c, n) => (c._1, c._2, c._3, n._2, c._5) )
                          .join(revenueByCustomer).where(0).equalTo(0)
                            .apply( (c, r) => (c._1, c._2, c._3, c._4, c._5, r._2) )
    // emit result
    result.writeAsCsv(outputPath, "\n", "|")

    // execute program
    env.execute("Scala TPCH Query 10 Example")
  }
  
  
  // *************************************************************************
  //     UTIL METHODS
  // *************************************************************************
  
  private var customerPath: String = null
  private var ordersPath: String = null
  private var lineitemPath: String = null
  private var nationPath: String = null
  private var outputPath: String = null

  private def parseParameters(args: Array[String]): Boolean = {
    if (args.length == 5) {
      customerPath = args(0)
      ordersPath = args(1)
      lineitemPath = args(2)
      nationPath = args(3)
      outputPath = args(4)
      true
    } else {
      System.err.println("This program expects data from the TPC-H benchmark as input data.\n" +
          "  Due to legal restrictions, we can not ship generated data.\n" +
          "  You can find the TPC-H data generator at http://www.tpc.org/tpch/.\n" +
          "  Usage: TPCHQuery10 <customer-csv path> <orders-csv path> " + 
                                "<lineitem-csv path> <nation-csv path> <result path>");
      false
    }
  }
  
  private def getCustomerDataSet(env: ExecutionEnvironment): 
                         DataSet[Tuple5[Int, String, String, Int, Double]] = {
    env.readCsvFile[Tuple5[Int, String, String, Int, Double]](
        customerPath,
        fieldDelimiter = '|',
        includedFields = Array(0,1,2,3,5) )
  }
  
  private def getOrdersDataSet(env: ExecutionEnvironment): DataSet[Tuple3[Int, Int, String]] = {
    env.readCsvFile[Tuple3[Int, Int, String]](
        ordersPath,
        fieldDelimiter = '|',
        includedFields = Array(0, 1, 4) )
  }
  
  private def getLineitemDataSet(env: ExecutionEnvironment):
                         DataSet[Tuple4[Int, Double, Double, String]] = {
    env.readCsvFile[Tuple4[Int, Double, Double, String]](
        lineitemPath,
        fieldDelimiter = '|',
        includedFields = Array(0, 5, 6, 8) )
  }

  private def getNationDataSet(env: ExecutionEnvironment): DataSet[Tuple2[Int, String]] = {
    env.readCsvFile[Tuple2[Int, String]](
        nationPath,
        fieldDelimiter = '|',
        includedFields = Array(0, 1) )
  }
  
}
