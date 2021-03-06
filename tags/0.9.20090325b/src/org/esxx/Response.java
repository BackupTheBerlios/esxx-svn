/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2008 Martin Blom <martin@blom.org>

     This program is free software: you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation, either version 3
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.esxx;

import org.esxx.util.IO;
import org.esxx.util.StringUtil;

import java.awt.image.RenderedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import org.json.*;
import org.mozilla.javascript.*;
import org.w3c.dom.Node;

public class Response  {

  public static interface HeaderEnumerator {
    public void header(String name, String value);
  }

  public Response(int status, String content_type, Object result, Map<String, String> headers) {
    setStatus(status);
    setContentType(content_type);
    setResult(result);
    httpHeaders = headers;
    contentLength = -1;
  }

  public int getStatus() {
    return httpStatus;
  }

  public void setStatus(int status) {
    httpStatus = status;
  }

  public String getContentType(boolean guess) {
    if (guess) {
      return guessContentType();
    }
    else {
      return contentType;
    }
  }

  public void setContentType(String content_type) {
    contentType = content_type;
  }

  public Object getResult() {
    return resultObject;
  }

  public void setResult(Object result) {
    resultObject = result;
  }

  public void setBuffered(boolean bool) {
    buffered = bool;
  }

  public boolean isBuffered() {
    return buffered;
  }

  public long getContentLength(ESXX esxx, Context cx) 
    throws IOException {
    if (!buffered) {
      throw new IllegalStateException("getContentLength() only works on buffered responses");
    }

    if (contentLength == -1) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      
      writeResult(esxx, cx, bos);
      setResult(bos.toByteArray());
      contentLength = bos.size();
    }

