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
package com.tcl.gateway.firehose.log4j;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.retry.PredefinedRetryPolicies;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import com.amazonaws.services.kinesis.model.StreamStatus;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseAsyncClient;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamRequest;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.Record;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.tcl.gateway.firehose.log4j.helper.AsyncPutCallStatsReporter;
import com.tcl.gateway.firehose.log4j.helper.BlockFastProducerPolicy;
import com.tcl.gateway.firehose.log4j.helper.CustomCredentialsProviderChain;
import com.tcl.gateway.firehose.log4j.helper.Validator;



public class FirehoseAppender extends AppenderSkeleton {
  private static final Logger LOGGER = Logger.getLogger(FirehoseAppender.class);
  private String encoding = AppenderConstants.DEFAULT_ENCODING;
  private int maxRetries = AppenderConstants.DEFAULT_MAX_RETRY_COUNT;
  private int bufferSize = AppenderConstants.DEFAULT_BUFFER_SIZE;
  private int threadCount = AppenderConstants.DEFAULT_THREAD_COUNT;
  private int shutdownTimeout = AppenderConstants.DEFAULT_SHUTDOWN_TIMEOUT_SEC;
  private boolean metric = AppenderConstants.DEFAULT_METRIC;
  private int batchSize = AppenderConstants.DEFAULT_BATCH_SIZE;
  private String endpoint;
  private String region;
  private String deliveryStreamName;
  private boolean initializationFailed = false;
  private BlockingQueue<Runnable> taskBuffer;
  private AmazonKinesisFirehoseAsyncClient firehoseClient;
  private AsyncPutCallStatsReporter asyncCallHander;
  
  private PutRecordBatchRequest request;
  
  private void error(String message) {
    error(message, null);
  }

  private void error(String message, Exception e) {
    LOGGER.error(message, e);
    errorHandler.error(message, e, ErrorCode.GENERIC_FAILURE);
    throw new IllegalStateException(message, e);
  }

    /**
     * Set proxy configuration based on system properties. Some of the properties are standard
     * properties documented by Oracle (http.proxyHost, http.proxyPort, http.auth.ntlm.domain),
     * and others are from common convention (http.proxyUser, http.proxyPassword).
     *
     * Finally, for NTLM authentication the workstation name is taken from the environment as
     * COMPUTERNAME. We set this on the client configuration only if the NTLM domain was specified.
     */
    private ClientConfiguration setProxySettingsFromSystemProperties(ClientConfiguration clientConfiguration) {

        final String proxyHost = System.getProperty("http.proxyHost");
        if(proxyHost != null) {
            clientConfiguration.setProxyHost(proxyHost);
        }

        final String proxyPort = System.getProperty("http.proxyPort");
        if(proxyPort != null) {
            clientConfiguration.setProxyPort(Integer.parseInt(proxyPort));
        }

        final String proxyUser = System.getProperty("http.proxyUser");
        if(proxyUser != null) {
            clientConfiguration.setProxyUsername(proxyUser);
        }

        final String proxyPassword = System.getProperty("http.proxyPassword");
        if(proxyPassword != null) {
            clientConfiguration.setProxyPassword(proxyPassword);
        }

        final String proxyDomain = System.getProperty("http.auth.ntlm.domain");
        if(proxyDomain != null) {
            clientConfiguration.setProxyDomain(proxyDomain);
        }

        final String workstation = System.getenv("COMPUTERNAME");
        if(proxyDomain != null && workstation != null) {
            clientConfiguration.setProxyWorkstation(workstation);
        }

        return clientConfiguration;
    }

