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
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import org.bumblescript.jfast.*;
import org.esxx.*;
import org.esxx.util.IO;
import org.esxx.util.StringUtil;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;

public class FCGIRequest
  extends WebRequest {

  public FCGIRequest(JFastRequest jfast) {
    // NOTE: We MUST NOT send the error stream to jfast.error, because
    // that stream will, at least by lighttpd, be inserted in the
    // output stream, leading to broken HTTP responses!
    super(new ByteArrayInputStream(jfast.data), System.err);
    jFast = jfast;
  }

  public void initRequest(URI fs_root_uri)
    throws URISyntaxException {
    String   request_method    = jFast.properties.getProperty("REQUEST_METHOD");
    URI      request_uri;
    URI      path_translated;

    String scheme   = jFast.properties.getProperty("HTTPS", "off").equals("on") ? "https" : "http";
    String hostname = jFast.properties.getProperty("HTTP_HOST", "localhost");
    String path     = jFast.properties.getProperty("REQUEST_URI");
    String query    = jFast.properties.getProperty("QUERY_STRING", "");

    if (path != null) {
      int q = path.indexOf('?');

      if (q != -1) {
	path = path.substring(0, q); // Nuke, query string, if present
      }
    }
    else {
      // Fall back to PATH_INFO (it might work too)
      path = StringUtil.encodeURI(jFast.properties.getProperty("PATH_INFO", ""), false);
    }

    request_uri = new URI(scheme + "://" + StringUtil.encodeURI(hostname, true)
			  + path + (query.isEmpty() ? "" : "?" + query));

    if (fs_root_uri == null) {
      String pt_path = null;

      if (ESXX.getInstance().isHandlerMode(jFast.properties.getProperty("SERVER_SOFTWARE"))) {
        pt_path = jFast.properties.getProperty("PATH_TRANSLATED");
      }

      if (pt_path == null) {
        // If not handler mode, or PATH_TRANSLATED missing, use
        // SCRIPT_FILENAME + PATH_INFO instead
        pt_path = (jFast.properties.getProperty("SCRIPT_FILENAME")
  		 + jFast.properties.getProperty("PATH_INFO"));
      }

      path_translated = new URI("file", null, pt_path, null);
      fs_root_uri = URI.create("file:/");
    }
    else {
      path_translated = getPathTranslated(fs_root_uri, path, "/");
    }

    initRequest(request_method, request_uri, path_translated,
		jFast.properties, fs_root_uri, false);
  }

  @Override public Integer handleResponse(Response response)
    throws Exception {
    try {
      // Output HTTP headers
      final PrintWriter out = new PrintWriter(IO.createWriter(jFast.out, "US-ASCII"));

      out.println("Status: " + response.getStatus());
      out.println("Content-Type: " + response.getContentType(true));

      if (response.isBuffered()) {
	out.println("Content-Length: " + response.getContentLength());
      }

      response.enumerateHeaders(new Response.HeaderEnumerator() {
	  public void header(String name, String value) {
	    out.println(name + ": " + value);
	  }
	});

      out.println();
      out.flush();

      response.writeResult(jFast.out);

      getErrorWriter().flush();
      jFast.out.flush();

      return 0;
    }
    finally {
      jFast.end();
    }
  }

  public static void runServer(int fastcgi_port, final URI fs_root_uri)
    throws IOException {
    final ESXX esxx  = ESXX.getInstance();
    JFast jfast = new JFast(fastcgi_port);

    esxx.getLogger().logp(Level.INFO, null, null,
			  "Listening for FastCGI requests on port " + fastcgi_port);

    int timeout = (int) (Double.parseDouble(esxx.getSettings()
					    .getProperty("esxx.net.timeout", "60"))
			 * 1000);

    while (true) {
      try {
	while (true) {
	  esxx.getLogger().log(Level.FINE, "Waiting for FastCGI connection");
	  final Socket accepted = jfast.accept();
	  accepted.setSoTimeout(timeout);
	  esxx.getLogger().log(Level.FINE, "Accepted FastCGI connection from " + accepted);

	  // Read JFast message in new thread with default timeout
	  esxx.addContextAction(null, new ContextAction() {
		@Override public Object run(Context cx) {
		  Socket socket = accepted;

		  try {
		    esxx.getLogger().log(Level.FINE,
					 "Reading FastCGI request from " + accepted);

		    JFastRequest req = new JFastRequest(socket);
		    socket = null; // JFastRequest owns Socket

		    esxx.getLogger().log(Level.FINE,
					 "Initializing FastCGI request from " + accepted);

		    FCGIRequest fr = new FCGIRequest(req);

		    try {
		      // Fire and forget
		      fr.initRequest(fs_root_uri);
		      esxx.addRequest(fr, fr, 0);
		      req = null; // FCGIRequest owns JFastRequest

		      esxx.getLogger().log(Level.FINE,
					   "FastCGI request from " + accepted
					   + " successfully initialized");
		    }
		    catch (Exception ex) {
		      fr.reportInternalError(500,
					     "ESXX Server Error", "FastCGI Error",
					     ex.getMessage(), ex);
		      req = null; // FCGIRequest owns JFastRequest

		      esxx.getLogger().log(Level.FINE,
					   "FastCGI request from " + accepted
					   + " failed to initialize");
		    }
		    finally {
		      if (req != null) {
			try { req.end(); } catch (Exception ignored) {}
		      }
		    }
		  }
		  catch (JFastException ex) {
		    // Invalid FCGI packet data etc
		    esxx.getLogger().log(Level.WARNING,
					 "Failed to process FastCGI request from " + accepted, ex);
		  }
		  catch (java.net.SocketTimeoutException ex) {
		    esxx.getLogger().logp(Level.WARNING, null, null,
					  "FastCGI socket timeout from " + accepted);
		  }
		  catch (IOException ex) {
		    // I/O error on Socket
		    esxx.getLogger().log(Level.SEVERE,
					 "I/O error when processing FastCGI request from "
					 + accepted, ex);
		  }
		  finally {
		    if (socket != null) {
		      try { socket.close(); } catch (Exception ignored) {}
		    }
		  }

		  return null;
		}
	    }, "FastCGI " + accepted, timeout);
	}
      }
      catch (IOException ex) {
	esxx.getLogger().log(Level.SEVERE,
			     "Failed to accept JFast request", ex);
	// Re-bind
	jfast.close();
	jfast = new JFast(fastcgi_port);
      }
    }
  }

  private JFastRequest jFast;
}
