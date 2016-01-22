package com.tcl.gateway.flume.log4j.helper;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;

public class TcpClient implements Appendable{
	
	private Socket socket;
	
	private PrintWriter pw ;

	public void connect(String hostname, int port)  throws IOException {
		socket = new Socket(hostname, port);
		pw = new PrintWriter(socket.getOutputStream());
	}

	@Override
	public TcpClient append(CharSequence csq) throws IOException {
		pw.println(csq);
		pw.flush();
		return this;
	}

	@Override
	public TcpClient append(CharSequence csq, int start, int end) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public TcpClient append(char c) throws IOException {
		return null;
	}

	public void close() throws IOException {
		if(socket != null)
		{
			socket.close();
			socket = null;
		}
		
	}

	public boolean isActive() {
		if(!socket.isConnected())
		{
			return false;
		}
		if(pw.checkError())
		{
			return false;
		}
		return true;
	}

}