  /**
   * Configures this appender instance and makes it ready for use by the
   * consumers. It validates mandatory parameters and confirms if the configured
   * stream is ready for publishing data yet.
   * 
   * Error details are made available through the fallback handler for this
   * appender
   * 
   * @throws IllegalStateException
   *           if we encounter issues configuring this appender instance
   */
  @Override
  public void activateOptions() {
    if (deliveryStreamName == null) {
      initializationFailed = true;
      error("Invalid configuration - streamName cannot be null for appender: " + name);
    }

    if (layout == null) {
      initializationFailed = true;
      error("Invalid configuration - No layout for appender: " + name);
    }

    ClientConfiguration clientConfiguration = new ClientConfiguration();
    clientConfiguration = setProxySettingsFromSystemProperties(clientConfiguration);

    clientConfiguration.setMaxErrorRetry(maxRetries);
    clientConfiguration.setRetryPolicy(new RetryPolicy(PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
        PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY, maxRetries, true));
    clientConfiguration.setUserAgent(AppenderConstants.USER_AGENT_STRING);

    final BlockingQueue<Runnable> taskBuffer = new LinkedBlockingDeque<Runnable>(bufferSize);
    ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(threadCount, threadCount,
        AppenderConstants.DEFAULT_THREAD_KEEP_ALIVE_SEC, TimeUnit.SECONDS, taskBuffer, new BlockFastProducerPolicy());
    threadPoolExecutor.prestartAllCoreThreads();
    firehoseClient = new AmazonKinesisFirehoseAsyncClient(new CustomCredentialsProviderChain(), clientConfiguration,
        threadPoolExecutor);

    boolean regionProvided = !Validator.isBlank(region);
    if (!regionProvided) {
      region = AppenderConstants.DEFAULT_REGION;
    }
    if (!Validator.isBlank(endpoint)) {
      if (regionProvided) {
	LOGGER
	    .warn("Received configuration for both region as well as Amazon Kinesis endpoint. ("
		+ endpoint
		+ ") will be used as endpoint instead of default endpoint for region ("
		+ region + ")");
      }
      firehoseClient.setEndpoint(endpoint);
    } else {
    	firehoseClient.setRegion(Region.getRegion(Regions.fromName(region)));
    }

    DescribeDeliveryStreamResult describeResult = null;
    try {
      describeResult = firehoseClient.describeDeliveryStream(new DescribeDeliveryStreamRequest().withDeliveryStreamName(deliveryStreamName));
      String streamStatus = describeResult.getDeliveryStreamDescription().getDeliveryStreamStatus();
      if (!StreamStatus.ACTIVE.name().equals(streamStatus) && !StreamStatus.UPDATING.name().equals(streamStatus)) {
        initializationFailed = true;
        error("Delivery Stream " + deliveryStreamName + " is not ready (in active/updating status) for appender: " + name);
      }
    } catch (ResourceNotFoundException rnfe) {
      initializationFailed = true;
      error("Delivery  Stream " + deliveryStreamName + " doesn't exist for appender: " + name, rnfe);
    }

    asyncCallHander = new AsyncPutCallStatsReporter(name);
    
    if(metric)
    {
    	MetricRegistry registry = new MetricRegistry();
    	registry.register("Gauge", new Gauge<Integer>(){

			@Override
			public Integer getValue() {
				return taskBuffer.size();
			}
    		
    	});
    	
    	ConsoleReporter.forRegistry(registry).build().start(3, TimeUnit.SECONDS);
    }
  }

