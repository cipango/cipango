// ========================================================================
// Copyright 2006-2013 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cipango.server.transaction;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import javax.servlet.sip.SipServletResponse;

import org.cipango.server.SipConnection;
import org.cipango.server.SipConnector;
import org.cipango.server.SipMessage;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.Transport;
import org.cipango.server.log.AccessLog;
import org.cipango.sip.AddressImpl;
import org.cipango.sip.SipHeader;
import org.cipango.sip.SipMethod;
import org.cipango.sip.Via;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * INVITE and non-INVITE client transaction. 
 * Supports RFC 6026.
 */
public class ClientTransactionImpl extends TransactionImpl implements ClientTransaction
{
	private static final Logger LOG = Log.getLogger(ClientTransactionImpl.class);
	
	private long _aDelay = __T1;
    private long _eDelay = __T1;
    
    private ClientTransactionListener _listener;
    private SipRequest _pendingCancel;
    
    private boolean _canceled = false;
    
    private SipConnection _connection;
    
    private Thread _responseProcessingThread;
    
	public ClientTransactionImpl(SipRequest request, ClientTransactionListener listener)
    {
		this(request, listener, request.appSession().newBranch());
	}
	
	public ClientTransactionImpl(SipRequest request, ClientTransactionListener listener, String branch) 
    {
		super(request, branch);
        _listener = listener;
	}
	
	public ClientTransactionListener getListener()
	{
		return _listener;
	}
	
	private void ack(SipResponse response) 
    {
		SipRequest ack = _request.createRequest(SipMethod.ACK);
		
		if (ack.to().getTag() == null) 
        {
			String tag = response.to().getTag();
			if (tag != null) 
				ack.to().setParameter(AddressImpl.TAG, tag);
		}
		try 
        {
			getServer().sendRequest(ack, getConnection());
		} 
        catch (IOException e) 
        {
			LOG.ignore(e);
		}
	}
	
	public synchronized void cancel(SipRequest cancel)
	{
		if (_canceled) 
			return;
		
		_canceled = true;
		
		if (_state == State.UNDEFINED || _state == State.CALLING || _state == State.TRYING)
		{
			_pendingCancel = cancel;
			return;
		}
		doCancel(cancel);
	}
		
	public boolean isCanceled()
	{
		return _canceled;
	}
	
	private ClientTransaction doCancel(SipRequest cancel)
	{
		ClientTransactionImpl cancelTx = new ClientTransactionImpl(cancel, _listener, cancel.getTopVia().getBranch());
		cancelTx.setTransactionManager(_transactionManager);
		cancelTx._connection = getConnection();
		cancelTx._listener = _listener;
		
		_transactionManager.addClientTransaction(cancelTx);
		
		try 
        {
			cancelTx.start();
		} 
        catch (IOException e) 
        {
			LOG.warn(e);
		}
        return cancelTx;
	}

	private void doSend() throws IOException 
    {
		if (getConnection() != null)
		{
			if (getConnection().isOpen())
				getServer().sendRequest(_request, getConnection());
			else
				LOG.debug("Could not sent request {} as the connection {} is closed", _request, getConnection());
		}
		else 
		{
			// TODO check Maxforwards
			
			Via via = new Via(null, null, -1);
			via.setBranch(getBranch());
			//customizeVia(via);
			_request.getFields().add(SipHeader.VIA.asString(), via, true);
			
			_connection = _transactionManager.getTransportProcessor().getConnection(
					_request, null);
			_listener.customizeRequest(_request, _connection);
			getServer().sendRequest(_request, _connection);
			
		}
	}
	

	
	public synchronized void start() throws IOException 
    {
        if (_state != State.UNDEFINED)
            throw new IllegalStateException("!undefined: " + _state);
        
        if (isInvite()) 
        {
			setState(State.CALLING);
			try
			{
				doSend();
			}
			finally
			{
				startTimer(Timer.B, 64L*__T1);
				startTimer(Timer.C, __TC);
			}
			if (!isTransportReliable())
				startTimer(Timer.A, _aDelay);
		} 
        else if (isAck()) 
        {
			setState(State.TRYING);
			doSend();
		} 
        else 
        {
			setState(State.TRYING);
			try
			{
				doSend();
			}
			finally
			{
				startTimer(Timer.F, 64L*__T1);
			}
			if (!isTransportReliable()) 
				startTimer(Timer.E, _eDelay);
		}
	}
	
