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
package org.cipango.tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

import org.cipango.client.SipClient;
import org.cipango.client.SipClient.Protocol;
import org.cipango.client.SipHeaders;
import org.cipango.client.SipMethods;
import org.cipango.client.test.TestAgent;
import org.hamcrest.Factory;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

public abstract class UaTestCase extends Assert
{
	@Rule 
	public TestName _name = new TestName();
	
	// Ensure test cannot be longer than 1 minute
	@Rule
    public Timeout _globalTimeout= new Timeout(60000);
	
	private List<Endpoint> _endpoints = new ArrayList<Endpoint>();
	private int _nextPort;
	
	protected SipClient _sipClient;
	protected TestAgent _ua;
	protected Properties _properties;
	
	public UaTestCase()
	{
		_properties = new Properties();
		try
		{
			_properties.load(getClass().getClassLoader()
					.getResourceAsStream("commonTest.properties"));
			
			_properties.putAll(System.getProperties());
			_nextPort = getLocalPort() + 1;
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public int getTimeout()
	{
		return getInt("local.timeout");
	}
	
	public int getInt(String property)
	{
		return Integer.parseInt(_properties.getProperty(property));
	}

	public String getDomain()
	{
		return _properties.getProperty("local.domain");	
	}
	
	public String getLocalHost() throws UnknownHostException
	{
		String host = _properties.getProperty("local.host");
		if (host == null || "".equals(host.trim()))
		{
			host = InetAddress.getLocalHost().getHostAddress();
			_properties.setProperty("local.host", host);
		}
		return host;
	}
	
	public int getLocalPort()
	{
		return getInt("local.sip.port");
	}

	public int getRemotePort()
	{
		return getInt("remote.sip.port");
	}

	public String getRemoteHost()
	{
		return _properties.getProperty("remote.host");
	}

	public String getHttpBaseUrl()
	{
		return "http://" + getRemoteHost() + ":" + _properties.getProperty("remote.http.port") + "/" + getApplicationName();
	}
	
	public Protocol getSipDefaultProtocol()
	{
		return Protocol.valueOf(_properties.getProperty("sip.protocol.default", "UDP").toUpperCase());
	}

	public String getFrom()
	{
		return "sip:alice@" + getDomain();
	}

	public Endpoint createEndpoint(String user)
	{
		return createEndpoint(user, null);
	}
	
	public String getApplicationName() 
	{
		return _properties.getProperty("application.name");
	}
	
	/**
	 * As Cipango-client filter initial requests on To URI, in forked tests, the user should be set same value.
	 * To be able to differentiate them an alias can be set.
	 */
	public Endpoint createEndpoint(String user, String alias)
	{
		Endpoint e = new Endpoint(user, getDomain(), _nextPort++);
		e.setAlias(alias);
		_endpoints.add(e);
		return e;
	}
	
	public Endpoint createEndpoint(String user, int port)
	{
		Endpoint e = new Endpoint(user, getDomain(), port);
		_endpoints.add(e);
		return e;
	}

	public String getTo()
	{
		return "sip:sipServlet@" + getDomain();
	}

	
	@Before
	public void setUp() throws Exception
	{
		Properties properties = new Properties();
		properties.putAll(_properties);
		_sipClient = new SipClient(getLocalHost(), getLocalPort());
		_sipClient.start();

		_ua = new TestAgent(_sipClient.getFactory().createAddress(getFrom()));
		_sipClient.addUserAgent(_ua);

		_ua.setOutboundProxy(getOutboundProxy());
		_ua.setTimeout(getTimeout());
		
		decorate(_ua);
	}
	
	@After
	public void tearDown() throws Exception
	{
		_ua = null;
		_sipClient.stop();
		for (Endpoint e: _endpoints)
			e.stop();
		Thread.sleep(10);
	}
	
	private Address getOutboundProxy() throws ServletParseException
	{
		String property = _properties.getProperty("remote.sip.outboundProxy");
		if (property == null)
		{
    		SipURI uri = _sipClient.getFactory().createSipURI(getApplicationName(), getRemoteHost());
    		uri.setPort(getRemotePort());
    		uri.setLrParam(true);
    		return _sipClient.getFactory().createAddress(uri);
		}
		return _sipClient.getFactory().createAddress(replaceProperties(property));
	}
	
	public String replaceProperties(String s)
	{
		int indexStart = 0;
		while (indexStart != -1)
		{
			indexStart = s.indexOf("${", indexStart);
			if (indexStart != -1)
			{
				int indexStop = s.indexOf('}', indexStart);
				if (indexStop != -1)
				{
					String key = s.substring(indexStart + 2, indexStop);
					if (_properties.containsKey(key))
						s = s.replace("${" + key + "}", _properties.getProperty(key));
				}
				indexStart++;
			}
		}
		return s;
	}
	
	public TestAgent decorate(TestAgent agent)
	{
		Map<String, String> extraHeaders = agent.getExtraHeaders();
		extraHeaders.put(MainServlet.SERVLET_HEADER, getClass().getName());
		extraHeaders.put(MainServlet.METHOD_HEADER, _name.getMethodName());		
		return agent;
	}

	public void assertValid(HttpURLConnection connection) throws Exception
	{
		connection.connect();
		int code = connection.getResponseCode();
		if (code != 200)
		{
			InputStream is = connection.getErrorStream();
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			int read = 0;
			byte[] b = new byte[1024];
			while ((read = is.read(b)) != -1)
				os.write(b, 0, read);
			Assert.fail("Fail on HTTP request: " + connection.getURL() + " with code " + code + " "
					+ connection.getResponseMessage() + "\n" + new String(os.toByteArray()));
		}
	}
	
	
	public void sendAndAssertMessage() throws IOException, ServletException
	{
		SipServletRequest request = _ua.createRequest(SipMethods.MESSAGE, getTo());
		SipServletResponse response = _ua.sendSynchronous(request);
		Assert.assertThat(response, isSuccess());
	}
	
	@Factory
	public static <T> Matcher<SipServletResponse> isSuccess()
	{
		return new IsSuccess();
	}
	
	public void startUacScenario() throws IOException, ServletException
	{
		SipServletRequest request = _ua.createRequest(SipMethods.REGISTER, getTo());
		request.addHeader(SipHeaders.CONTACT, _sipClient.getContact().toString());
		SipServletResponse response = _ua.sendSynchronous(request);
		Assert.assertThat(response, isSuccess());
	}
	
	/**
	 * Call the method checkForFailure on the AS.
	 * This method is useful when exception are thrown on the AS when no response 
	 * can be sent by the server (in doResponse(), on a committed request or on a
	 * ACK).
	 */
	public void checkForFailure()
	{
		try
		{
			// Ensure servlet has ended the treatment.
			Thread.sleep(50);

			SipServletRequest request = _ua.createRequest(SipMethods.MESSAGE, getTo());
			request.setHeader(MainServlet.METHOD_HEADER, "checkForFailure");
			SipServletResponse response = _ua.sendSynchronous(request);
			Assert.assertThat(response, isSuccess());
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}
	
	public class Endpoint
	{
		private SipClient _client;
		private String _user;
		private String _uri;
		private int _port;
		private String _alias;
		
		public Endpoint(String user, String domain, int port)
		{
			_user = user;
			_uri = "sip:" + user + "@" + domain;
			_port = port;
		}
		
		public String getUri()
		{
			return _uri;
		}
		
		public int getPort()
		{
			return _port;
		}
		
		public void stop() throws Exception
		{
			if (_client != null)
				_client.stop();
		}

		public Address getContact()
		{
			SipURI contact = (SipURI) getOrCreateClient().getContact().clone();
			contact.setUser(_user);
			return getUserAgent().getFactory().createAddress(contact);
		}

		public TestAgent getUserAgent()
		{
			return getUserAgent(getSipDefaultProtocol());
		}
		
		public TestAgent getUserAgent(SipClient.Protocol protocol)
		{
			SipClient client = getOrCreateClient(protocol);

			try
			{
				Address addr = client.getFactory().createAddress(_uri);
				TestAgent ua = (TestAgent) client.getUserAgent(addr.getURI()); 
				if (ua == null)
				{
					ua = decorate(new TestAgent(addr));
					ua.setTimeout(getTimeout());
					ua.setAlias(getAlias());
					client.addUserAgent(ua);
				}
				return ua;

			} catch (ServletParseException e) {
				throw new RuntimeException(e);
			}
		}

		protected SipClient getOrCreateClient()
		{
			return getOrCreateClient(getSipDefaultProtocol());
		}
		
		protected SipClient getOrCreateClient(SipClient.Protocol protocol)
		{
			if (_client == null)
			{
				try
	    		{
	    			_client = new SipClient();
	    			_client.addConnector(protocol, getLocalHost(), _port);
	    			_client.start();
	    		} catch (Exception e) {
	    			throw new RuntimeException(e);
	    		}
			}
			return _client;
		}

		public String getAlias()
		{
			if (_alias == null)
				return _user;
			return _alias;
		}

		public void setAlias(String alias)
		{
			_alias = alias;
		}
	}
}
