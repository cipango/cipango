package org.cipango.tests.matcher;

import javax.servlet.sip.SipServletResponse;

import org.hamcrest.Description;

@Deprecated
public class HasStatus extends org.cipango.client.test.matcher.HasStatus 
{
	public HasStatus(int status)
	{
		super(status);
	}

	@Override
	protected void describeMismatchSafely(SipServletResponse item, Description mismatchDescription)
	{
		SipMatchers.describeErrorFromResponse(item, mismatchDescription);
	}
}
