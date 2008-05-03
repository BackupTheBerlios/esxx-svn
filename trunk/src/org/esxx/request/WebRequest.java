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

package org.esxx.request;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Properties;
import org.esxx.*;
import org.esxx.js.JSResponse;
import org.mozilla.javascript.RhinoException;

public class WebRequest
  extends Request 
  implements ESXX.ResponseHandler {
    
  public WebRequest(URL url, String[] command_line, Properties properties,
		    InputStream in, Writer error, OutputStream out)
    throws IOException {
    super(url, command_line, properties, in, error);
    outStream = out;
  }

  public URL getWD() {
    try {
      URI main = super.getURL().toURI();

      return new File(main).getParentFile().toURI().toURL();
    }
    catch (Exception ex) {
      // Should not happen. Fall back to super method if it does.
    }

    return super.getWD();
  }

  public Object handleResponse(ESXX esxx, JSResponse response)
    throws Exception {
    // Output HTTP headers
    final PrintWriter out = new PrintWriter(createWriter(outStream, "US-ASCII"));

    out.println("Status: " + response.getStatus());
    out.println("Content-Type: " + response.getContentType());

    response.enumerateHeaders(new JSResponse.HeaderEnumerator() {
	public void header(String name, String value) {
	  out.println(name + ": " + value);
	}
      });
	
    out.println();
    out.flush();

    Object result = response.getResult();

    // Output body
    HashMap<String,String> mime_params = new HashMap<String,String>();
    String mime_type = ESXX.parseMIMEType(response.getContentType(), mime_params);

    esxx.serializeToStream(result, null, null,
			   mime_type, mime_params,
			   outStream);

    getErrorWriter().flush();
    getDebugWriter().flush();
    outStream.flush();

    return 0;
  }

  public Object handleError(ESXX esxx, Throwable ex) {
    String title = "ESXX Server Error";
    int    code  = 500;
	
    if (ex instanceof ESXXException) {
      code = ((ESXXException) ex).getStatus();
    }

    StringWriter sw = new StringWriter();
    PrintWriter out = new PrintWriter(sw);

    out.println("<?xml version='1.0'?>");
    out.println("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
		"\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">");
    out.println("<html><head><title>" + title + "</title></head><body>");
    out.println("<h1>" + title + "</h1>");
    out.println("<h2>Unhandled exception: " + ex.getClass().getSimpleName() + "</h2>");
    if (ex instanceof ESXXException ||
	ex instanceof javax.xml.stream.XMLStreamException ||
	ex instanceof javax.xml.transform.TransformerException) {
      out.println("<p><tt>" + ex.getMessage() + "</tt></p>");
    }
    else if (ex instanceof RhinoException) {
      out.println("<pre>");
      out.println(ex.getClass().getSimpleName() + ": " + ex.getMessage());
      out.println(((RhinoException) ex).getScriptStackTrace(new ESXX.JSFilenameFilter()));
      out.println("</pre>");
    }
    else {
      out.println("<pre>");
      ex.printStackTrace(out);
      out.println("</pre>");
    }
    out.println("</body></html>");
    out.close();

    try {
      return handleResponse(esxx, new JSResponse(code,
						 "text/html",
						 sw.toString()));
    }
    catch (Exception ex2) {
      // Hmm
      return 20;
    }
  }

  protected static URL createURL(Properties headers)
    throws IOException {
    try {
      File file = new File(headers.getProperty("PATH_TRANSLATED"));

      while (file != null && !file.exists()) {
	file = file.getParentFile();
      }

      if (file.isDirectory()) {
	throw new IOException("Unable to find a file in path "
			      + headers.getProperty("PATH_TRANSLATED"));
      }

      return new URL("file", "", file.getAbsolutePath());
    }
    catch (MalformedURLException ex) {
      ex.printStackTrace();
      return null;
    }
  }

  protected static String encodeXMLContent(String str) {
    return str.replaceAll("&", "&amp;").replaceAll("<", "&lt;");
  }

  protected static String encodeXMLAttribute(String str) {
    return encodeXMLContent(str).replaceAll("'", "&apos;").replaceAll("\"", "&quot;");
  }

  private OutputStream outStream;
}