    return contentLength;
  }

  public void unwrapResult() {
    resultObject = unwrap(resultObject);
  }

  public Map<String, String> headers() {
    return httpHeaders;
  }


  public void enumerateHeaders(HeaderEnumerator he) {
    if (httpHeaders != null) {
      for (Map.Entry<String, String> e : httpHeaders.entrySet()) {
	he.header(e.getKey(), e.getValue());
      }
    }
  }

  public void writeResult(ESXX esxx, Context cx, OutputStream out)
    throws IOException {
    HashMap<String,String> mime_params = new HashMap<String,String>();
    String mime_type = ESXX.parseMIMEType(guessContentType(), mime_params);

    writeObject(resultObject, mime_type, mime_params, esxx, cx, out);
  }

  public static void writeObject(Object object,
				 String mime_type, HashMap<String,String> mime_params,
				 ESXX esxx, Context cx, OutputStream out)
    throws IOException {

    if (object == null) {
      return;
    }

    // Unwrap wrapped objects
    object = unwrap(object);

    // Convert complex types to primitive types
    if (object instanceof Node) {
      if ("message/rfc822".equals(mime_type)) {
	try {
	  String xml = esxx.serializeNode((Node) object);
	  org.esxx.xmtp.XMTPParser xmtpp = new org.esxx.xmtp.XMTPParser();
	  javax.mail.Message msg = xmtpp.convertMessage(new StringReader(xml));
	  object = new ByteArrayOutputStream();
	  msg.writeTo(new FilterOutputStream((OutputStream) object) {
	      public void write(int b)
		throws IOException {
		if (b == '\r') {
		  return;
		}
		else if (b == '\n') {
		  out.write('\r');
		  out.write('\n');
		}
		else {
		  out.write(b);
		}
	      }
	    });
	}
	catch (javax.xml.stream.XMLStreamException ex) {
	  throw new ESXXException("Failed to serialize Node as message/rfc822:" + ex.getMessage(), 
				  ex);
	}
	catch (javax.mail.MessagingException ex) {
	  throw new ESXXException("Failed to serialize Node as message/rfc822:" + ex.getMessage(), 
				  ex);
	}
      }
      else {
	object = esxx.serializeNode((Node) object);
      }
    }
    else if (object instanceof Scriptable) {
      if ("application/x-www-form-urlencoded".equals(mime_type)) {
	String cs = mime_params.get("charset");

	if (cs == null) {
	  cs = "UTF-8";
	}

	object = StringUtil.encodeFormVariables(cs, (Scriptable) object);
      }
      else {
	object = jsToJSON(object, cx).toString();
      }
    }
    else if (object instanceof byte[]) {
      object = new ByteArrayInputStream((byte[]) object);
    }

    // Serialize primitive types
    if (object instanceof ByteArrayOutputStream) {
      ByteArrayOutputStream bos = (ByteArrayOutputStream) object;

      bos.writeTo(out);
    }
    else if (object instanceof ByteBuffer) {
      // Write result as-is to output stream
      WritableByteChannel wbc = Channels.newChannel(out);
      ByteBuffer          bb  = (ByteBuffer) object;

      bb.rewind();

      while (bb.hasRemaining()) {
	wbc.write(bb);
      }

      wbc.close();
    }
    else if (object instanceof InputStream) {
      IO.copyStream((InputStream) object, out);
    }
    else if (object instanceof Reader) {
      // Write stream as-is, using the specified charset (if present)
      String cs = mime_params.get("charset");

      if (cs == null) {
	cs = "UTF-8";
      }

      Writer ow = new OutputStreamWriter(out, cs);

      IO.copyReader((Reader) object, ow);
    }
    else if (object instanceof String) {
      // Write string as-is, using the specified charset (if present)
      String cs = mime_params.get("charset");

      if (cs == null) {
	cs = "UTF-8";
      }

      Writer ow = new OutputStreamWriter(out, cs);
      ow.write((String) object);
      ow.flush();
    }
    else if (object instanceof RenderedImage) {
      Iterator<ImageWriter> i = ImageIO.getImageWritersByMIMEType(mime_type);

      if (!i.hasNext()) {
	throw new ESXXException("No ImageWriter available for " + mime_type);
      }

      ImageWriter writer = i.next();

      writer.setOutput(ImageIO.createImageOutputStream(out));
      writer.write((RenderedImage) object);
    }
    else {
      throw new UnsupportedOperationException("Unsupported object class type: "
					      + object.getClass());
    }
  }

  private static Object unwrap(Object object) {
    // Unwrap wrapped objects
    while (object instanceof Wrapper) {
      object = ((Wrapper) object).unwrap();
    }

    // Convert to "primitive" types
    if (object instanceof org.mozilla.javascript.xml.XMLObject) {
      object = ESXX.e4xToDOM((Scriptable) object);
    }
    
    return object;
  }

  public String guessContentType() {
    if (contentType == null) {
      // Set default content-type, if missing
      if (resultObject instanceof InputStream ||
	  resultObject instanceof ByteArrayOutputStream ||
	  resultObject instanceof ByteBuffer ||
	  resultObject instanceof byte[]) {
	return "application/octet-stream";
      }
      else if (resultObject instanceof Reader ||
	       resultObject instanceof String) {
	return "text/plain; charset=UTF-8";
      }
      else if (resultObject instanceof RenderedImage) {
	return "image/png";
      }
      else if (resultObject instanceof Node ||
	       resultObject instanceof org.mozilla.javascript.xml.XMLObject) {
	return "application/xml";
      }
      else if (resultObject instanceof Scriptable) {
	return "application/json";
      }
      else {
	return "application/octet-stream";
      }
    }

    return contentType;
  }

  private static Object jsToJSON(Object object, Context cx) {
    try {
      if (object instanceof NativeArray) {
	Object[] array = cx.getElements((Scriptable) object);

	for (int i = 0; i < array.length; ++i) {
	  array[i] = jsToJSON(array[i], cx);
	}

	object = new JSONArray(array).toString();
      }
      else if (object instanceof Wrapper) {
	object = jsToJSON(((Wrapper) object).unwrap(), cx);
      }
      else if (object instanceof Scriptable) {
	Scriptable jsobject = (Scriptable) object;

	object = new JSONObject();

	for (Object k : jsobject.getIds()) {
	  if (k instanceof String) {
	    String key = (String) k;
	    ((JSONObject) object).put(key, jsToJSON(jsobject.get(key, jsobject), cx));
	  }
	}
      }
      else {
	object = Context.jsToJava(object, Object.class);
      }

      return object;
    }
    catch (JSONException ex) {
      throw new ESXXException("Failed to convert JavaScript object to JSON: " + ex.getMessage(),
			      ex);
    }
  }


  private int httpStatus;
  private String contentType;
  private Object resultObject;
  private long contentLength;
  private boolean buffered;
  private Map<String, String> httpHeaders;
}
