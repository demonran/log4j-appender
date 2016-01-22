/*******************************************************************************
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 * 
 *  http://aws.amazon.com/apache2.0
 * 
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 ******************************************************************************/
package com.tcl.gataway.firehose.log4j.helper;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormat;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchResult;

/**
 * Gathers information on how many put requests made by AWS SDK's async client,
 * succeeded or failed since the beginning
 */
public class AsyncPutCallStatsReporter implements AsyncHandler<PutRecordBatchRequest, PutRecordBatchResult> {
  private static Logger logger = Logger.getLogger(AsyncPutCallStatsReporter.class);
  private String appenderName;
  private long successfulRequestCount;
  private long failedRequestCount;
  private DateTime startTime;

  public AsyncPutCallStatsReporter(String appenderName) {
    this.appenderName = appenderName;
    this.startTime = DateTime.now();
  }

  /**
   * This method is invoked when there is an exception in sending a log record
   * to Kinesis. These logs would end up in the application log if configured
   * properly.
   */
  @Override
  public void onError(Exception exception) {
    failedRequestCount++;
    logger.error("Failed to publish a log entry to kinesis using appender: " + appenderName, exception);
  }

  /**
   * This method is invoked when a log record is successfully sent to Kinesis.
   * Though this is not too useful for production use cases, it provides a good
   * debugging tool while tweaking parameters for the appender.
   */
  @Override
  public void onSuccess(PutRecordBatchRequest request, PutRecordBatchResult result) {
    successfulRequestCount++;
    if (logger.isDebugEnabled() && (successfulRequestCount + failedRequestCount) % 3000 == 0) {
      logger.debug("Appender (" + appenderName + ") made " + successfulRequestCount
          + " successful put requests out of total " + (successfulRequestCount + failedRequestCount) + " in "
          + PeriodFormat.getDefault().print(new Period(startTime, DateTime.now())) + " since start");
    }
  }
}
