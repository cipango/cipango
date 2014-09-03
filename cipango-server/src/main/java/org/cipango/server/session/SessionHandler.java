// ========================================================================
// Copyright 2008-2012 NEXCOM Systems
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
import java.lang.reflect.Method;
import java.util.EventListener;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.TooManyHopsException;

import org.cipango.server.SipMessage;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.handler.SipHandlerWrapper;
import org.cipango.server.session.SessionManager.ApplicationSessionScope;
import org.cipango.server.sipapp.SipAppContext;
import org.cipango.server.transaction.ServerTransaction;
import org.cipango.server.transaction.TransactionImpl;
import org.cipango.server.util.ExceptionUtil;
import org.cipango.sip.SipException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


@ManagedObject("Session  handler")
public class SessionHandler extends SipHandlerWrapper
{
	public static final String APP_ID = "appid";
	private static final int MAX_TRY_LOCK_TIME = 64 * TransactionImpl.__T1 / 1000; // 32 seconds
	private static final Logger LOG = Log.getLogger(SessionHandler.class);
	private SessionManager _sessionManager;
    private Method _sipApplicationKeyMethod;
	
	public SessionHandler()
	{
		this(new SessionManager());
	}
	
	public SessionHandler(SessionManager sessionManager)
	{
		setSessionManager(sessionManager);
	}
	
	
	@Override
	protected void doStart() throws Exception
	{
		_sessionManager.setSipAppContext(SipAppContext.getCurrentContext());
		super.doStart();
	}
	
	public void setSessionManager(SessionManager sessionManager)
	{
		if (isStarted())
	        throw new IllegalStateException();
		updateBean(_sessionManager, sessionManager);
		_sessionManager = sessionManager;
	}
	
	public void handle(SipMessage message) throws IOException, ServletException 
	{
		if (message instanceof SipRequest)
			handleRequest((SipRequest) message);
		else
		{
			ApplicationSessionScope scope = getSessionManager().openScope(message.appSession(), MAX_TRY_LOCK_TIME);
			if (!scope.isLocked())
			{
				LOG.warn("Drop message {} as could not lock the session after {} seconds (the transaction should be now expired)",
						message, MAX_TRY_LOCK_TIME);
				return;
			}
			try
			{
				_handler.handle(message);
			}
			finally
			{
				scope.close();
			}
		}
	}
	
	public void handleRequest(SipRequest request) throws IOException, ServletException 
	{
		ApplicationSession appSession = null;
		Session session = request.session();
		boolean forwarded = session != null;
		// The session can be not null in case of servletContext.getNamedDispatcher().forward()
		if (!forwarded)
		{									
			if (request.isInitial())
			{
				
				if (_sipApplicationKeyMethod != null)
				{
					try
					{
						String key = (String) _sipApplicationKeyMethod.invoke(null, request);
						if (LOG.isDebugEnabled())
							LOG.debug("routing initial request to key {}", key);
						
						if (key != null)
						{
							String id = _sessionManager.getApplicationSessionIdByKey(key);
							appSession = _sessionManager.getApplicationSession(id);
							if (appSession == null)
								appSession = _sessionManager.createApplicationSession(id);
						}
					}
					catch (Exception e)
					{
						LOG.debug("failed to get SipApplicationKey", e);
					}	
				}
				
				if (appSession == null)
					appSession = _sessionManager.createApplicationSession();
				
				session = appSession.createSession(request);
				session.addServerTransaction((ServerTransaction) request.getTransaction());
				session.setSubscriberURI(request.getSubscriberURI());
				session.setRegion(request.getRegion());
			}
			else
			{
				String appId = request.getParameter(APP_ID);
				if (appId == null)
				{
					String tag = request.getToTag();
					int i = tag.indexOf('-');
					if (i != -1)
						appId = tag.substring(0,  i);
				}
				if (appId == null)
				{
					notFound(request, "No Application Session Identifier");
					return;
				}
				
				appSession = _sessionManager.getApplicationSession(appId);
				
				if (appSession == null)
				{
					notFound(request, "No Application Session");
					return;
				}
				
				session = appSession.getSession(request);
			}
			
			if (session == null)
			{
				notFound(request, "No SIP Session");
				return;
			}			
		}
		
		ApplicationSessionScope scope = _sessionManager.openScope(session.appSession());
		
		try
		{
			if (!forwarded)
			{
				if (request.isInvite()) 
		        { 
					SipResponse response = (SipResponse) request.createResponse(SipServletResponse.SC_TRYING);
					((ServerTransaction) request.getTransaction()).send(response);
				}
				session.access();
				request.setSession(session);
				
				if (!request.isInitial() && session.isUA())
					session.getUa().handleRequest(request);
			}

			if (!request.isHandled())
				_handler.handle(request);
			
			if (!request.isInitial() && session.isProxy()  && !request.isCancel())
			{
				Proxy proxy = request.getProxy();
				proxy.proxyTo(request.getRequestURI());
			}
		}
		catch (Throwable e)
		{
        	if (!request.isAck() && !request.isCommitted())
        	{
        		int code = SipServletResponse.SC_SERVER_INTERNAL_ERROR;
        		if (e instanceof SipException)
        			code = ((SipException) e).getStatus();
        		else if (e instanceof TooManyHopsException)
        			code = SipServletResponse.SC_TOO_MANY_HOPS;
        		
        		SipServletResponse response;
        		if (code == SipServletResponse.SC_SERVER_INTERNAL_ERROR)
        		{
        			response = request.createResponse(
    	        			SipServletResponse.SC_SERVER_INTERNAL_ERROR,
    	        			"Error in handler: " + e.getMessage());
        			ExceptionUtil.fillStackTrace(response, e);
        		}
        		else
        		{
        			response = request.createResponse(code);
        		}
	        	response.send();
        	}
        	else
        	{
        		LOG.debug(e);
        	}
		}
		finally
		{
			scope.close();
		}
	}
	
	protected void notFound(SipRequest request, String reason)
	{
		if (!request.isAck())
			try
			{
				// In this case, there is no session, so could not use response.send()
				SipResponse response = (SipResponse) request.createResponse(SipServletResponse.SC_CALL_LEG_DONE, reason);
				// FIXME to tag
				((ServerTransaction) response.getTransaction()).send(response);
			}
			catch (Exception e)
			{
				LOG.ignore(e);
			}
	}
	
	protected String getApplicationId(String s)
	{
		return s.substring(s.lastIndexOf('-'));
	}

	@ManagedAttribute("Session manager")
	public SessionManager getSessionManager()
	{
		return _sessionManager;
	}
	
    public void addEventListener(EventListener listener)
    {
        _sessionManager.addEventListener(listener);
    }
    

    public void removeEventListener(EventListener listener)
    {
        _sessionManager.removeEventListener(listener);
    }

    public void clearEventListeners()
    {
        _sessionManager.clearEventListeners();
    }
    
	public Method getSipApplicationKeyMethod()
	{
		return _sipApplicationKeyMethod;
	}

	public void setSipApplicationKeyMethod(Method sipApplicationKeyMethod)
	{
		_sipApplicationKeyMethod = sipApplicationKeyMethod;
	}
	
}
