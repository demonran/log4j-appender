package com.tcl.gataway.flume.log4j;

import java.io.IOException;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;

import com.tcl.gataway.flume.log4j.helper.TcpClient;

public class FlumeTcpAppender extends AppenderSkeleton{
	
	private String hostname;
	private int port;
	
	private TcpClient tcpClient;

	@Override
	public void close(){
	    if (tcpClient != null) {
	      try {
	    	  tcpClient.close();
	      } catch (IOException ex) {
	        LogLog.error("Error while trying to close RpcClient.", ex);
	      } finally {
	    	  tcpClient = null;
	      }
	    } else {
	      String errorMsg = "Flume log4jappender already closed!";
	      LogLog.error(errorMsg);
	    }
	}

	@Override
	public boolean requiresLayout() {
		return true;
	}

	@Override
	protected void append(LoggingEvent event) {
		if (tcpClient == null) {
		      String errorMsg = "Cannot Append to Appender! Appender either closed or" +
		        " not setup correctly!";
		      LogLog.error(errorMsg);
		    }

		    if(!tcpClient.isActive()){
		      reconnect();
		    }
		
		
		String message  = layout.format(event);
		System.out.println(message);
		try {
			tcpClient.append(message);
		} catch (IOException e) {
			 String msg = "Flume append() failed.";
		      LogLog.error(msg);
		}
		
	}


	private void reconnect() {
		close();
		activateOptions();
		
	}

	@Override
	public void activateOptions() {
		tcpClient = new TcpClient();
		    try {
		    	tcpClient.connect(hostname,port);
		      if (layout != null) {
		        layout.activateOptions();
		      }
		    } catch (IOException e) {
		      String errormsg = "TCP client creation failed! " +
		        e.getMessage();
		      LogLog.error(errormsg);
		    }
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}
	
	

}
