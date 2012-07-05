package org.cipango.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

public class UserAgent 
{
	private Address _aor;
	private Address _contact;
	private List<Credentials> _credentials = new ArrayList<Credentials>();
	private SipFactory _factory;
	private Thread _registrationThread;
	private RegistrationTask _registrationTask;
	private Registration.Listener _registrationListener;
	private long _timeout;

	public UserAgent(Address aor)
	{
		_aor = aor;
	}
	
	public SipFactory getFactory()
	{
		return _factory;
	}
	
	public void setFactory(SipFactory factory)
	{
		_factory = factory;
	}
	
	/**
	 * @return the registrationListener
	 */
	public Registration.Listener getRegistrationListener()
	{
		return _registrationListener;
	}

	/**
	 * @param registrationListener the registrationListener to set
	 */
	public void setRegistrationListener(Registration.Listener listener)
	{
		_registrationListener = listener;
		if (_registrationTask != null)
			_registrationTask.setListener(listener);
	}

	public void setContact(Address contact)
	{
		_contact = contact;
		
		// TODO: Should current registration be dropped or unregistration called
		//       for the previous contact?
	}
	
	public Address getContact()
	{
		return _contact;
	}
	
	public void handleInitialRequest(SipServletRequest request)
	{
		// TODO
	}
		
	/**
	 * Synchronously registers this <code>UserAgent</code>'s contact with the
	 * given expiration value.
	 * 
	 * This method returns either when success, failure or request timeout is
	 * encountered, and the listener, if any, is notified as well.
	 * Authentication is handled automatically if credentials were provided to
	 * this <code>UserAgent</code>.
	 * 
	 * On success, the registration is maintained in the background by sending
	 * regularly re-REGISTER requests. Subsequent events (end of registration,
	 * failure to register) will only be reported through the listener.
	 * 
	 * @param expires
	 *            the expiration time demanded by this <code>UserAgent</code>.
	 * @return <code>true</code> if registration was successful,
	 *         <code>false</code> otherwise.
	 * @throws IOException
	 * @throws ServletException
	 * @see Registration#register(javax.servlet.sip.URI, int)
	 */
	public boolean register(int expires) throws IOException, ServletException
	{
		boolean result;
		
		if (!_aor.getURI().isSipURI())
			throw new ServletException(_aor.toString() + " is not a SIP URI. Cannot register");

		if (_registrationThread == null)
		{
			Registration registration = new Registration((SipURI) _aor.getURI());
			registration.setFactory(_factory);
			registration.setCredentials(_credentials);
			registration.setTimeout(_timeout);
			_registrationTask = new RegistrationTask(registration, _contact);
			_registrationTask.setListener(_registrationListener);
			_registrationThread = new Thread(_registrationTask, "registration-task");	
		}
				
		result = _registrationTask.getRegistration().register(_contact.getURI(), expires);
		if (result)
			_registrationThread.run();
		else
			_registrationThread.interrupt();
		
		return result;
	}
	
	/**
	 * Synchronously unregisters this <code>UserAgent</code>'s contact.
	 * 
	 * After this method returns, the contact that was previously registered is
	 * unregistered, and no background activity is sustained to register it
	 * again.
	 * 
	 * @return <code>true</code> if unregistration was successful,
	 *         <code>false</code> otherwise or if unregistration was a no-op.
	 * @throws IOException
	 * @throws ServletException
	 */
	public boolean unregister() throws IOException, ServletException
	{
		if (_registrationThread != null)
			_registrationThread.interrupt();

		if (_registrationTask != null)
			return _registrationTask.getRegistration().unregister(null);
		return false;
	}
		
//	Call call(URI remote)
//	{
//
//	}
	
	public SipServletRequest createRequest(String method, String to) throws ServletParseException
	{
		SipApplicationSession appSession = _factory.createApplicationSession();
		return _factory.createRequest(appSession, method, getAor(), _factory.createAddress(to));
	}
	
	public SipServletRequest createRequest(String method, Address to)
	{
		SipApplicationSession appSession = _factory.createApplicationSession();
		return _factory.createRequest(appSession, method, getAor(), to);
	}
	
	public SipServletRequest createRequest(String method, UserAgent agent)
	{
		SipApplicationSession appSession = _factory.createApplicationSession();
		return _factory.createRequest(appSession, method, getAor(), agent.getAor());
	}


	/**
	 * Creates a request with the given method and destination URI and waits for the final response.
	 * @param request
	 * @return the final response or <code>null</code> if no response has been received before timeout.
	 * @throws IOException
	 * @throws ServletException 
	 * @see {@link #setTimeout()}
	 */
	public SipServletResponse sendSynchronous(String method, Address to) throws IOException, ServletException
	{
		return sendSynchronous(createRequest(method, to));
	}

	/**
	 * Sends <code>request</code> and waits for the final response.
	 * @param request
	 * @return the final response or <code>null</code> if no response has been received before timeout.
	 * @throws IOException
	 * @throws ServletException 
	 * @see {@link #setTimeout()}
	 */
	public SipServletResponse sendSynchronous(SipServletRequest request) throws IOException, ServletException
	{
		RequestHandler handler = new RequestHandler(request, this);
		handler.send();
		return handler.waitFinalResponse();
	}
	
	/**
	 * Sends <code>request</code>.
	 * @param request
	 * @return the request handler.
	 * @throws IOException
	 * @throws ServletException 
	 */
	public RequestHandler send(SipServletRequest request) throws IOException, ServletException
	{
		RequestHandler handler = new RequestHandler(request, this);
		handler.send();
		return handler;
	}
	
	@SuppressWarnings("unchecked")
	public List<SipServletResponse> getResponses(SipServletRequest request)
	{
		return (List<SipServletResponse>) request.getAttribute(SipServletResponse.class.getName());
	}
	
	public long getTimeout()
	{
		return _timeout;
	}

	public void setTimeout(long timeout)
	{
		_timeout = timeout;
	}

	public List<Credentials> getCredentials()
	{
		return _credentials;
	}

	public void addCredentials(Credentials credentials)
	{
		_credentials.add(credentials);
	}

	public Address getAor()
	{
		return _aor;
	}
	
	public String getDomain()
	{
		if (_aor.getURI().isSipURI())
			return ((SipURI) _aor.getURI()).getHost();
		return null;
	}
}
