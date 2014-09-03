package org.cipango.server.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.Executor;

import javax.servlet.sip.SipServletResponse;

import org.cipango.server.AbstractSipConnector;
import org.cipango.server.MessageTooLongException;
import org.cipango.server.SipConnection;
import org.cipango.server.SipConnector;
import org.cipango.server.SipMessage;
import org.cipango.server.SipMessageGenerator;
import org.cipango.server.SipServer;
import org.cipango.server.Transport;
import org.cipango.server.util.FixedBufferPool;
import org.cipango.sip.SipParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class UdpConnector extends AbstractSipConnector
{
	private static final Logger LOG = Log.getLogger(UdpConnector.class);
	
	public static final int MAX_UDP_SIZE = 65536;
	public static final int DEFAULT_MTU = 1500;
	
	private DatagramChannel _channel;
	private InetAddress _localAddr;
	
	private ByteBuffer[] _inBuffers;
	private ByteBufferPool _outBuffers;
	private int _mtu;
	private final SipMessageGenerator _sipGenerator;
	
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
    	_mtu = MAX_UDP_SIZE;
    	_sipGenerator = new SipMessageGenerator();
    }

	public int getMtu()
	{
		return _mtu;
	}

	public void setMtu(int mtu)
	{
		_mtu = mtu;
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
		
		_outBuffers = new FixedBufferPool(_mtu);
		super.doStart();
	}
	
	public synchronized void open() throws IOException
	{
		_channel = DatagramChannel.open();
		_channel.configureBlocking(true);
		_channel.socket().bind(new InetSocketAddress(InetAddress.getByName(getHost()), getPort()));
		
		_localAddr = _channel.socket().getLocalAddress();
	}

	public synchronized void close() throws IOException
	{
		if (_channel != null)
			_channel.close();
		_channel = null;
	}
	
	public InetAddress getAddress()
	{
		return _localAddr;
	}

	protected void accept(int id) throws IOException 
	{
		DatagramChannel channel;
		synchronized (this)
		{
			channel = _channel;
		}
		
		if (channel != null && channel.isOpen())
		{
			ByteBuffer buffer = _inBuffers[id];
			BufferUtil.clearToFill(buffer);
			
			InetSocketAddress address = (InetSocketAddress) channel.receive(buffer);
			
			BufferUtil.flipToFlush(buffer, 0);

			new UdpConnection(address).process(buffer);		
		}	
	}
	
	@Override
	public SipConnection getConnection(InetAddress address, int port)
	{
		return new UdpConnection(new InetSocketAddress(address, port));
	}
	
	public class UdpConnection implements SipConnection
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
		
		public void send(SipMessage message) throws MessageTooLongException
		{
			send(message, false);
		}
		
		public void send(SipMessage message, boolean ignoreMtu) throws MessageTooLongException
		{
			ByteBuffer buffer = ignoreMtu ? BufferUtil.allocate(MAX_UDP_SIZE) : _outBuffers.acquire(_mtu, false);

			buffer.clear();
			
			_sipGenerator.generateMessage(buffer, message);
			
			if (!ignoreMtu && message.isRequest() && (buffer.position() + 200 > _mtu))
				throw new MessageTooLongException();
			
			buffer.flip();

			try
			{
				_channel.send(buffer, _address);
			}
			catch (Exception e)
			{
				LOG.warn(e);
			}
			
			if (!ignoreMtu)
				_outBuffers.release(buffer); 
		}
		
		public void write(ByteBuffer buffer) throws IOException
		{
			_channel.send(buffer, _address);
		}
		
		
		public void process(ByteBuffer buffer) throws IOException
		{
			
			MessageBuilder builder = new MessageBuilder(getServer(), this);
			SipParser parser = new SipParser(builder);
			
			boolean done = parser.parseNext(buffer);
			if (!done)
				builder.badMessage(SipServletResponse.SC_BAD_REQUEST, "Parser in state " + parser.getState()
						+ " after processing UDP packet");
		}

		@Override
		public boolean isOpen()
		{
			return _channel.isOpen();
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
