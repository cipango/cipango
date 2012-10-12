package org.cipango.server.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.text.ParseException;
import java.util.concurrent.Executor;

import org.cipango.server.AbstractSipConnector;
import org.cipango.server.SipConnection;
import org.cipango.server.SipConnector;
import org.cipango.server.SipMessage;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.SipServer;
import org.cipango.server.Transport;
import org.cipango.sip.AddressImpl;
import org.cipango.sip.SipGenerator;
import org.cipango.sip.SipHeader;
import org.cipango.sip.SipMethod;
import org.cipango.sip.SipParser;
import org.cipango.sip.SipVersion;
import org.cipango.sip.Via;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class UdpConnector extends AbstractSipConnector
{
	private static final Logger LOG = Log.getLogger(UdpConnector.class);
	
	public static final int MAX_UDP_SIZE = 65536;
	
	private DatagramChannel _channel;
	private InetAddress _localAddr;
	
	private ByteBuffer[] _inBuffers;
	private ByteBufferPool _outBuffers;
	
    public UdpConnector(
    		@Name("sipServer") SipServer server)
    {
        this(server, null, Math.max(1,(Runtime.getRuntime().availableProcessors())/2));
    }

    public UdpConnector(
            @Name("sipServer") SipServer server,
            @Name("acceptors") int acceptors)
    {
        this(server, null, acceptors);
    }

    public UdpConnector(
            @Name("sipServer") SipServer server,
            @Name("executor") Executor executor,
            @Name("acceptors") int acceptors)
    {
    	super(server, executor, acceptors);
    }

	public Transport getTransport()
	{
		return Transport.UDP;
	}
	
	@Override
	protected void doStart() throws Exception
	{
		_inBuffers = new ByteBuffer[getAcceptors()];
		for (int i = _inBuffers.length; i-->0;)
			_inBuffers[i] = BufferUtil.allocateDirect(MAX_UDP_SIZE);
		
		_outBuffers = new ArrayByteBufferPool();
		super.doStart();
	}
	
	public void open() throws IOException
	{
		_channel = DatagramChannel.open();
		_channel.configureBlocking(true);
		_channel.socket().bind(new InetSocketAddress(InetAddress.getByName(getHost()), getPort()));
		
		_localAddr = _channel.socket().getLocalAddress();
	}

	public void close() throws IOException
	{
		_channel.close();
	}
	
	public InetAddress getAddress()
	{
		return _localAddr;
	}

	protected void accept(int id) throws IOException 
	{
		ByteBuffer buffer = _inBuffers[id];
		BufferUtil.clearToFill(buffer);
		
		InetSocketAddress address = (InetSocketAddress) _channel.receive(buffer);
		
		BufferUtil.flipToFlush(buffer, 0);
	
	
		new UdpConnection(address).process(buffer);
		
		
		//getServer().handle(message);
		//System.out.println(message.getHeader("Call-Id"));
		//try { Thread.sleep(5); } catch (Exception e) {}
		//int length = p.getLength();
		//if (length == 2 || length == 4) return;
		
		
	}
	
	class UdpConnection implements SipConnection
	{
		private InetSocketAddress _address;
		
		public UdpConnection(InetSocketAddress address)
		{
			_address = address;
		}
		
		public SipConnector getConnector()
		{
			return UdpConnector.this;
		}
		
		public Transport getTransport()
		{
			return Transport.UDP;
		}
		
		public InetAddress getRemoteAddress()
		{
			return _address.getAddress();
		}
		
		public int getRemotePort()
		{
			return _address.getPort();
		}
		
		public InetAddress getLocalAddress()
		{
			return _localAddr;
		}
		
		public int getLocalPort()
		{
			return getPort();
		}
		
		public void send(SipMessage message)
		{
			SipResponse response = (SipResponse) message;
			ByteBuffer buffer = _outBuffers.acquire(1500, false);
			
			buffer.clear();
			
			new SipGenerator().generateResponse(buffer, response.getStatus(), response.getReasonPhrase(), response.getFields());

			buffer.flip();
			try
			{
				_channel.send(buffer, _address);
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
			
			_outBuffers.release(buffer); 
		}
		
		public void write(ByteBuffer buffer) throws IOException
		{
			_channel.send(buffer, _address);
		}
		
		
		public void process(ByteBuffer buffer) throws IOException
		{
			
			MessageBuilder builder = new MessageBuilder();
			SipParser parser = new SipParser(builder);
			
			parser.parseNext(buffer);
			
			SipMessage message = builder._message;
			message.setConnection(this);
			message.setTimeStamp(System.currentTimeMillis());
			
			getServer().process(message);
		}
	}
	
	class MessageBuilder implements SipParser.SipMessageHandler
	{
		private SipMessage _message;
		
		public boolean startRequest(String method, String uri, SipVersion version)
		{
			SipRequest request = new SipRequest();
			
			SipMethod m = SipMethod.CACHE.get(method);
			request.setMethod(m, method);
			
			_message = request;
			return false;
		}

		public boolean parsedHeader(SipHeader header, String name, String value)
		{
			Object o = value;
			
			try
			{	
				if (header != null)
				{
					switch (header.getType())
					{
					case VIA:
						Via via = new Via(value);
						via.parse();
						o = via;
						break;
					case ADDRESS:
						AddressImpl addr = new AddressImpl(value);
						addr.parse();
						o = addr;
						break;
					}
				}
			}
			catch (ParseException e)
			{
				LOG.warn(e);
				return true;
			}
			_message.getFields().add(name, o, false);
			return false;
		}

		public boolean headerComplete() 
		{
			return false;
		}

		public boolean messageComplete(ByteBuffer content) 
		{
			return false;
		}
		
		public void badMessage(int status, String reason)
		{
			
		}
	}
	
	public static void main(String[] args) throws Exception 
	{
		SipServer sipServer = new SipServer();
		UdpConnector connector = new UdpConnector(sipServer);
		connector.setHost("192.168.2.127");
		connector.setPort(5060);
		connector.start(); 
		
	}
}
