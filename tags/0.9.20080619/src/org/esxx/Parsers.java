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

import org.esxx.xmtp.MIMEParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileCacheImageInputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Iterator;
import java.util.HashMap;
import org.htmlcleaner.HtmlCleaner;
import org.json.*;
import org.mozilla.javascript.*;
import org.w3c.dom.Document;

class Parsers {
    public Parsers(final ESXX esxx) {

      parserMap.put("application/octet-stream", new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException, org.xml.sax.SAXException {
	      ByteArrayOutputStream bos = new ByteArrayOutputStream();

	      ESXX.copyStream(is, bos);

	      return java.nio.ByteBuffer.wrap(bos.toByteArray());
	    }
	});

      parserMap.put("application/json", new Parser() {
	  public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
			      Collection<URL> external_urls,
			      PrintWriter err,
			      Context cx, Scriptable scope)
	    throws IOException {
	    String cs = mime_params.get("charset");

	    if (cs == null) {
	      cs = "UTF-8";
	    }

	    ByteArrayOutputStream bos = new ByteArrayOutputStream();
	    ESXX.copyStream(is, bos);

	    try {
	      JSONTokener tok = new JSONTokener(bos.toString(cs));

	      char first = tok.nextClean();
	      tok.back();

	      if (first == '{') {
		return jsonToJS(new JSONObject(tok), cx, scope);
	      }
	      else if (first == '[') {
		return jsonToJS(new JSONArray(tok), cx, scope);
	      }
	      else {
		throw new IOException("Not a JSON Array or Object");
	      }
	    }
	    catch (JSONException ex) {
	      throw new IOException("Failed to parse JSON data: " + ex.getMessage(), ex);
	    }
	  }

	  private Object jsonToJS(Object json, Context cx, Scriptable scope)
	    throws IOException, JSONException {
	    Scriptable res;

	    if (json == JSONObject.NULL) {
	      return null;
	    }
	    else if (json instanceof String ||
		     json instanceof Number ||
		     json instanceof Boolean) {
	      return json;
	    }
	    else if (json instanceof JSONObject) {
	      JSONObject jo = (JSONObject) json;
	      res  = cx.newObject(scope);

	      for (Iterator<?> i = jo.keys(); i.hasNext();) {
		String  key = (String) i.next();
		Object  val = jsonToJS(jo.get(key), cx, scope);
		res.put(key, res, val);
	      }
	    }
	    else if (json instanceof JSONArray) {
	      JSONArray ja = (JSONArray) json;
	      res = cx.newArray(scope, ja.length());

	      for (int i = 0; i < ja.length(); ++i) {
		Object val = jsonToJS(ja.get(i), cx, scope);
		res.put(i, res, val);
	      }
	    }
	    else {
	      res = Context.toObject(json, scope);
	    }

	    return res;
	  }
	});