	public synchronized void handleResponse(SipResponse response) 
    {
    _responseProcessingThread = Thread.currentThread();
    try
		{
		int status = response.getStatus(); 
        
		if (isInvite()) 
        {
			switch (_state) 
            {
			case CALLING:
				cancelTimer(Timer.A); cancelTimer(Timer.B); cancelTimer(Timer.C);
				if (status < 200) 
                {
					setState(State.PROCEEDING);
					if (_pendingCancel != null)
						doCancel(_pendingCancel);
					else
					  startTimer(Timer.C, __TC);
				} 
                else if (200 <= status && status < 300) 
                {
					setState(State.ACCEPTED);
					startTimer(Timer.M, 64L*__T1);
				} 
                else 
                {
					setState(State.COMPLETED);
					ack(response);
					if (isTransportReliable()) 
						terminate();
					else 
						startTimer(Timer.D, __TD);
				}
				_listener.handleResponse(response);
				break;
				
			case PROCEEDING:
			  cancelTimer(Timer.C);
			  if (status < 200) 
			  {
			    // Provisional responses should restart timer C.
			    startTimer(Timer.C, __TC);
			  }
			  else if (200 <= status && status < 300) 
                {
					setState(State.ACCEPTED);
					startTimer(Timer.M, 64L*__T1);
				} 
                else if (status >= 300) 
                {
					setState(State.COMPLETED);
					ack(response);
					if (isTransportReliable()) 
						terminate();
					else 
						startTimer(Timer.D, __TD);
				}
				_listener.handleResponse(response);
				break;
                
			case COMPLETED:
				ack(response);
				break;
			case ACCEPTED:
				if (!(200 <= status && status < 300))
				{
					LOG.debug("non 2xx response {} in Accepted state", response);
				}
				else
				{
					_listener.handleResponse(response);
				}
				break;
			default:
				LOG.debug("handleResponse (invite) && state ==" + _state);
			}
		} 
        else 
        {
			switch (_state) 
            {
			case TRYING:
				if (status < 200) 
                {
					setState(State.PROCEEDING);
				} 
                else 
                {
					cancelTimer(Timer.E); cancelTimer(Timer.F);
					setState(State.COMPLETED);
					if (isTransportReliable()) 
						terminate(); // TIMER_K == 0
					else 
						startTimer(Timer.K, __T4);
				}
                if (!isCancel())
                    _listener.handleResponse(response);
				break;
                
			case PROCEEDING:
				if (status >= 200) 
                {
                    cancelTimer(Timer.E); cancelTimer(Timer.F);
					setState(State.COMPLETED);
					if (isTransportReliable())
						terminate();
					else 
						startTimer(Timer.K, __T4);
                    if (!isCancel())
                        _listener.handleResponse(response);
				}
				break;
				
			case COMPLETED:
				break;
				
			default:
				LOG.warn("handleResponse (non-invite) && state ==" + _state);
			}
		}
		}
		finally
		{
			_responseProcessingThread = null;
		}
	}
	
	
	public boolean isProcessingResponse()
	{
		return _responseProcessingThread != null && _responseProcessingThread != Thread.currentThread();
	}
	
	public boolean isServer() 
    {
		return false;
	}
	
