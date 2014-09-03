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
package org.cipango.client.test;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.junit.Assert;

import org.cipango.client.Dialog;
import org.cipango.client.MessageHandler;
import org.cipango.server.session.ApplicationSession;
import org.cipango.server.session.SessionManager;
import org.cipango.server.session.SessionManager.ApplicationSessionScope;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class UaRunnable extends Thread
{
	private Logger LOG = Log.getLogger(UaRunnable.class);
	
	protected TestAgent _ua;
	protected Dialog _dialog;
	private Throwable _e;
	private Boolean _isDone = Boolean.FALSE;

	public UaRunnable(TestAgent userAgent)
	{
		_ua = userAgent;
		_ua.setDefaultHandler(new InitialRequestHandler());
		_dialog = _ua.customize(new Dialog());
		_dialog.setCredentials(_ua.getCredentials());
		_dialog.setTimeout(_ua.getTimeout());
	}
	
	public void run()
	{
		try
		{
			doTest();
		}
		catch (Throwable e)
		{
			LOG.warn("Got throwable for agent " + _ua, e);
			_e = e;
		}
		finally
		{
			_isDone = true;
			synchronized (_isDone)
			{
				_isDone.notify();
			}
		}
	}
	
	public abstract void doTest() throws Throwable;
	
	public Throwable getException()
	{
		return _e;
	}

	public boolean isDone()
	{
		return _isDone;
	}

	public String getUserName()
	{
		return _ua.getAlias();
	}
	
	public void assertDone() throws Exception
	{
		if (_e != null)
			throwException();
		if (_isDone)
			return;
		
		synchronized (_isDone)
		{
			try
			{
				_isDone.wait(2000);
			}
			catch (InterruptedException e)
			{
			}
		}
		if (_e != null)
			throwException();
		if (!_isDone)
			Assert.fail(getUserName() + " not done");
	}
	
	private void throwException() throws Exception
	{
		if (_e != null)
		{
			if (_e instanceof Exception)
				throw (Exception) _e;
			if (_e instanceof Error)
				throw (Error) _e;
		}
	}
	
	/**
	 * Open a scope on <code>session</code>. This ensure while scope is not close that any other 
	 * thread could access to the <code>session</code>.
	 * It could be useful to manage test with fork multiple success responses.
	 */
	public ApplicationSessionScope openScope(SipApplicationSession session)
	{
		ApplicationSession appSession = ((SessionManager.AppSessionIf) session).getAppSession();
		return appSession.getSessionManager().openScope(appSession);
	}

	public SipServletRequest waitForInitialRequest()
	{
		synchronized(_dialog)
		{
			try { _dialog.wait(_ua.getTimeout()); } catch (InterruptedException e) { }
		}
		return (SipServletRequest) _dialog.getSession().getAttribute(
				Dialog.INITIAL_REQUEST_ATTRIBUTE);
	}

	public Dialog getDialog()
	{
		return _dialog;
	}
	
	public class InitialRequestHandler implements MessageHandler
	{
		public void handleRequest(SipServletRequest request)
				throws IOException, ServletException
		{
			_dialog.accept(request);
			synchronized(_dialog)
			{
				_dialog.notifyAll();
			}
		}

		public void handleResponse(SipServletResponse response)
				throws IOException, ServletException
		{
		}
	}
}