//       parserMap.put("application/xslt+xml", new Parser() {
// 	    public Object parse(String mime_type, HashMap<String,String> mime_params,
// 				InputStream is, URL is_url,
// 				Collection<URL> external_urls,
// 				PrintWriter err,
// 				Context cx, Scriptable scope)
// 	      throws IOException, org.xml.sax.SAXException {
// //	      Transformer transformer = esxx.getCachedStylesheet(is_url);
// 	    }
// 	});

      parserMap.put("message/rfc822", new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException, org.xml.sax.SAXException {
	      boolean xmtp;
	      boolean ns;
	      boolean html;

	      String fmt = mime_params.get("x-format");
	      String prc = mime_params.get("x-process-html");

	      if (fmt == null || fmt.equals("esxx")) {
		xmtp = false;
		ns   = false;
		html = true;
	      }
	      else if (fmt.equals("xmtp")) {
		xmtp = true;
		ns   = true;
		html = false;
	      }
	      else if (fmt.equals("xios")) {
		xmtp = false;
		ns   = true;
		html = true;
	      }
	      else {
		throw new IOException("No support for param 'x-format=" + fmt + "'");
	      }

	      if (prc == null) {
		// Leave html as-is
	      }
	      else if (prc.equals("true")) {
		html = true;
	      }
	      else if (prc.equals("false")) {
		html = false;
	      }
	      else {
		throw new IOException("Invalid value in param 'x-process-html=" + prc + "'");
	      }

	      try {
		MIMEParser p = new MIMEParser(xmtp, ns, html, true);
		p.convertMessage(is);
		Document result = p.getDocument();
		return ESXX.domToE4X(result, cx, scope);
	      }
	      catch (Exception ex) {
		throw new IOException("Unable to parse email message", ex);
	      }
	    }
	});

      Parser xml_parser =  new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException, org.xml.sax.SAXException {
	      Document result = esxx.parseXML(is, is_url, external_urls, err);
	      return ESXX.domToE4X(result, cx, scope);
	    }
	};

      parserMap.put("text/xml", xml_parser);
      parserMap.put("application/xml", xml_parser);

      parserMap.put("text/html", new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException, javax.xml.parsers.ParserConfigurationException  {
	      String      cs = mime_params.get("charset");
	      HtmlCleaner hc;

	      if (cs != null) {
		hc = new HtmlCleaner(is, cs);
	      }
	      else {
		hc = new HtmlCleaner(is);
	      }

	      hc.setHyphenReplacementInComment("\u2012\u2012");
	      hc.setUseCdataForScriptAndStyle(false);
	      hc.clean();

	      return ESXX.domToE4X(hc.createDOM(), cx, scope);
	    }
	});

      parserMap.put("text/plain", new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException {
	      String cs = mime_params.get("charset");

	      if (cs == null) {
		cs = "UTF-8";
	      }

	      ByteArrayOutputStream bos = new ByteArrayOutputStream();
	      ESXX.copyStream(is, bos);
	      return bos.toString(cs);
	    }
	});

      Parser image_parser = new Parser() {
	    public Object parse(String mime_type, HashMap<String,String> mime_params,
				InputStream is, URL is_url,
				Collection<URL> external_urls,
				PrintWriter err,
				Context cx, Scriptable scope)
	      throws IOException {
	      if (mime_type.equals("image/*")) {
		return ImageIO.read(is);
	      }
	      else {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mime_type);

		if (readers.hasNext()) {
		  ImageReader reader = readers.next();
		  String      index  = mime_params.get("x-index");

		  reader.setInput(new FileCacheImageInputStream(is, null));
		  return reader.read(index != null ? Integer.parseInt(index) : 0);
		}
		else {
		  return null;
		}
	      }
	    }
	};

      parserMap.put("image/bmp", image_parser);
      parserMap.put("image/gif", image_parser);
      parserMap.put("image/jpeg", image_parser);
      parserMap.put("image/wbmp", image_parser);
      parserMap.put("image/*", image_parser);
    }

    public Object parse(String mime_type, HashMap<String,String> mime_params,
			InputStream is, final URL is_url,
			Collection<URL> external_urls,
			PrintWriter err,
			Context cx, Scriptable scope)
      throws Exception {
      // Read-only accesses; no syncronization required
      Parser parser = parserMap.get(mime_type);

      if (parser == null) {
	if (mime_type.endsWith("+xml")) {
	  parser = parserMap.get("application/xml");
	}
	else {
	  parser = parserMap.get("application/octet-stream");
	}
      }

      return parser.parse(mime_type, mime_params, is, is_url,
			  external_urls, err, cx, scope);
    }

    private interface Parser {
	public Object parse(String mime_type, HashMap<String,String> mime_params,
			    InputStream is, URL is_url,
			    Collection<URL> external_urls,
			    PrintWriter err,
			    Context cx, Scriptable scope)
	  throws Exception;

    }

    private HashMap<String, Parser> parserMap = new HashMap<String, Parser>();
}