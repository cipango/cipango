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

package org.cipango.server.transaction;

import java.io.IOException;

import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;


/**
 * INVITE and non-INVITE client transaction. 
 * Supports RFC 6026.
 */
public interface ClientTransaction extends Transaction
{

	public ClientTransactionListener getListener();
		
	public void cancel(SipRequest cancel);
	
	public boolean isCanceled();
		
	public void start() throws IOException;
	
	public void handleResponse(SipResponse response);
		
	public void terminate();
	
	public SipResponse create408();
	
	/**
	 * Returns true if another thread is in ClientTransaction.handleResponse()
	 */
	public boolean isProcessingResponse();

}


