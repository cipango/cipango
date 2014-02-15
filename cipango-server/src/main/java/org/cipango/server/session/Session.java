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
package org.cipango.server.session;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipErrorEvent;
import javax.servlet.sip.SipErrorListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionBindingEvent;
import javax.servlet.sip.SipSessionBindingListener;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;

import org.cipango.server.RequestCustomizer;
import org.cipango.server.SipConnection;
import org.cipango.server.SipConnector;
import org.cipango.server.SipMessage;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.SipServer;
import org.cipango.server.servlet.SipServletHolder;
import org.cipango.server.session.SessionManager.SipSessionIf;
import org.cipango.server.session.scoped.ScopedClientTransactionListener;
import org.cipango.server.session.scoped.ScopedServerTransactionListener;
import org.cipango.server.sipapp.SipAppContext;
import org.cipango.server.transaction.ClientTransaction;
import org.cipango.server.transaction.ClientTransactionListener;
import org.cipango.server.transaction.ServerTransaction;
import org.cipango.server.transaction.ServerTransactionListener;
import org.cipango.server.transaction.Transaction;
import org.cipango.server.transaction.TransactionImpl;
import org.cipango.server.util.ReadOnlyAddress;
import org.cipango.sip.AddressImpl;
import org.cipango.sip.RAck;
import org.cipango.sip.SipException;
import org.cipango.sip.SipFields;
import org.cipango.sip.SipFields.Field;
import org.cipango.sip.SipGrammar;
import org.cipango.sip.SipHeader;
import org.cipango.sip.SipMethod;
import org.cipango.util.TimerTask;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Session implements SipSessionIf, Dumpable
{
	public enum Role { UNDEFINED, UAC, UAS, PROXY_RECORD_ROUTE, PROXY_NO_RECORD_ROUTE}
	
	private static final Logger LOG = Log.getLogger(Session.class);
	
	private final String _id;
	private final ApplicationSession _applicationSession;

	protected State _state = State.INITIAL;
	protected Role _role = Role.UNDEFINED;
	
	protected final String _callId;
	protected Address _localParty;
	protected Address _remoteParty;
	
	protected Map<String, Object> _attributes;
	
	protected List<ServerTransaction> _serverTransactions = new ArrayList<ServerTransaction>(1);
	protected List<ClientTransaction> _clientTransactions = new ArrayList<ClientTransaction>(1);
	
	protected String _linkedSessionId;
	
	private boolean _valid = true;
	private final long _created;
	private long _lastAccessed;
	
	private DialogInfo _dialog;
	private SipServletHolder _handler;
	private boolean _invalidateWhenReady = true;

	protected SipApplicationRoutingRegion _region;
	protected URI _subscriberURI;
	
	public Session(ApplicationSession applicationSession, String id, SipRequest request)
	{
		this(applicationSession, 
				id,
				request.getCallId(),
				request.to().clone(),
				(Address) request.getFrom().clone());
	}
	
	public Session(ApplicationSession applicationSession, String id, String callId, Address local, Address remote)
	{
		_applicationSession = applicationSession;
		_id = id;
		_created = System.currentTimeMillis();
		_callId = callId;
		_localParty = local;
		_remoteParty = remote;
		if (applicationSession.getContext().getSpecVersion() == SipAppContext.VERSION_10)
			_invalidateWhenReady = false;
	}
	
	public Session(String id, Session other)
	{
		this(other._applicationSession, 
				id,
				other.getCallId(),
				(Address) other.getLocalParty().clone(),
				(Address) other.getRemoteParty().clone());
		_invalidateWhenReady = other._invalidateWhenReady;
		_handler = other._handler;
				
		_role = other._role;
		
		if (_role == Role.UAS)
			_localParty.setParameter(AddressImpl.TAG,  _applicationSession.newUASTag());
		else
			_remoteParty.removeParameter(AddressImpl.TAG);
		
		if (other._dialog != null)
		{
			_dialog = new DialogInfo();
			_dialog._localCSeq = other._dialog._localCSeq;
		}
		
		if (other._attributes != null)
		{
			_attributes = new HashMap<String, Object>();
			_attributes.putAll(other._attributes);
		}
	}
	
	public ApplicationSession appSession()
	{
		return _applicationSession;
	}
	
	public boolean isUA()
	{
		return _role == Role.UAS || _role == Role.UAC;
	}
	
	public boolean isProxy()
	{
		return _role == Role.PROXY_RECORD_ROUTE || _role == Role.PROXY_NO_RECORD_ROUTE;
	}
	
	public void setLinkedSession(Session session) 
	{ 
		_linkedSessionId = session != null ? session.getId() : null;
	}
	
	public Session getLinkedSession() 
	{ 
		return _linkedSessionId != null ? (Session) _applicationSession.getSipSession(_linkedSessionId) : null; 
	}
	
	protected SipServer getServer()
	{
		return _applicationSession.getSessionManager().getSipAppContext().getServer();
	}
	
	private void checkValid()
	{
		if (!_valid)
			throw new IllegalStateException("SipSession has been invalidated");
	}
	
	protected Object doPutOrRemove(String name, Object value)
	{
		if (value == null)
		{	
			return _attributes != null ? _attributes.remove(name) : null;
		}
		else
		{
			if (_attributes == null)
				_attributes = new HashMap<String, Object>();
			return _attributes.put(name, value);
		}
	}
	
	private void bindValue(String name, Object value)
	{
		if (value != null && value instanceof SipSessionBindingListener)
			((SipSessionBindingListener) value).valueBound(new SipSessionBindingEvent(this, name));
	}
	
	private void unbindValue(String name, Object value)
	{
		if (value != null && value instanceof SipSessionBindingListener)
			((SipSessionBindingListener) value).valueUnbound(new SipSessionBindingEvent(this, name));
	}
	
	public void setProxy()
	{
		if (isUA())
			throw new IllegalStateException("Session is " + _role);
		
		_role = Role.PROXY_RECORD_ROUTE;
		
		// In proxy local and remote party are inversed.
		Address tmp = _remoteParty;
		_remoteParty = _localParty;
		_localParty = tmp;	
	}
	
	public void setRecordRoute(boolean recordRoute)
	{
		if (!isProxy())
			throw new IllegalStateException("Session is " + _role);
		
		_role = recordRoute ? Role.PROXY_RECORD_ROUTE : Role.PROXY_NO_RECORD_ROUTE;
	}
	
	public void setUAS()
	{
		if (isUA())
			return;
		
		if (_role != Role.UNDEFINED)
			throw new IllegalStateException("session is " + _role);
		
		_role = Role.UAS;
		
		String tag = _localParty.getParameter(AddressImpl.TAG);
		if (tag == null)
		{
			tag = _applicationSession.newUASTag();
			_localParty.setParameter(AddressImpl.TAG, tag);
		}
		_dialog = new DialogInfo();
	}
	
	public void createUA(UAMode mode)
	{
		// FIXME merge setUAS and createUA
		if (_role != Role.UNDEFINED)
			throw new IllegalStateException("Session is " + _role);
		
		_role = mode == UAMode.UAC ? Role.UAC : Role.UAS;
		_dialog = new DialogInfo();
	}
	
	public void sendResponse(SipResponse response, boolean reliable) throws IOException
	{
		access();
		
		if (_role == Role.UNDEFINED)
			createUA(UAMode.UAS);
		
		if (isProxy()) // Virtual branch
		{
			if (!response.getRequest().isInitial())
				throw new IllegalStateException("Session is proxy");
			
			_role = Role.UAS;
			_dialog = new DialogInfo();
			
			// Reset local and remote party to original values
			Address tmp = _remoteParty;
			_remoteParty = _localParty;
			_localParty = tmp;
			
			String tag = _applicationSession.newUASTag();
			_localParty.setParameter(AddressImpl.TAG, tag);
			
			response.to().setParameter(AddressImpl.TAG, tag);
			
			LOG.debug("Create a virtual branch on session {}", this);
		}
		
		if (isUA())
		{
			_dialog.sendResponse(response, reliable);
		}
	}
		
	public ClientTransaction sendRequest(SipRequest request, ClientTransactionListener listener) throws IOException
	{
		SipServer server = getServer();

		access();
				
		if (server.getHandler() instanceof RequestCustomizer)
			((RequestCustomizer) server.getHandler()).customizeRequest(request);
		
		request.setCommitted(true);
	
		ClientTransaction tx = server.getTransactionManager().sendRequest(request, new ScopedClientTransactionListener(this, listener));

		if (!request.isAck())
			addClientTransaction(tx);
		return tx;
	}
		
	public ClientTransaction sendRequest(SipRequest request) throws IOException
	{
		if (!isUA())
			throw new IllegalStateException("Session is not UA");
		
		return sendRequest(request, _dialog);
	}
			
	private void setState(State state)
	{
		if (LOG.isDebugEnabled())
            LOG.debug("{} -> {}", this, state);
		_state = state;
	}

	public void invokeServlet(SipRequest request) throws SipException
	{
		try
		{
			_applicationSession.getSessionManager().getSipAppContext().handle(request);
		}
		catch (TooManyHopsException e)
		{
			throw new SipException(SipServletResponse.SC_TOO_MANY_HOPS);
		}
		catch (Throwable t)
		{
			throw new SipException(SipServletResponse.SC_SERVER_INTERNAL_ERROR, t);
		}
	}

	public void invokeServlet(SipResponse response)
	{
		try
		{
			if (isValid())
				_applicationSession.getSessionManager().getSipAppContext().handle(response);
		}
		catch (Throwable t)
		{
			LOG.debug(t);
		}
	}
	
	public void access()
	{
		_lastAccessed = System.currentTimeMillis();
		_applicationSession.access(_lastAccessed);
	}
	
	/**
	 * When in proxy mode, the server transaction become complete, the derived sessions 
	 * state should be set to TERMINATED.
	 * 
	 * @param SipReponse the last received response for the branch.
	 */
	public void updateStateOnProxyComplete(boolean forceTerminated)
	{
		if (!isProxy())
		{
			// Could happens if a virtual response has been sent
			LOG.debug("Could not update state as role is no more proxy");
			return;
		}
		
		access();
		
		switch (_state)
		{
		case INITIAL:
			setState(State.TERMINATED);
			break;
		case EARLY:
			if (forceTerminated)
				setState(State.TERMINATED);
			break;
		default:
			break;
		}
	}
		
	public void updateState(SipResponse response, boolean uac)
	{
		SipRequest request = (SipRequest) response.getRequest();
		int status = response.getStatus();

		if (request.isInitial() && (request.isInvite() || request.isSubscribe()))
		{
			switch (_state)
			{
			case INITIAL:
				if (status < 300)
				{
					// In UAS mode, the to tag has been set yet.
					if ((!uac && isUA()) || response.to().getTag() != null)
					{	
						if (_dialog != null)
							_dialog.createDialog(response, uac);
						else if (isProxy())
							createProxyDialog(response);

						if (status < 200)
							setState(State.EARLY);
						else
							setState(State.CONFIRMED);
					}
				}
				else
				{
					if (isProxy())
					{
						// Keep state to INITIAL
					}
					else if (uac)
					{
						// FIXME _dialog.resetDialog();
						setState(State.INITIAL);
					}
					else
					{
						setState(State.TERMINATED);
					}
				}
				break;
			case EARLY:
				if (200 <= status && status < 300)
				{
					setState(State.CONFIRMED);
				}
				else if (status >= 300)
				{
					if (isProxy())
					{
						_remoteParty.removeParameter(AddressImpl.TAG);
						setState(State.INITIAL);
					}
					else if (uac)
					{
						// FIXME _dialog.resetDialog();
						setState(State.INITIAL);
					}
					else
						setState(State.TERMINATED);
				}
				break;
			default:
				break;
			}
		}
		else if (request.isBye() && response.is2xx())
		{
			setState(State.TERMINATED);
		}
		else if (response.isNotify())
		{
			try
			{
				Parameterable p = request.getParameterableHeader(SipHeader.SUBSCRIPTION_STATE.asString());
				if (p != null)
				{
					if ("terminated".equalsIgnoreCase(p.getValue()))
						setState(State.TERMINATED);
				}
				else
					LOG.debug("Missing mandatory Subscription-State header in NOTIFY");
			}
			catch (Exception e)
			{
				LOG.ignore(e);
			}
		}
	}
		
	public boolean isSameDialog(SipResponse response)
	{
		String remoteTag = _remoteParty.getParameter(AddressImpl.TAG);
		if (remoteTag != null)
		{
			String responseTag = response.to().getTag();
			if (responseTag != null && !remoteTag.equalsIgnoreCase(responseTag))
			{
				LOG.debug("session: not same dialog tags are {} {}", remoteTag, responseTag);
				return false;
			}
		}
		return true;
	}
	
	public boolean isDialog(String callId, String fromTag, String toTag)
	{
		if (!_callId.equals(callId))
			return false;
		
		String localTag = _localParty.getParameter(AddressImpl.TAG);
		String remoteTag = _remoteParty.getParameter(AddressImpl.TAG);
		
		if (fromTag.equals(localTag) && toTag.equals(remoteTag))
			return true;
		if (toTag.equals(localTag) && fromTag.equals(remoteTag))
			return true;
		return false;
	}
	
	protected void createProxyDialog(SipResponse response)
	{
		String tag = response.to().getTag();
        _remoteParty.setParameter(AddressImpl.TAG, tag);
	}
	
	public Address getContact()
	{
		return getContact(getServer().getConnectors()[0]);
	}
	
	public Address getContact(SipConnector connector)
	{
		URI uri = connector.getURI().clone();
		uri.setParameter(SessionHandler.APP_ID, _applicationSession.getId());
		return new AddressImpl(uri);
	}
	
	public Address getContact(SipConnection connection)
	{
		return getContact(connection.getConnector());
	}
	
	public SipServletRequest createRequest(String method) 
	{
		checkValid();
		if (!isUA())
			throw new IllegalStateException("session is " + _role);

		return _dialog.createRequest(method);
	}
	
	public SipServletHolder getHandler()
	{
		return _handler;
	}
	
	public void setHandler(SipServletHolder handler)
	{
		_handler = handler;
	}
	

	/**
	 * @see SipSession#getApplicationSession()
	 */
	public SipApplicationSession getApplicationSession() 
	{
		return _applicationSession;
	}

	/**
	 * @see SipSession#getAttribute(String)
	 */
	public synchronized Object getAttribute(String name) 
	{
		checkValid();
		if (name == null)
			throw new NullPointerException("Attribute name is null");
		
		return _attributes != null ? _attributes.get(name) : null;
	}

	/**
	 * @see SipSession#getAttributeNames()
	 */
	public synchronized Enumeration<String> getAttributeNames() 
	{
		checkValid();
		if (_attributes == null)
			return Collections.emptyEnumeration();
		return Collections.enumeration(new ArrayList<String>(_attributes.keySet()));
	}

	@Override
	public String getCallId() 
	{
		return _callId;
	}

	/**
	 * @see SipSession#getCreationTime()
	 */
	public long getCreationTime() 
	{
		return _created;
	}

	/**
	 * @see SipSession#getId()
	 */
	public String getId() 
	{
		return _id;
	}

	@Override
	public boolean getInvalidateWhenReady() 
	{
		checkValid();
		return _invalidateWhenReady;
	}

	/**
	 * @see SipSession#getLastAccessedTime()
	 */
	public long getLastAccessedTime() 
	{
		return _lastAccessed;
	}

	@Override
	public Address getLocalParty() 
	{
		return new ReadOnlyAddress(_localParty);
	}

	@Override
	public SipApplicationRoutingRegion getRegion() 
	{
		checkValid();
		return _region;
	}

	@Override
	public Address getRemoteParty()
	{
		return new ReadOnlyAddress(_remoteParty);
	}
	
	public String getLocalTag()
	{
		return _localParty.getParameter(AddressImpl.TAG);
	}

	/**
	 * @see SipSession#getServletContext()
	 */
	public ServletContext getServletContext() 
	{
		return _applicationSession.getSessionManager().getContext();
	}

	/**
	 * @see SipSession#getState()
	 */
	public State getState() 
	{
		checkValid();
		return _state;
	}

	@Override
	public URI getSubscriberURI() 
	{
		checkValid();
		return _subscriberURI;
	}

	@Override
	public void invalidate() 
	{
		checkValid();
		_applicationSession.assertLocked();
		
		if (LOG.isDebugEnabled())
			LOG.debug("invalidating SipSession {}", this);
		
		_valid = false;
		_applicationSession.removeSession(this);
	}

	@Override
	public boolean isReadyToInvalidate() 
	{
		checkValid();

		if (_lastAccessed == 0)
			return false;
		
		if (_state == State.TERMINATED || _state == State.INITIAL)
		{
			// Invalidate also if transaction state is accepted as it could only
			// happens when state is TERMINATED
			for (Transaction transaction : _clientTransactions)
				if (transaction.getState() != Transaction.State.COMPLETED
						&& transaction.getState() != Transaction.State.CONFIRMED
						&& transaction.getState() != Transaction.State.ACCEPTED)
					return false;
			for (Transaction transaction : _serverTransactions)
				if (transaction.getState() != Transaction.State.COMPLETED
						&& transaction.getState() != Transaction.State.CONFIRMED
						&& transaction.getState() != Transaction.State.ACCEPTED)
					return false;
			return true;
		}
		
		if (_role == Role.PROXY_NO_RECORD_ROUTE)
		{
			for (Transaction transaction : _clientTransactions)
				if (transaction.getState() != Transaction.State.COMPLETED
						&& transaction.getState() != Transaction.State.CONFIRMED)
					return false;
			for (Transaction transaction : _serverTransactions)
				if (transaction.getState() != Transaction.State.COMPLETED
						&& transaction.getState() != Transaction.State.CONFIRMED)
					return false;
			return true;
		}

		return false;
	}
	
	public void invalidateIfReady()
	{
		if (isValid() && getInvalidateWhenReady() && isReadyToInvalidate())
		{
			SipAppContext context = _applicationSession.getContext();
			List<SipSessionListener> listeners = _applicationSession.getSessionManager().getSessionListeners();
			if (!listeners.isEmpty())
				context.fire(_applicationSession, listeners, ApplicationSession.__sessionReadyToInvalidate, new SipSessionEvent(this));

			if (isValid() && getInvalidateWhenReady())
				invalidate();
		}
	}

	/**
	 * @see SipSession#isValid()
	 */
	public boolean isValid() 
	{
		return _valid;
	}
	
	public boolean isTerminated()
	{
		return _state == State.TERMINATED || !_valid;
	}
	
	/**
	 * @see SipSession#removeAttribute(String)
	 */
	public void removeAttribute(String name) 
	{
		putAttribute(name, null);
	}

	/**
	 * @see SipSession#setAttribute(String, Object)
	 */
	public void setAttribute(String name, Object value) 
	{
		if (name == null || value == null)
			throw new NullPointerException("Name or attribute is null");
		putAttribute(name, value);
	}
	
	public void putAttribute(String name, Object value) 
	{
		Object old = null;
		synchronized (this)
		{
			checkValid();
			old = doPutOrRemove(name, value);
		}
		_applicationSession.assertLocked();
		if (value == null || !value.equals(old))
		{
			if (old != null)
				unbindValue(name, old);
			if (value != null)
				bindValue(name, value);
			
			_applicationSession.getSessionManager().doSessionAttributeListeners(this, name, old, value);
		}
	}

	/**
	 * @see SipSession#setHandler(String)
	 */
	public void setHandler(String name) throws ServletException 
	{
		checkValid();
		
		SipAppContext context = _applicationSession.getContext();
		SipServletHolder handler = context.getServletHandler().getHolder(name);
		
		if (handler == null)
			throw new ServletException("No handler named " + name);
		
		setHandler(handler);
	}

	@Override
	public void setInvalidateWhenReady(boolean invalidateWhenReady) 
	{
		checkValid();
		_invalidateWhenReady = invalidateWhenReady;
	}

	@Override
	public void setOutboundInterface(InetSocketAddress address) 
	{
		checkValid();
		if (address == null)
			throw new NullPointerException("Null address");
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void setOutboundInterface(InetAddress address) 
	{
		checkValid();
		if (address == null)
			throw new NullPointerException("Null address");
		// TODO Auto-generated method stub	
	}

	public void addServerTransaction(ServerTransaction transaction)
	{
		_serverTransactions.add(transaction);
	}
	
	public void removeTransaction(Transaction transaction)
	{
		if (transaction.isServer())
			_serverTransactions.remove(transaction);
		else
			_clientTransactions.remove(transaction);
	}
	
	public List<ServerTransaction> getServerTransactions() 
	{
		return _serverTransactions;
	}
	
	public void addClientTransaction(ClientTransaction transaction)
	{
		_clientTransactions.add(transaction);
	}
		
	public List<ClientTransaction> getClientTransactions() 
	{
		return _clientTransactions;
	}
	
	public void setSubscriberURI(URI uri)
	{
		_subscriberURI = uri;
	}
	
	public void setRegion(SipApplicationRoutingRegion region)
	{
		_region = region;
	}
	
	public DialogInfo getUa()
	{
		return _dialog;
	}
	
	@Override 
	public String toString()
	{
		return String.format("%s{l(%s)<->r(%s),%s,%s}", getId(), _localParty, _remoteParty, _state, _role);
	}
	
    public boolean equals(Object o)
    {
    	if (o == null || !(o instanceof SipSessionIf))
			return false;
    	SipSessionIf session = ((SipSessionIf) o).getSession();
    	return this == session;
    }

	@Override
	public Session getSession()
	{
		return this;
	}

	@Override
	public String dump()
	{
		return ContainerLifeCycle.dump(this);
	}

	@Override
	public void dump(Appendable out, String indent) throws IOException
	{
		out.append(indent).append("+ ").append(getId()).append('\n');
		indent += "  ";
		printAttr(out, "created", new Date(getCreationTime()), indent);
		printAttr(out, "accessed", new Date(getLastAccessedTime()), indent);
		printAttr(out, "role", _role, indent);
		printAttr(out, "state", _state, indent);
		printAttr(out, "invalidateWhenReady", _invalidateWhenReady, indent);
		printAttr(out, "attributes", _attributes, indent);
		printAttr(out, "localParty", _localParty, indent);
		printAttr(out, "remoteParty", _remoteParty, indent);
		printAttr(out, "region", _region, indent);
		printAttr(out, "Call-ID", _callId, indent);
		printAttr(out, "linkedSessionId", _linkedSessionId, indent);
		printAttr(out, "subscriberURI", _subscriberURI, indent);
		printAttr(out, "handler", getHandler(), indent);
		if (_dialog != null)
			_dialog.dump(out, indent + "  ");
		if (!_serverTransactions.isEmpty() || !_clientTransactions.isEmpty())
		{
			out.append(indent).append("+ [transactions]\n");
			for (ServerTransaction tx : _serverTransactions)
				out.append(indent).append("  + ").append(tx.toString()).append("\n");
			for (ClientTransaction tx : _clientTransactions)
				out.append(indent).append("  + ").append(tx.toString()).append("\n");
		}
	}
	
	private void printAttr(Appendable sb, String name, Object value, String indent) throws IOException
	{
		sb.append(indent).append("- ").append(name).append(": ").append(String.valueOf(value)).append('\n');
	}
	
	public class DialogInfo implements ClientTransactionListener, ServerTransactionListener, Dumpable
	{
		protected long _localCSeq = 1;
		protected long _remoteCSeq = -1;
		protected long _localRSeq = 1;
		protected long _remoteRSeq = -1;

		protected URI _remoteTarget;
		protected LinkedList<String> _routeSet;
		
		private Object _serverInvites;
		private Object _clientInvites;
		
		protected boolean _secure = false;
		
		public SipServletRequest createRequest(String method)
		{
			SipMethod sipMethod = SipMethod.get(method);
			if (sipMethod == SipMethod.ACK || sipMethod == SipMethod.CANCEL)
				throw new IllegalArgumentException("Forbidden request method " + method);
		
			if (_state == State.TERMINATED)
				throw new IllegalStateException("Cannot create request in TERMINATED state");
			else if (_state == State.INITIAL && _role == Role.UAS)
				throw new IllegalStateException("Cannot create request in INITIAL state and UAS mode");
			
			return createRequest(sipMethod, method, _localCSeq++);
		}

		public SipRequest createRequest(SipRequest srcRequest)
		{
			SipRequest request = new SipRequest(srcRequest);
            
            request.getFields().remove(SipHeader.RECORD_ROUTE.asString());
            request.getFields().remove(SipHeader.VIA.asString());
            request.getFields().remove(SipHeader.CONTACT.asString());
            
            setDialogHeaders(request, _localCSeq++);
            		
			request.setSession(Session.this);
			
			return request;
		}
		
		public SipServletRequest createRequest(SipMethod sipMethod, long cseq)
		{
			return createRequest(sipMethod, sipMethod.asString(), cseq);
		}
		
		public SipServletRequest createRequest(SipMethod sipMethod, String method, long cseq)
		{
			SipRequest request = new SipRequest();
			request.setMethod(sipMethod, method);
			
			setDialogHeaders(request, cseq);
			
			request.setSession(Session.this);

			return request;
		}
		
		protected void setDialogHeaders(SipRequest request, long cseq)
		{
			SipFields fields = request.getFields();
			
			fields.set(SipHeader.FROM, _localParty.clone());
			fields.set(SipHeader.TO, _remoteParty.clone());
			
			if (_remoteTarget != null)
				request.setRequestURI((URI) _remoteTarget.clone());
			else if (request.getRequestURI() == null)
				request.setRequestURI(request.to().getURI());
			
			if (_routeSet != null)
			{
				fields.remove(SipHeader.ROUTE.asString());
				
				for (String route: _routeSet)
				{
					fields.add(SipHeader.ROUTE.asString(), route);
				}
			}
			fields.set(SipHeader.CALL_ID, _callId);
			fields.set(SipHeader.CSEQ, cseq + " " + request.getMethod());
			fields.set(SipHeader.MAX_FORWARDS, "70");
			
			if (request.needsContact())
				fields.set(SipHeader.CONTACT, getContact());
		}
		
		protected void createDialog(SipResponse response, boolean uac)
		{
			if (uac)
			{
				String tag = response.to().getTag();
                _remoteParty.setParameter(AddressImpl.TAG, tag);
                setRoute(response, true);
			}
			else
			{
                SipRequest request = (SipRequest) response.getRequest();
    			
				_remoteCSeq = request.getCSeq().getNumber();
				_secure = request.isSecure() && request.getRequestURI().getScheme().equals("sips");
				
				setRoute(request, false);
			}
		}
		
		protected void setRoute(SipMessage message, boolean reverse)
		{
			ListIterator<String> routes = message.getFields().getValues(SipHeader.RECORD_ROUTE.asString());
			_routeSet = new LinkedList<String>();
			while (routes.hasNext())
			{
				if (reverse)
					_routeSet.addFirst(routes.next());
				else
					_routeSet.addLast(routes.next());
			}
		}
		
		
		public void handleRequest(SipRequest request) throws IOException, SipException
		{
			if (request.getCSeq().getNumber() <= _remoteCSeq && !request.isAck() && !request.isCancel())
				throw new SipException(SipServletResponse.SC_SERVER_INTERNAL_ERROR, "Out of order request");
			
			_remoteCSeq = request.getCSeq().getNumber();
			if (isTargetRefresh(request))
				setRemoteTarget(request);
			
			if (!request.isAck())
			{
				if (isUA())
					addServerTransaction((ServerTransaction) request.getTransaction());
			}
			
			if (request.isAck())
			{
				ServerInvite invite = getServerInvite(_remoteCSeq, false);
				if (invite == null)
				{
					if (LOG.isDebugEnabled())
						LOG.debug("dropping ACK without INVITE context");
					request.setHandled(true);
				}
				else
				{
					if (invite.getResponse() != null)
						invite.ack();
					else // retrans or late
						request.setHandled(true);
				}
			}
			else if (request.isPrack())
			{
				RAck rack = null;
				
				try
				{
					rack = request.getRAck();
				}
				catch (Exception e)
				{
					throw new SipException(SipServletResponse.SC_BAD_REQUEST, e.getMessage());
				}
				
				ServerInvite invite = getServerInvite(rack.getCSeq(), false);
				
				if (invite == null || !invite.prack(rack.getRSeq()))
					throw new SipException(SipServletResponse.SC_CALL_LEG_DONE, "No matching 100 rel for RAck " + rack);
			}
		}
		
		
		@Override
		public void handleResponse(SipResponse response)
		{
			if (response.getStatus() == 100)
				return;
						
			if (!isSameDialog(response))
			{
				Session derived = _applicationSession.getSession(response);
				if (derived == null)
				{
					derived = _applicationSession.createDerivedSession(Session.this);
					if (_linkedSessionId != null)
					{
					  Session linkedSession = getLinkedSession();
					  // It's possible for _linkedSessionId != null, but linkedSession == null (linked session invalidated?).
					  if (linkedSession != null)
					  {
					    Session linkDerived = _applicationSession.createDerivedSession(linkedSession);
					    linkDerived.setLinkedSession(derived);
					    derived.setLinkedSession(linkDerived);
					  }
					}
				}
				derived._dialog.handleResponse(response);
				return;
			}
			
			response.setSession(Session.this);
			
			access();
			
			if (response.isInvite() && response.is2xx())
			{
				long cseq = response.getCSeq().getNumber();
				ClientInvite invite = getClientInvite(cseq, true);
				if (invite._2xx != null || invite._ack != null)
				{
					if (invite._ack != null)
					{
						try
						{
							ClientTransaction tx = (ClientTransaction) invite._ack.getTransaction();
							getServer().sendRequest(invite._ack, tx.getConnection());
						}
						catch (Exception e)
						{
							LOG.ignore(e);
						}
					}
					return;
				}
				else
				{
					invite._2xx = response;
				}
			}
			else if (response.isReliable1xx())
			{
				long rseq = response.getRSeq();
				if (_remoteRSeq != -1 && (_remoteRSeq + 1 != rseq))
				{
					if (LOG.isDebugEnabled())
						LOG.debug("Dropping 100rel with rseq {} since expecting {}", rseq, _remoteRSeq+1);
					return;
				}
				_remoteRSeq = rseq;
				long cseq = response.getCSeq().getNumber();
				ClientInvite invite = getClientInvite(cseq, true);
				invite.addReliable1xx(response);
			}
			else
				response.setCommitted(true);
			
			updateState(response, true);
			
			if (isTargetRefresh(response))
				setRemoteTarget(response);
			
			if (isValid())
				invokeServlet(response);
			
		}
		
		@Override
		public void handleCancel(ServerTransaction tx, SipRequest cancel)
				throws IOException
		{
			cancel.setSession(tx.getRequest().session());
			if (tx.isCompleted())
			{
				LOG.debug("ignoring late cancel {}", tx);
				cancel.createResponse(SipServletResponse.SC_CALL_LEG_DONE).send();
			}
			else
			{
				
				try
				{
					cancel.createResponse(SipServletResponse.SC_OK).send();
					tx.getRequest().createResponse(SipServletResponse.SC_REQUEST_TERMINATED).send();
					setState(State.TERMINATED);
				}
				catch (Exception e)
				{
					LOG.debug("failed to cancel request", e);
				}
				invokeServlet(cancel);
			}
		}

		public void sendResponse(SipResponse response, boolean reliable) throws IOException
		{
			ServerTransaction tx = (ServerTransaction) response.getTransaction();
			SipRequest request = (SipRequest) response.getRequest();
			
			boolean forked2xx = response.is2xx() && tx.getState() == Transaction.State.ACCEPTED;// TODO improve check
			
			if (tx.isCompleted() && !forked2xx)
				throw new IllegalStateException("no valid transaction");
			
			tx.setListener(new ScopedServerTransactionListener(Session.this, this));
						
			updateState(response, false);
			
			if (isTargetRefresh(request))
				setRemoteTarget(request);
			
			if (request.isInvite())
			{
				int status = response.getStatus();
				long cseq = response.getCSeq().getNumber();
				
				if (200 <= status && (status < 300))
				{
					DialogInfo.ServerInvite invite = _dialog.getServerInvite(cseq, true);
					invite.set2xx(response);
				}
				else if ((100 < status) && (status < 200)  && reliable)
				{
					DialogInfo.ServerInvite invite = _dialog.getServerInvite(cseq, true);
					
					long rseq = _localRSeq++;
					response.getFields().addString(SipHeader.REQUIRE.toString(), SipGrammar.REL_100, false);
					response.setRSeq(rseq);
					
					invite.addReliable1xx(response);
				}
				else if (status >= 300)
				{
					DialogInfo.ServerInvite invite = _dialog.getServerInvite(cseq, false);
					if (invite != null)
						invite.stop1xxRetrans();
				}
			}
			
			if (!forked2xx)
				tx.send(response);
			else
			{
				getServer().sendResponse(response, tx.getConnection());
			}
		}
		
		private boolean isTargetRefresh(SipMessage message)
		{
			if (message instanceof SipResponse)
			{
				SipResponse response = (SipResponse) message;
				if (response.getStatus() >= 300)
					return false;
			}
			// NOTIFY is target refresh according to bug 699 in RFC 6665
			return message.isInvite() || message.isSubscribe() || message.isNotify() 
					|| message.isNotify() || message.isMethod(SipMethod.REFER);
		}
		
		protected void setRemoteTarget(SipMessage message) 
		{
			Address contact = null;
			try
			{
				Field field = message.getFields().getField(SipHeader.CONTACT); 
				if (field != null)
					contact = field.asAddress();
			}
			catch (ServletParseException e)
			{
				LOG.debug(e);
			}
			if (contact != null)
				_remoteTarget = contact.getURI();
		}

		@Override
		public void customizeRequest(SipRequest request, SipConnection connection)
		{
			// TODO Auto-generated method stub
			
		}
		
		@Override
		public void transactionTerminated(Transaction transaction)
		{
			removeTransaction(transaction);
			if (transaction.isInvite())
			{
				long cseq = transaction.getRequest().getCSeq().getNumber();
				if (transaction.isServer()) 
					removeServerInvite(cseq);
				else
					removeClientInvite(cseq);
			}
		}
		
		private ClientInvite getClientInvite(long cseq, boolean create)
		{
			for (int i = LazyList.size(_clientInvites); i-->0;)
			{
				ClientInvite invite = (ClientInvite) LazyList.get(_clientInvites, i);
	            if (invite.getCSeq() == cseq)
	            	return invite;
			}
			if (create)
			{
				ClientInvite invite = new ClientInvite(cseq);
				_clientInvites = LazyList.add(_clientInvites, invite);
				
				if (LOG.isDebugEnabled())
					LOG.debug("added client invite context with cseq " + cseq);
				return invite;
			}
			return null;
		}
		
		private ServerInvite getServerInvite(long cseq, boolean create)
		{
			for (int i = LazyList.size(_serverInvites); i-->0;)
			{
				ServerInvite invite = (ServerInvite) LazyList.get(_serverInvites, i);
	            if (invite.getSeq() == cseq)
	            	return invite;
			}
			if (create)
			{
				ServerInvite invite = new ServerInvite(cseq);
				_serverInvites = LazyList.add(_serverInvites, invite);
				
				if (LOG.isDebugEnabled())
					LOG.debug("added server invite context with cseq " + cseq);
				
				return invite;
			}
			return null;
		}
		
		private ClientInvite removeClientInvite(long cseq)
		{
			for (int i = LazyList.size(_clientInvites); i-->0;)
			{
				ClientInvite invite = (ClientInvite) LazyList.get(_clientInvites, i);
				if (invite.getCSeq() == cseq)
				{
					_clientInvites = LazyList.remove(_clientInvites, i);
            	
					if (LOG.isDebugEnabled())
						LOG.debug("removed client invite context for cseq " + cseq);
					return invite;
				}
			}
			return null;
	    }
		
		private ServerInvite removeServerInvite(long cseq)
		{
			for (int i = LazyList.size(_serverInvites); i-->0;)
			{
				ServerInvite invite = (ServerInvite) LazyList.get(_serverInvites, i);
				if (invite.getSeq() == cseq)
				{
					_serverInvites = LazyList.remove(_serverInvites, i);
            	
					if (LOG.isDebugEnabled())
						LOG.debug("removed server invite context for cseq " + cseq);
					return invite;
				}
			}
			return null;
	    }
		
		public List<SipServletResponse> getUncommitted1xx(UAMode mode)
		{
			List<SipServletResponse> list = null;
			if (mode == UAMode.UAS)
			{
				for (int i = LazyList.size(_serverInvites); i-->0;)
				{
					ServerInvite invite = (ServerInvite) LazyList.get(_serverInvites, i);
					for (int j = LazyList.size(invite._reliable1xxs); j-->0;)
					{
						ServerInvite.Reliable1xx reliable1xx = (ServerInvite.Reliable1xx) LazyList.get(invite._reliable1xxs, j);
						SipResponse response = reliable1xx.getResponse();
						if (response != null && !response.isCommitted())
						{
							if (list == null)
								list = new ArrayList<SipServletResponse>();
							list.add(response);
						}
					}
				}
			}
			else
			{
				for (int i = LazyList.size(_clientInvites); i-->0;)
				{
					ClientInvite invite = (ClientInvite) LazyList.get(_clientInvites, i);
					for (int j = LazyList.size(invite._reliable1xxs); j-->0;)
					{
						ClientInvite.Reliable1xxClient reliable1xx = (ClientInvite.Reliable1xxClient) LazyList.get(invite._reliable1xxs, j);
						SipResponse response = reliable1xx.getResponse();
						if (response != null && !response.isCommitted())
						{
							if (list == null)
								list = new ArrayList<SipServletResponse>();
							list.add(response);
						}
					}
				}
			}
			if (list == null)
				return Collections.emptyList();
			else
				return list;
		}
		
		public List<SipServletResponse> getUncommitted2xx(UAMode mode)
		{
			List<SipServletResponse> list = null;
			if (mode == UAMode.UAS)
			{
				for (int i = LazyList.size(_serverInvites); i-->0;)
				{
					ServerInvite invite = (ServerInvite) LazyList.get(_serverInvites, i);
					SipResponse response = invite.getResponse();
					if (response != null && !response.isCommitted())
					{
						if (list == null)
							list = new ArrayList<SipServletResponse>();
						list.add(response);
					}
				}
			}
			else
			{
				for (int i = LazyList.size(_clientInvites); i-->0;)
				{
					ClientInvite invite = (ClientInvite) LazyList.get(_clientInvites, i);
					if (invite._2xx != null && !invite._2xx.isCommitted())
					{
						if (list == null)
							list = new ArrayList<SipServletResponse>();
						list.add(invite._2xx);
					}
				}
			}
			if (list == null)
				return Collections.emptyList();
			else
				return list;
		}
		
		@Override
		public String dump()
		{
			return ContainerLifeCycle.dump(this);
		}

		@Override
		public void dump(Appendable sb, String indent) throws IOException
		{
			sb.append(indent).append("+ [ua]\n");
			indent += "  ";
			printAttr(sb, "local CSeq", _localCSeq, indent);
			printAttr(sb, "Remote CSeq", _remoteCSeq, indent);
			printAttr(sb, "Remote Target", _remoteTarget, indent);
			printAttr(sb, "route Set", _routeSet, indent);
			printAttr(sb, "Secure", _secure, indent);
			printAttr(sb, "local RSeq", _localRSeq, indent);
			printAttr(sb, "Remote RSeq", _remoteRSeq, indent);
			if (LazyList.size(_serverInvites) != 0)
			{
				sb.append(indent).append("+ [serveur INVITE]\n");
				for (int i = 0; i< LazyList.size(_serverInvites); i++)
					sb.append(indent + "  ").append("- ").append(LazyList.get(_serverInvites, i).toString()).append('\n');
			}
			if (LazyList.size(_clientInvites) != 0)
			{
				sb.append(indent).append("+ [client INVITE]\n");
				for (int i = 0; i< LazyList.size(_clientInvites); i++)
					sb.append(indent + "  ").append("- ").append(LazyList.get(_clientInvites, i).toString()).append('\n');
			}
		}
		
		class ClientInvite
		{
			private long _cseq;
			private SipRequest _ack;
			private SipResponse _2xx;
			private Object _reliable1xxs;
		
			public ClientInvite(long cseq) { _cseq = cseq; }
			public long getCSeq() { return _cseq; }
			
			public void addReliable1xx(SipResponse response)
			{
				Reliable1xxClient reliable1xx = new Reliable1xxClient(response);
				_reliable1xxs = LazyList.add(_reliable1xxs, reliable1xx);
			}
			
			public boolean prack(long rseq)
			{
				for (int i = LazyList.size(_reliable1xxs); i-->0;)
				{
					Reliable1xxClient reliable1xx = (Reliable1xxClient) LazyList.get(_reliable1xxs, i);
					if (reliable1xx.getRSeq() == rseq)
					{
						_reliable1xxs = LazyList.remove(_reliable1xxs, i);
						return true;
					}
				}
				return false;
			}
			
			@Override 
			public String toString()
			{
				StringBuilder sb = new StringBuilder();
				sb.append("CSeq: ").append(_cseq);
				if (LazyList.size(_reliable1xxs) != 0)
				{
					sb.append(", RSeq={");
					for (int i = 0; i < LazyList.size(_reliable1xxs); i++)
						sb.append(LazyList.get(_reliable1xxs, i));
					sb.append("}");
				}
				return sb.toString();
			}
			
			class Reliable1xxClient
			{
				private SipResponse _1xx;
				
				public Reliable1xxClient(SipResponse response) { _1xx = response; }
				public long getRSeq() { return _1xx.getRSeq(); }
				public SipResponse getResponse() { return _1xx; }
				
				@Override public String toString(){ return String.valueOf(getRSeq());}
			}
		}
		
		abstract class ReliableResponse
		{
			private static final int TIMER_RETRANS = 0;
			private static final int TIMER_WAIT_ACK = 1;
			
			private long _seq;
			protected SipResponse _response;
			private TimerTask[] _timers;
			private long _retransDelay = TransactionImpl.__T1;
			
			public ReliableResponse(long seq) { _seq = seq; }
			
			public long getSeq() { return _seq; }
			public SipResponse getResponse() { return _response; }
			
			public void startRetrans(SipResponse response)
			{
				_response = response;
				
				_timers = new TimerTask[2];
				_timers[TIMER_RETRANS] = appSession().getSessionManager()
						.schedule(new Timer(TIMER_RETRANS), _retransDelay);
				_timers[TIMER_WAIT_ACK] = appSession().getSessionManager()
						.schedule(new Timer(TIMER_WAIT_ACK), 64 * TransactionImpl.__T1);
			}
			
			public void stopRetrans()
			{
				cancelTimer(TIMER_RETRANS);
				_response = null;
			}
			
			public void ack()
			{
				stopRetrans();
				cancelTimer(TIMER_WAIT_ACK);
			}
			
			private void cancelTimer(int id)
			{
				TimerTask timer = _timers[id];
				if (timer != null)
					timer.cancel();
				_timers[id] = null;
			}
			
			/** 
			 * @return the delay for the next retransmission, -1 to stop retransmission
			 */
			public abstract long retransmit(long delay);
			public abstract void noAck();
			
			protected void timeout(int id)
			{
				switch(id)
				{
				case TIMER_RETRANS:
					if (_response != null)
					{
						_retransDelay = retransmit(_retransDelay);
						if (_retransDelay > 0)
							_timers[TIMER_RETRANS] = appSession().getSessionManager().schedule(
									new Timer(TIMER_RETRANS), _retransDelay);
					}
					break;
				case TIMER_WAIT_ACK:
					cancelTimer(TIMER_RETRANS);
					if (_response != null)
					{
						noAck();
						_response = null;
					}
					break;
				default:
					throw new IllegalArgumentException("unknown id " + id);
				}
			}
			
			@Override public String toString(){ return String.valueOf(getSeq());}
			
			class Timer implements Runnable
			{
				private int _id;
				
				public Timer(int id) { _id = id; }
				public void run() { timeout(_id); }
				@Override public String toString() { return _id == TIMER_RETRANS ? "retrans" : "wait-ack"; }
			}
		}
		
		class ServerInvite extends ReliableResponse
		{
			private Object _reliable1xxs;
			
			public ServerInvite(long cseq) { super(cseq); }
			
			public void set2xx(SipResponse response)
			{
				stop1xxRetrans();
				startRetrans(response);
			}
			
			public void addReliable1xx(SipResponse response)
			{
				Reliable1xx reliable1xx = new Reliable1xx(response.getRSeq());
				_reliable1xxs = LazyList.add(_reliable1xxs, reliable1xx);
				reliable1xx.startRetrans(response);
			}
			
			public boolean prack(long rseq)
			{
				for (int i = LazyList.size(_reliable1xxs); i-->0;)
				{
					Reliable1xx reliable1xx = (Reliable1xx) LazyList.get(_reliable1xxs, i);
					if (reliable1xx.getSeq() == rseq)
					{
						reliable1xx.ack();
						_reliable1xxs = LazyList.remove(_reliable1xxs, i);
						return true;
					}
				}
				return false;
			}
			
			public void stop1xxRetrans()
			{
				for (int i = LazyList.size(_reliable1xxs); i-->0;)
				{
					Reliable1xx reliable1xx = (Reliable1xx) LazyList.get(_reliable1xxs, i);
					reliable1xx.stopRetrans();
				}
			}
			
			public void noAck() 
			{
				if (isValid())
				{
					List<SipErrorListener> listeners = _applicationSession.getContext().getSipErrorListeners();
					if (!listeners.isEmpty())
						 _applicationSession.getContext().fire(_applicationSession, listeners, ApplicationSession.__noAck, 
								 new SipErrorEvent(getResponse().getRequest(), getResponse()));
				}
			}

			public long retransmit(long delay) 
			{
				SipResponse response = getResponse();
				ServerTransaction tx = (ServerTransaction) response.getTransaction();
				if (tx != null)
					tx.send(response);
				else
				{
					try
					{
				    	SipRequest request = (SipRequest) response.getRequest();
				    	if (request != null)
				    		getServer().sendResponse(response, request.getConnection());
					}
					catch (IOException e) {
						LOG.debug(e);
					}
				}
				return Math.min(delay*2, TransactionImpl.__T2);
			}
			
			@Override 
			public String toString()
			{
				StringBuilder sb = new StringBuilder();
				sb.append("CSeq: ").append(getSeq());
				if (LazyList.size(_reliable1xxs) != 0)
				{
					sb.append(", RSeq={");
					for (int i = 0; i < LazyList.size(_reliable1xxs); i++)
						sb.append(LazyList.get(_reliable1xxs, i));
					sb.append("}");
				}
				return sb.toString();
			}
			
			class Reliable1xx extends ReliableResponse
			{
				public Reliable1xx(long rseq) { super(rseq); }
				
				public long retransmit(long delay)
				{
					ServerTransaction tx = (ServerTransaction) getResponse().getTransaction();
					if (tx.getState() == Transaction.State.PROCEEDING)
					{
						tx.send(getResponse());
						return delay*2;
					}
					return -1;
				}
				
				public void noAck()
				{
					if (isValid())
					{
						if (isValid())
						{
							List<SipErrorListener> listeners = _applicationSession.getContext().getSipErrorListeners();
							if (!listeners.isEmpty())
								 _applicationSession.getContext().fire(_applicationSession, listeners, ApplicationSession.__noPrack, 
										 new SipErrorEvent(getResponse().getRequest(), getResponse()));
						}
					}
				}
			}
		}
	}
	
}