  /**
   * Closes this appender instance. Before exiting, the implementation tries to
   * flush out buffered log events within configured shutdownTimeout seconds. If
   * that doesn't finish within configured shutdownTimeout, it would drop all
   * the buffered log events.
   */
  @Override
  public void close() {
    ThreadPoolExecutor threadpool = (ThreadPoolExecutor) firehoseClient.getExecutorService();
    threadpool.shutdown();
    BlockingQueue<Runnable> taskQueue = threadpool.getQueue();
    int bufferSizeBeforeShutdown = threadpool.getQueue().size();
    boolean gracefulShutdown = true;
    try {
      gracefulShutdown = threadpool.awaitTermination(shutdownTimeout, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // we are anyways cleaning up
    } finally {
      int bufferSizeAfterShutdown = taskQueue.size();
      if (!gracefulShutdown || bufferSizeAfterShutdown > 0) {
        String errorMsg = "Kinesis Log4J Appender (" + name + ") waited for " + shutdownTimeout
            + " seconds before terminating but could send only " + (bufferSizeAfterShutdown - bufferSizeBeforeShutdown)
            + " logevents, it failed to send " + bufferSizeAfterShutdown
            + " pending log events from it's processing queue";
        LOGGER.error(errorMsg);
        errorHandler.error(errorMsg, null, ErrorCode.WRITE_FAILURE);
      }
    }
    firehoseClient.shutdown();
  }

  @Override
  public boolean requiresLayout() {
    return true;
  }

  /**
   * This method is called whenever a logging happens via logger.log(..) API
   * calls. Implementation for this appender will take in log events instantly
   * as long as the buffer is not full (as per user configuration). This call
   * will block if internal buffer is full until internal threads create some
   * space by publishing some of the records.
   * 
   * If there is any error in parsing logevents, those logevents would be
   * dropped.
   */
  @Override
  public void append(LoggingEvent logEvent) {
    if (initializationFailed) {
      error("Check the configuration and whether the configured stream " + deliveryStreamName
          + " exists and is active. Failed to initialize kinesis log4j appender: " + name);
      return;
    }
    try {
    	
    	if(request == null)
    	{
    		request = new PutRecordBatchRequest();
    	}
    	
      String message = layout.format(logEvent);
      ByteBuffer data = ByteBuffer.wrap(message.getBytes(encoding));
      
      request.withRecords(new Record().withData(data));
      
      if(request.getRecords().size() >= batchSize )
      {
    	   firehoseClient.putRecordBatchAsync(request, asyncCallHander);
      }
    } catch (Exception e) {
      LOGGER.error("Failed to schedule log entry for publishing into Kinesis stream: " + deliveryStreamName);
      errorHandler.error("Failed to schedule log entry for publishing into Kinesis stream: " + deliveryStreamName, e,
          ErrorCode.WRITE_FAILURE, logEvent);
    }
  }

  /**
   * Returns configured stream name
   * 
   * @return configured stream name
   */
  public String getDeliveryStreamName() {
    return deliveryStreamName;
  }

  /**
   * Sets streamName for the kinesis stream to which data is to be published.
   * 
   * @param streamName
   *          name of the kinesis stream to which data is to be published.
   */
  public void setDeliveryStreamName(String deliveryStreamName) {
    Validator.validate(!Validator.isBlank(deliveryStreamName), "streamName cannot be blank");
    this.deliveryStreamName = deliveryStreamName.trim();
  }

  /**
   * Configured encoding for the data to be published. If none specified,
   * default is UTF-8
   * 
   * @return encoding for the data to be published. If none specified, default
   *         is UTF-8
   */
  public String getEncoding() {
    return this.encoding;
  }

  /**
   * Sets encoding for the data to be published. If none specified, default is
   * UTF-8
   * 
   * @param charset
   *          encoding for expected log messages
   */
  public void setEncoding(String charset) {
    Validator.validate(!Validator.isBlank(encoding), "encoding cannot be blank");
    this.encoding = encoding.trim();
  }

  /**
   * Returns configured maximum number of retries between API failures while
   * communicating with Kinesis. This is used in AWS SDK's default retries for
   * HTTP exceptions, throttling errors etc.
   * 
   * @return configured maximum number of retries between API failures while
   *         communicating with Kinesis
   */
  public int getMaxRetries() {
    return maxRetries;
  }

  /**
   * Configures maximum number of retries between API failures while
   * communicating with Kinesis. This is used in AWS SDK's default retries for
   * HTTP exceptions, throttling errors etc.
   * 
   */
  public void setMaxRetries(int maxRetries) {
    Validator.validate(maxRetries > 0, "maxRetries must be > 0");
    this.maxRetries = maxRetries;
  }

  /**
   * Returns configured buffer size for this appender. This implementation would
   * buffer these many log events in memory while parallel threads are trying to
   * publish them to Kinesis.
   * 
   * @return configured buffer size for this appender.
   */
  public int getBufferSize() {
    return bufferSize;
  }

  /**
   * Configures buffer size for this appender. This implementation would buffer
   * these many log events in memory while parallel threads are trying to
   * publish them to Kinesis.
   */
  public void setBufferSize(int bufferSize) {
    Validator.validate(bufferSize > 0, "bufferSize must be >0");
    this.bufferSize = bufferSize;
  }

  /**
   * Returns configured number of parallel thread count that would work on
   * publishing buffered events to Kinesis
   * 
   * @return configured number of parallel thread count that would work on
   *         publishing buffered events to Kinesis
   */
  public int getThreadCount() {
    return threadCount;
  }

  /**
   * Configures number of parallel thread count that would work on publishing
   * buffered events to Kinesis
   */
  public void setThreadCount(int parallelCount) {
    Validator.validate(parallelCount > 0, "threadCount must be >0");
    this.threadCount = parallelCount;
  }

  /**
   * Returns configured timeout between shutdown and clean up. When this
   * appender is asked to close/stop, it would wait for at most these many
   * seconds and try to send all buffered records to Kinesis. However if it
   * fails to publish them before timeout, it would drop those records and exit
   * immediately after timeout.
   * 
   * @return configured timeout for shutdown and clean up.
   */
  public int getShutdownTimeout() {
    return shutdownTimeout;
  }

  /**
   * Configures timeout between shutdown and clean up. When this appender is
   * asked to close/stop, it would wait for at most these many seconds and try
   * to send all buffered records to Kinesis. However if it fails to publish
   * them before timeout, it would drop those records and exit immediately after
   * timeout.
   */
  public void setShutdownTimeout(int shutdownTimeout) {
    Validator.validate(shutdownTimeout > 0, "shutdownTimeout must be >0");
    this.shutdownTimeout = shutdownTimeout;
  }

  /**
   * Returns count of tasks scheduled to send records to Kinesis. Since
   * currently each task maps to sending one record, it is equivalent to number
   * of records in the buffer scheduled to be sent to Kinesis.
   * 
   * @return count of tasks scheduled to send records to Kinesis.
   */
  public int getTaskBufferSize() {
    return taskBuffer.size();
  }

  /**
   * Returns configured Kinesis endpoint.
   * 
   * @return configured kinesis endpoint
   */
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * Set kinesis endpoint. If set, it overrides the default kinesis endpoint in
   * the configured region
   * 
   * @param endpoint
   *          kinesis endpoint to which requests should be made.
   */
  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  /**
   * Returns configured region for Kinesis.
   * 
   * @return configured region for Kinesis
   */
  public String getRegion() {
    return region;
  }

  /**
   * Configures the region and default endpoint for all Kinesis calls. If not
   * overridden by {@link #setEndpoint(String)}, all Kinesis requests are made
   * to the default endpoint in this region.
   * 
   * @param region
   *          the Kinesis region whose endpoint should be used for kinesis
   *          requests
   */
  public void setRegion(String region) {
    this.region = region;
  }
}
