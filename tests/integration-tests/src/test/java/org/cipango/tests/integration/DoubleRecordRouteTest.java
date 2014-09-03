// ========================================================================
// Copyright 2011-2012 NEXCOM Systems
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


import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Iterator;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

import org.cipango.client.Call;
import org.cipango.client.SipClient;
import org.cipango.client.SipHeaders;
import org.cipango.client.SipMethods;
import org.cipango.client.test.UaRunnable;
import org.cipango.tests.UaTestCase;
import org.cipango.tests.integration.category.NotCompliantV2;
import org.junit.Test;
import org.junit.experimental.categories.Category;


@Category(NotCompliantV2.class)
public class DoubleRecordRouteTest extends UaTestCase
{
	/**
	 * Ensure that if different connectors are used, there are two record routes.
	 */
	@Test
	public void testUdpToTcp() throws Throwable 
	{
		Endpoint bob = createEndpoint("bob");
		UaRunnable callB = new UaRunnable(bob.getUserAgent(SipClient.Protocol.TCP))
		{
			@Override
			public void doTest() throws Throwable
			{
				SipServletRequest request = waitForInitialRequest();
				assertThat(request.getMethod(), is(SipMethods.INVITE));
				Iterator<Address> it = request.getAddressHeaders(SipHeaders.RECORD_ROUTE);
				Address addr = it.next();
				assertThat(((SipURI) addr.getURI()).getTransportParam(), is("tcp"));
				assertThat(it.hasNext(), is(true));
				addr = it.next();
				assertThat(((SipURI) addr.getURI()).getTransportParam(), is(nullValue()));
				assertThat(it.hasNext(), is(false));
				SipServletResponse response = _ua.createResponse(request, SipServletResponse.SC_OK);
				response.send();
				
				assertThat(_dialog.waitForRequest().getMethod(), is(SipMethods.ACK));
				request = _dialog.waitForRequest();
				assertThat(request.getMethod(), is(SipMethods.BYE));
				_ua.createResponse(request, SipServletResponse.SC_OK).send();
			}
		};


		callB.start();
		
		SipServletRequest request = _ua.createRequest(SipMethods.INVITE, bob.getUri());
		request.setRequestURI(bob.getContact().getURI());
		Call callA = _ua.createCall(request);

		SipServletResponse response = callA.waitForResponse();
        assertThat(response, isSuccess());
        Iterator<String> it = response.getHeaders("mode");
        assertThat(it.hasNext(), is(true));
        it.next();
        assertThat(it.hasNext(), is(false));

        callA.createAck().send();
        Thread.sleep(200);
        callA.createBye().send();
        assertThat(callA.waitForResponse(), isSuccess());
		callB.assertDone();

	}
	
	/**
	 * Ensure that if the same connector is used, there is only one record route.
	 */
	@Test
	public void testUdpToUdp() throws Throwable 
	{
		Endpoint bob = createEndpoint("bob");
		UaRunnable callB = new UaRunnable(bob.getUserAgent())
		{
			@Override
			public void doTest() throws Throwable
			{
				SipServletRequest request = waitForInitialRequest();
				assertThat(request.getMethod(), is(equalTo(SipMethods.INVITE)));
				Iterator<Address> it = request.getAddressHeaders(SipHeaders.RECORD_ROUTE);
				Address addr = it.next();
				assertThat(((SipURI) addr.getURI()).getTransportParam(), is(nullValue()));
				assertThat(it.hasNext(), is(false));
		        
				_ua.createResponse(request, SipServletResponse.SC_OK).send();
				assertThat(_dialog.waitForRequest().getMethod(), is(equalTo(SipMethods.ACK)));
				request = _dialog.waitForRequest();
				assertThat(request.getMethod(), is(equalTo(SipMethods.BYE)));
				_ua.createResponse(request, SipServletResponse.SC_OK).send();
			}
		};
		
		callB.start();
		
		SipServletRequest request = _ua.createRequest(SipMethods.INVITE, bob.getUri());
		request.setRequestURI(bob.getContact().getURI());
		Call callA = _ua.createCall(request);

        assertThat(callA.waitForResponse(), isSuccess());
        callA.createAck().send();
        Thread.sleep(200);
        callA.createBye().send();
        assertThat(callA.waitForResponse(), isSuccess());
		callB.assertDone();
	}
}
