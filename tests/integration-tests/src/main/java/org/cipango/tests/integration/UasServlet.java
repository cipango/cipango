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
package org.cipango.tests.integration;

import static org.junit.Assert.*;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipServlet;

import org.cipango.tests.AbstractServlet;

@SuppressWarnings("serial")
@SipServlet (name="org.cipango.tests.integration.UasTest")
public class UasServlet extends AbstractServlet
{

	public void testLateInvite200NoRinging(SipServletRequest request) throws Throwable
	{
		testLateInvite200(request);
	}
	
	public void testLateInvite200(SipServletRequest request) throws Throwable
	{
		String method = request.getMethod();
		if ("INVITE".equals(method))
		{
			if ("true".equals(request.getTo().getURI().getParameter("ringing")))
				request.createResponse(SipServletResponse.SC_RINGING).send();
			Thread.sleep(1000);
			request.createResponse(SipServletResponse.SC_OK).send();
		}
		else if ("ACK".equals(method))
		{
			// Do nothing
		}
		else if ("CANCEL".equals(method))
		{
			fail("CANCEL is notified to servlet");
		}
		else if ("BYE".equals(method))
		{
			request.createResponse(SipServletResponse.SC_OK).send();
			request.getApplicationSession().invalidate();
		}
	}
	

	public void testBigResponse(SipServletRequest request) throws Exception
	{
		SipServletResponse response = request.createResponse(SipServletResponse.SC_OK);
		StringBuilder sb = new StringBuilder();
		int lines = 0;
		for (int i = 0; i < 60000; i++)
		{
			sb.append((char) ('a' + lines));
			if ((i+1) % 200 == 0)
			{
				sb.append("\r\n");
				i += 2;
				lines++;
				if (lines > 25)
					lines = 0;
			}
		}
		response.setContent(sb.toString(), "text/plain");
		response.send();
	}
	
	public void testEarlyCancel(SipServletRequest request) throws Exception
	{
		//System.out.println("testEarlyCancel: Received " + request.getMethod());
	}
}
