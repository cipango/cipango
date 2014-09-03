package org.cipango.sip;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.BitSet;

import org.cipango.util.StringScanner;
import org.cipango.util.StringUtil;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SipParser 
{
	public enum State
	{
		START,
		METHOD_OR_VERSION,
		SPACE1,
		URI,
		STATUS,
		SPACE2,
		REQUEST_VERSION,
		REASON_PHRASE,
		HEADER,
		HEADER_NAME,	
		HEADER_IN_NAME,	
		HEADER_VALUE,
		HEADER_IN_VALUE,
		END,
		CONTENT;
	}
	
	private SipMessageHandler _handler;
	
	private SipHeader _header;
	private String _headerString;
	private String _valueString;

	private State _state = State.START;
	
	private SipMethod _method;
	private String _methodString;
	private String _uri;
	private SipVersion _version;
	private long _status;
	
	private byte _eol;
	private long _contentLength;
	private ByteBuffer _content;
	
    private final StringBuilder _string = new StringBuilder();
    private final Utf8StringBuilder _utf8 = new Utf8StringBuilder();
 
	private int _length;

	private static final Logger LOG = Log.getLogger(SipParser.class);
	private static final BitSet COMMA_QUOTE_BS = StringUtil.toBitSet(",\"");
	
	private final static Charset UTF8 = Charset.forName("UTF8");
	private final CharsetDecoder _charsetDecoder =  UTF8.newDecoder();
	private final CharBuffer _oneChar = CharBuffer.allocate(1);
	
	public SipParser(SipMessageHandler eventHandler)
	{
		_handler = eventHandler;
		_charsetDecoder.onMalformedInput(CodingErrorAction.REPLACE);
		_charsetDecoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}
	
	public State getState()
	{
		return _state;
	}
	
	public boolean isState(State state)
	{
		return _state == state;
	}
	
	private void quickStart(ByteBuffer buffer)
	{
		while (_state == State.START && buffer.hasRemaining())
		{
			_version = SipVersion.lookAheadGet(buffer);
			if (_version != null)
			{
				buffer.position(buffer.position()+_version.asString().length()+1);
                _state = State.SPACE1;
                return;
			}
			else 
			{
				_method = SipMethod.lookAheadGet(buffer);
                if (_method!=null)
                {
                    _methodString = _method.asString();
                    buffer.position(buffer.position()+_methodString.length()+1);
                    _state=State.SPACE1;
                    return;
                }
			}
			byte ch = buffer.get();
			if (_eol == SipGrammar.CR && ch == SipGrammar.LF)
			{
				_eol = SipGrammar.LF;
				continue;
			}
			_eol = 0;
			
			if (ch > SipGrammar.SPACE || ch < 0)
			{				
				_string.setLength(0);
				_string.append((char) ch);
				_state = State.METHOD_OR_VERSION;
				return;
			}
		}
	}
	
	private boolean parseLine(ByteBuffer buffer) throws ParseException
	{
		boolean returnFromParse = false;
		
		while (_state.ordinal() < State.HEADER.ordinal() && buffer.hasRemaining() && !returnFromParse)
		{
			byte ch = buffer.get();
			
			if (_eol == SipGrammar.CR && ch == SipGrammar.LF)
			{
				_eol = SipGrammar.LF;
				continue;
			}
			_eol = 0;
			
			switch (_state)
			{
				case METHOD_OR_VERSION:
					if (ch == SipGrammar.SPACE)
					{
						String methodOrVersion = takeString();
						SipVersion version = SipVersion.CACHE.get(methodOrVersion);
						if (version == null)
						{
							SipMethod method = SipMethod.CACHE.get(methodOrVersion);
							if (method != null)
								_methodString = method.toString();
							else
								_methodString = methodOrVersion;
						}
						else
							_version = version;
						_state = State.SPACE1;
					}
					else if (ch < SipGrammar.SPACE && ch >= 0)
					{
						badMessage(buffer, "No URI");
						return true;
					}
					else
					{
						_string.append((char) ch);
					}
					break;
					
				case SPACE1:
					if (ch > SipGrammar.SPACE || ch < 0)
					{
						if (Character.isDigit(ch))
						{
							_state = State.STATUS;
							_status = ch - '0';
							if (_version == null)
							{
								badMessage(buffer, "Unknown Version: " + _methodString);
								return true;
							}
						}
						else
						{
							_state = State.URI;
							_utf8.reset();
							_utf8.append(ch);
							if (_version != null)
							{
								badMessage(buffer, "Invalid status");
								return true;
							}
						}
					}
					else if (ch < SipGrammar.SPACE)
					{
						badMessage(buffer, "No URI");
						return true;
					}
					break;
				case URI:
					if (ch == SipGrammar.SPACE)
					{
						_uri = _utf8.toString();
						_utf8.reset();
						_state = State.SPACE2;
					}
					else 
						_utf8.append(ch);
					break;
				case STATUS:
					if (ch == SipGrammar.SPACE)
					{
						if (_status < 100 || _status >= 700)
							throw new ParseException("Invalid status code: " + _status, buffer.position());
						_state = State.SPACE2;
					}
					else if (Character.isDigit(ch))
						_status = _status * 10 +  ch - '0';
					else
						throw new ParseException("Invalid status code: " + _status + (char) ch, buffer.position());
					break;
				case SPACE2:
					if (ch > SipGrammar.SPACE || ch < 0)
					{
						_string.setLength(0);
						_string.append((char) ch);

						if (_status == 0)
						{
							_state = State.REQUEST_VERSION;
							
							if (buffer.position() > 0 && buffer.hasArray())
							{
								_version = SipVersion.lookAheadGet(buffer.array(), buffer.arrayOffset()+buffer.position()-1, 
										buffer.arrayOffset()+buffer.limit());
								if (_version != null)
								{
									_string.setLength(0);
									buffer.position(buffer.position()+_version.asString().length()-1);
									_eol = buffer.get();
									_state = State.HEADER;
									returnFromParse |= _handler.startRequest(_methodString, _uri, _version);
								}
							}
						}
						else
							_state = State.REASON_PHRASE;
					}
					else if (ch == SipGrammar.CR || ch == SipGrammar.LF)
					{
						_eol = ch;
						_state = State.HEADER;
						returnFromParse |= _handler.startResponse(_version, (int) _status, "");
					}
					break;
				case REQUEST_VERSION:
					if (ch == SipGrammar.CR || ch == SipGrammar.LF)
					{
						String version = takeString();
						_version = SipVersion.CACHE.get(version);
						
						if (_version == null)
						{
							badMessage(buffer, "Unknown Version");
							return true;
						}
						_eol = ch;
						_state = State.HEADER;
						returnFromParse |= _handler.startRequest(_methodString, _uri, _version);
					}
					else
					{
						_string.append((char) ch);
					}
					break;
				
				case REASON_PHRASE:
					if (ch == SipGrammar.CR || ch == SipGrammar.LF)
					{
						String reason = takeString();
						
						_eol = ch;
						_state = State.HEADER;
						returnFromParse |= _handler.startResponse(_version, (int) _status, reason);
					}
					else
					{
						_string.append((char) ch);
					}
					break;
			}
		}
		return returnFromParse;
	}
	
	private boolean parseHeaders(ByteBuffer buffer)
	{
		boolean returnFromParse = false;
		
		while (_state.ordinal() < State.END.ordinal() && buffer.hasRemaining() && !returnFromParse)
		{
			final char ch = readChar(buffer);
			if (_eol == SipGrammar.CR && ch == SipGrammar.LF)
			{
				_eol = SipGrammar.LF;
				continue;
			}
			_eol = 0;
			
			switch (_state)
			{
				case HEADER:
					switch (ch)
					{
						case SipGrammar.COLON:
						case SipGrammar.SPACE:
						case SipGrammar.TAB:
							_length = -1;
							_string.setLength(0);
							_state = State.HEADER_VALUE;
							break;
							
						default:
							
							if (_headerString != null || _valueString != null)
							{
								if (_header == SipHeader.CONTENT_LENGTH)
								{
									try
									{
										_contentLength = Long.parseLong(_valueString.trim());
									}
									catch (NumberFormatException e)
									{
										badMessage(buffer, "Invalid Content-Length");
										return true;
									}
								}
								if (_header != null && _header.isList())
								{
									StringScanner scanner = new StringScanner(_valueString);
	
									while (!scanner.eof())
									{
										scanner.skipToOneOf(COMMA_QUOTE_BS);
							
										if (scanner.eof())
											returnFromParse |= _handler.parsedHeader(_header, _headerString, scanner.sliceFromMark());
										else if (scanner.peekChar() == ',')
										{
											scanner.skipBackWhitespace();
											returnFromParse |= _handler.parsedHeader(_header, _headerString, scanner.sliceFromMark());
											scanner.skipWhitespace();
											scanner.skipChar(); // skip ','
											scanner.skipWhitespace();
											scanner.mark();
										}
										else
										{
											try
											{
												scanner.readQuoted();
												if (scanner.eof())
													returnFromParse |= _handler.parsedHeader(_header, _headerString, scanner.sliceFromMark());
											}
											catch (ParseException e)
											{
												badMessage(buffer, "Invalid quoted message");
												return true;
											}
										}
									}	
								}
								else
									returnFromParse |= _handler.parsedHeader(_header, _headerString, _valueString);
							}
							
							_headerString = _valueString = null;
							_header = null;
							
							if (ch == SipGrammar.CR || ch == SipGrammar.LF)
							{
								consumeCRLF((byte)ch, buffer);// it's ok, CR/LF are mapped properly in UTF8.
								if (_contentLength == 0)
								{
									returnFromParse |= _handler.headerComplete();
									_state = State.END;
									returnFromParse |= _handler.messageComplete(null);
								}
								else
								{
									returnFromParse |= _handler.headerComplete();
									_state = State.CONTENT;
								}
							}
							else
							{
							  //Optimizations really do not work for UTF8.
//								if (buffer.remaining() > 6 && buffer.hasArray())
//								{
//									_header = SipHeader.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());
//									
//									if (_header != null)
//									{
//										_headerString = _header.asString();
//										buffer.position(buffer.position()+_headerString.length());
//										_state = buffer.get(buffer.position()-1) == ':' ? State.HEADER_VALUE : State.HEADER_NAME;
//										break;
//									}
//								}
								_state = State.HEADER_NAME;
								_string.setLength(0);
								_string.append((char) ch);
								_length = 1;
							}
					}
					
					break;
					
				case HEADER_NAME:
					switch (ch)
					{
						case SipGrammar.CR:
						case SipGrammar.LF:
							consumeCRLF((byte)ch, buffer);// CR/LF are properly mapped in UTF8.
							_headerString = takeLengthString();
							_header = SipHeader.CACHE.get(_headerString);
							_state = State.HEADER;
							
							break;
							
						case SipGrammar.COLON:
							if (_headerString == null)
							{
								_headerString = takeLengthString();
								_header = SipHeader.CACHE.get(_headerString);
							}
							_state = State.HEADER_VALUE;
							break;
						case SipGrammar.SPACE:
						case SipGrammar.TAB:
							_string.append((char) ch);
							break;
							
						default:
							if (_header != null)
							{
								_string.setLength(0);
								_string.append(_header.asString());
								_string.append(' ');
								_length = _string.length();
								_header = null;
								_headerString = null;
							}
							_string.append((char) ch);
							_length = _string.length();
							_state = State.HEADER_IN_NAME;
					}
					
					break;
					
				case HEADER_IN_NAME:
					
					switch (ch)
					{
						case SipGrammar.CR:
						case SipGrammar.LF:
							consumeCRLF((byte)ch, buffer);// CR/LF are properly mapped in UTF8.
							_headerString = takeString();
							_length = -1;
							_header = SipHeader.CACHE.get(_headerString);
							_state = State.HEADER;
							break;
						
						case SipGrammar.COLON:
							if (_headerString == null)
							{
								_headerString = takeString();
								_header = SipHeader.CACHE.get(_headerString);
							}
							_length = -1;
							_state = State.HEADER_VALUE;
							break;
						case SipGrammar.SPACE:
						case SipGrammar.TAB:
							_state = State.HEADER_NAME;
							_string.append((char) ch);
							break;
						default:
							_string.append((char) ch);
							_length++;
					}
					break;
					
				case HEADER_VALUE:
					switch (ch)
					{
						case SipGrammar.CR:
						case SipGrammar.LF:
							consumeCRLF((byte)ch, buffer);// CR/LF are properly mapped in UTF8.
							
							if (_length > 0)
							{
								if (_valueString != null)
									_valueString += " " + takeLengthString();
								else
									_valueString = takeLengthString();
							}
							_state = State.HEADER;
							break;
						case SipGrammar.SPACE:
						case SipGrammar.TAB:
							break;
						default:
							_string.append((char) ch);
							_length = _string.length();
							_state = State.HEADER_IN_VALUE;
					}
					
					break;
				case HEADER_IN_VALUE:
					switch (ch)
					{
						case SipGrammar.CR:
						case SipGrammar.LF:
							consumeCRLF((byte)ch, buffer);// CR/LF are properly mapped in UTF8.
							
							if (_length > 0)
							{
								if (_valueString != null)
									_valueString += " " + takeString();
								else
									_valueString = takeString();
							}
							_length = -1;
							_state = State.HEADER;
							break;
						case SipGrammar.SPACE:
						case SipGrammar.TAB:
							_string.append((char) ch);
							_state = State.HEADER_VALUE;
							break;
						default:
							_string.append((char) ch);
							_length++;
					}
					break;
			}
		}
		return returnFromParse;
	}
	
	public boolean parseNext(ByteBuffer buffer)
	{
		try
		{
			switch (_state)
			{
				case START:
					_version = null;
					_method = null;
					_methodString = null;
					_uri = null;
					_header = null;
					_contentLength = -1;
					// UTF8 is not the best place for optimizations, but method and version are ASCII, so it's ok.
					quickStart(buffer);
					break;
					
				case END:
					return false;
			}
			if (_state.ordinal() < State.HEADER.ordinal())
				if (parseLine(buffer))
					return true;
			
			if (_state.ordinal() < State.END.ordinal())
				if (parseHeaders(buffer))
					return true;

			if (_state == State.CONTENT)
			{
				// Eat the last LF symbol before content if necessary.
				if (_eol == SipGrammar.CR && buffer.hasRemaining() && buffer.get(buffer.position()) == SipGrammar.LF)
				{
					buffer.get();
					_eol=0;
				}
	            
				ByteBuffer content = getContent(buffer);
				if (!content.isReadOnly() && buffer.hasRemaining())
				{
					int remaining = (int) Math.min(buffer.remaining(), _contentLength - content.position());
					buffer.get(content.array(), content.position(), remaining);
					content.position(content.position() + remaining);

					if (content.position() == content.limit())
					{
						content.flip();
						content = content.asReadOnlyBuffer();
					}
				}

				if (content.isReadOnly())
				{
					_state = State.END;
					releaseContent();
					if (_handler.messageComplete(content))
						return true;
				}
			}
			return false;
		}
		catch (Exception e)
		{
			LOG.warn(e);
			_handler.badMessage(400, e.toString());
			return true;
		}
	}
	
	private void badMessage(ByteBuffer buffer, String reason)
	{
		BufferUtil.clear(buffer);
		_state = State.END;
		_handler.badMessage(400, reason);
	}
	
	private ByteBuffer getContent(ByteBuffer buffer)
	{
		if (_content == null)
		{
            if (_contentLength == -1)
			{
            	 _content = buffer.asReadOnlyBuffer();
			}
            else if (buffer.remaining() >= _contentLength)
			{
				_content = buffer.asReadOnlyBuffer();
				if (_content.remaining() > _contentLength)
					_content.limit(_content.position() + (int)_contentLength);
				buffer.position(_content.position() + (int)_contentLength);
			}
			else
				_content = ByteBuffer.allocate((int)_contentLength);
		}
		return _content;
	}
	
	private void releaseContent()
	{
		_content = null;
	}

	private String takeString()
	{
		String s = _string.toString();
		_string.setLength(0);
		
		return s;
	}
	
	private String takeLengthString()
	{
		_string.setLength(_length);
		String s = _string.toString();
		_string.setLength(0);
		_length = -1;
		
		return s;
	}
	
	private void consumeCRLF(byte ch, ByteBuffer buffer)
	{
		_eol = ch;
		if (_eol == SipGrammar.CR && buffer.hasRemaining() && buffer.get(buffer.position()) == SipGrammar.LF)
		{
			buffer.get();
			_eol = 0;
		}
	}
	
	private char readChar(ByteBuffer buffer)
	{
    _oneChar.clear();
    final int savedPos = buffer.position();
    CoderResult result = _charsetDecoder.decode(buffer, _oneChar, true); // yes, in case if message is broken in the middle of UTF8 char we may get a problem.
    if (_oneChar.position() != 0)
    {
      // We may be consuming data
      return _oneChar.get(0);
    }
    if (buffer.position() == savedPos)
    {
      // Prevent infinite loop. Just take one character out if none was taken
      buffer.get();
    }
    return '?';
	}
	
	public void reset()
	{
		_state = State.START;
		_method = null;
		_methodString = null;
		_uri = null;
		_status = 0;
		_charsetDecoder.reset();
	}
	
	public String toString()
	{
		return String.format("%s{s=%s}",
                getClass().getSimpleName(),
                _state);
	}
	
	public interface SipMessageHandler
	{
		boolean startRequest(String method, String uri, SipVersion version) throws ParseException;
		boolean startResponse(SipVersion version, int status, String reason) throws ParseException;
		boolean parsedHeader(SipHeader header, String name, String value);
		boolean headerComplete();
		boolean messageComplete(ByteBuffer content);
		
		void badMessage(int status, String reason);
	}
}