	public void terminate() 
    {
		super.terminate();
		_transactionManager.transactionTerminated(this);
		if (_listener != null)
			_listener.transactionTerminated(this);
    }
	
	
	public SipResponse create408()
	{
		// could not use request.createResponse() because the request is committed. 
		SipResponse responseB = new SipResponse(_request, SipServletResponse.SC_REQUEST_TIMEOUT, null);
		if (responseB.to().getTag() == null)
			responseB.to().setParameter(AddressImpl.TAG, responseB.appSession().newUASTag());
		SipConnector c = getConnection() == null ? getServer().getConnectors()[0] : getConnection().getConnector();
		responseB.setConnection(new TimeoutConnection(c));
		
		AccessLog accessLog = getServer().getAccessLog();
		if (accessLog != null)
			accessLog.messageReceived(responseB, responseB.getConnection());
		
		return responseB;
	}
	

	@Override
	public SipConnection getConnection()
	{
		return _connection;
	}
	
	@Override
	public String toString()
	{
		return "ClientTransaction {branch=" + getBranch() 
				+ ", method=" + getRequest().getMethod()
				+ ", state=" + getState() + "}";
	}

	@Override
	protected synchronized void timeout(Timer timer)
	{
		if (isCanceled(timer))
		{
			LOG.debug("Do not run timer {} on transaction {} as it is canceled ", timer, this);
			return;
		}
		
		switch (timer) 
        {
		case A:
			try 
            {
            	doSend();
			} 
            catch (IOException e) 
            {
				LOG.debug("Failed to (re)send request " + _request);
			}
			_aDelay = _aDelay * 2;
			startTimer(Timer.A, _aDelay);
			break;
    case C:
      // RFC3261, Section 16.8
      switch (_state)
      {
      case PROCEEDING:
        // We should generate CANCEL (we had received provisional responses before):
        try
        {
          _request.createCancel().send();
        }
        catch (Exception e)
        {
          LOG.debug("Failed to process timer C timeout, cannot send cancel for " + _request, e);
        }
        // If timer C should fire ... or terminate the client transaction.
        // So let's terminate it here. If we haven't received INVITE response in 3min, there is good chance
        // that we wouldn't receive 487 as well.
        cancelTimer(Timer.A); cancelTimer(Timer.B);
        terminate();
        return;
      case CALLING:
        // In this case (no any response received) we want to behave like timer B below.
        break;
      default:
        LOG.info("Timer C fired in unknown state {}, cancel it", getState());
        cancelTimer(Timer.C);
        return;
      }
      // No break. E.g. if we haven't received any provisional response, timerC should behave as timerB 

		case B:
			cancelTimer(Timer.A);
			SipResponse responseB = create408();
			// TODO send to ??
            if (!isCancel())
                _listener.handleResponse(responseB);
			terminate();
            break;
    
        case D:
            terminate();
            break;
            
        case E:
            try 
            {
                doSend();
            }
            catch (IOException e)
            {
                LOG.debug("Failed to (re)send request " + _request);
            }
            if (_state == State.TRYING)
                _eDelay = Math.min(_eDelay * 2, __T2);
            else
                _eDelay = __T2;
            startTimer(Timer.E, _eDelay);
            break;
        case F:
            cancelTimer(Timer.E);
            SipResponse responseF = create408();
            if (!isCancel())
                _listener.handleResponse(responseF); // TODO interface TU
            terminate();
            break;
        case K:
            terminate();
            break;
        case M:
        	terminate();
        	break;
        default:
            throw new RuntimeException("unknown timer  " + timer);
		}
	}
	
	public static class TimeoutConnection implements SipConnection
	{
		private SipConnector _connector;
		
		public TimeoutConnection(SipConnector connector)
		{
			_connector = connector;
		}
		
		@Override
		public SipConnector getConnector()
		{			
			
			return _connector;
		}

		@Override
		public InetAddress getLocalAddress()
		{
			return _connector.getAddress();
		}

		@Override
		public int getLocalPort()
		{
			return _connector.getPort();
		}

		@Override
		public InetAddress getRemoteAddress()
		{
			return _connector.getAddress();
		}

		@Override
		public int getRemotePort()
		{
			return _connector.getPort();
		}

		@Override
		public Transport getTransport()
		{
			return _connector.getTransport();
		}

		@Override
		public void send(SipMessage message)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void write(ByteBuffer buffer) throws IOException
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isOpen()
		{
			return false;
		}
		
	}

}


