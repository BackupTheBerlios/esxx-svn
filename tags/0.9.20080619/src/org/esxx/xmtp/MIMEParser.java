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

package org.esxx.xmtp;

import java.io.*;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;
import org.htmlcleaner.HtmlCleaner;
import org.w3c.dom.*;
import org.w3c.dom.ls.*;
import org.w3c.dom.bootstrap.*;

public class MIMEParser {
    public MIMEParser(boolean xmtp, boolean use_ns,
		      boolean process_html, boolean add_preamble)
      throws MessagingException, IOException,
      ClassNotFoundException, InstantiationException, IllegalAccessException {

      this.session = Session.getDefaultInstance(System.getProperties());

      this.xmtpMode    = xmtp;
      this.procHTML    = process_html;
      this.addPreamble = add_preamble;

      if (use_ns) {
	if (xmtpMode) {
	  documentNS     = XMTP_NAMESPACE;
	  documentPrefix = "";
	}
	else {
	  documentNS     = MIME_NAMESPACE;
	  documentPrefix = "m:";
	}
      }
      else {
	documentNS     = "";
	documentPrefix = "";
      }

      DOMImplementationRegistry reg  = DOMImplementationRegistry.newInstance();

      domImplementation   = reg.getDOMImplementation("XML 3.0");
      domImplementationLS = (DOMImplementationLS) domImplementation.getFeature("LS", "3.0");

      document = domImplementation.createDocument(documentNS, documentPrefix + "Message", null);
    }

    public String getNamespace() {
      return documentNS;
    }

    public Document getDocument() {
      return document;
    }

    public String getString() {
      LSSerializer ser = domImplementationLS.createLSSerializer();

      return ser.writeToString(document);
    }

    public void writeDocument(Writer wr) {
      LSSerializer ser = domImplementationLS.createLSSerializer();
      LSOutput     out = domImplementationLS.createLSOutput();

      out.setCharacterStream(wr);
      ser.write(document, out);
    }

    private static final int STRING_PART = 1;
    private static final int MULTI_PART  = 2;
    private static final int PLAIN_PART  = 3;
    private static final int XML_PART    = 4;
    private static final int HTML_PART   = 5;	// Only used if processing HTML
    private static final int RFC822_PART = 6;
    private static final int BASE64_PART = 7;


    public String convertMessage(InputStream is)
      throws IOException, MessagingException {
      MimeMessage msg = new MimeMessage(session, is);
      convertMessage(document.getDocumentElement(), msg);
      return msg.getMessageID();
    }

    private void convertMessage(Element element, MimeMessage msg)
      throws IOException, MessagingException {
      this.message = msg;
      convertPart(element, message, "mid:", message.getMessageID());
    }


    protected void convertPart(Element element, Part part, String about_prefix, String about)
      throws IOException, MessagingException {

      if (about == null && part instanceof MimePart) {
	about = ((MimePart) part).getContentID();
      }

      if (about != null && about.matches("^<.*>$")) {
	about = about.substring(1, about.length() - 1);
      }

      if (about != null) {
	if (xmtpMode) {
	  element.setAttributeNS(RDF_NAMESPACE, "web:about", about_prefix + about);
	}
	else {
	  element.setAttribute("id", about_prefix + about);
	}
      }

      int    part_type = 0;
      Object content   = part.getContent();

      ContentType content_type = new ContentType(part.getContentType());
      String base_type = content_type.getBaseType().toLowerCase();
      InputStream content_stream = null;

      if (content instanceof String) {
	if (base_type.endsWith("/xml") || base_type.endsWith("+xml")) {
	  part_type = XML_PART;
	}
	else if (procHTML && base_type.equals("text/html")) {
	  part_type = HTML_PART;
	}
	else {
	  part_type = STRING_PART;
	}
      }
      else if (content instanceof MimeMessage) {
	part_type = RFC822_PART;
      }
      else if (content instanceof Multipart) {
	part_type = MULTI_PART;
      }
      else if (content instanceof Part) {
	part_type = PLAIN_PART;
      }
      else {
	content_stream = (InputStream) content;

	if (base_type.endsWith("/xml") || base_type.endsWith("+xml")) {
	  part_type = XML_PART;
	}
	else if (procHTML && base_type.equals("text/html")) {
	  part_type = HTML_PART;
	}
	else {
	  part_type = BASE64_PART;
	}
      }

      convertHeaders(element, part, part.getAllHeaders(),
		     part_type == BASE64_PART ? "base64" : null);

      Element body = document.createElementNS(documentNS, documentPrefix + "Body");
      element.appendChild(body);

      switch (part_type) {
	case STRING_PART:
	  convertTextPart(body, (String) content);
	  break;

	case MULTI_PART: {
	  Multipart mp = (Multipart) content;

	  // Add preample as a plain text node first in the Body if
	  // addPreamble is true
	  if (addPreamble && mp instanceof MimeMultipart) {
	    MimeMultipart mmp = (MimeMultipart) mp;

	    if (mmp.getPreamble() != null) {
	      body.appendChild(document.createTextNode(mmp.getPreamble()));
	    }
	  }

	  for (int i = 0; i < mp.getCount(); ++i) {
	    Element msg = document.createElementNS(documentNS, documentPrefix + "Message");
	    body.appendChild(msg);
	    convertPart(msg, mp.getBodyPart(i), "cid:", null);
	  }
	  break;
	}

	case PLAIN_PART: {
	  Element msg = document.createElementNS(documentNS, documentPrefix + "Message");
	  body.appendChild(msg);
	  convertPart(msg, (Part) content, "cid:", null);
	  break;
	}

	case HTML_PART: {
	  // We can only arrive here if we're processing HTML parts

	  HtmlCleaner hc;

	  if (content_stream != null) {
	    hc = new HtmlCleaner(content_stream, content_type.getParameter("charset"));
	  }
	  else {
	    hc = new HtmlCleaner((String) content);
	  }

	  hc.setHyphenReplacementInComment("\u2012\u2012");
	  hc.setOmitDoctypeDeclaration(false);
	  hc.clean();

	  // HtmlCleaner.createDOM() doesn't expand HTML entity
	  // references, so we reparse the document from a string
	  // instead.
	  content = hc.getXmlAsString();

	  // Update content type
	  Element ct = (Element) element.getElementsByTagNameNS("*", "Content-Type").item(0);

	  if (ct != null) {
	    ct.setTextContent("text/x-html+xml");

	    NamedNodeMap nodes = ct.getAttributes();

	    while (nodes.getLength() > 0) {
	      nodes.removeNamedItem(nodes.item(0).getNodeName());
	    }
	  }

	  // !!! FALL THROUGH TO XML_PART !!!
	}

	case XML_PART: {
	  // !!! MAY CONTINUE FROM HTML_PART !!!

	  try {
	    LSInput  input  = domImplementationLS.createLSInput();
	    LSParser parser = domImplementationLS.createLSParser(
	      DOMImplementationLS.MODE_SYNCHRONOUS, null);

	    if (content_stream != null) {
	      input.setByteStream(content_stream);
	    }
	    else {
	      input.setStringData((String) content);
	    }

	    Document doc = parser.parse(input);
	    convertDOMPart(body, doc);
	  }
	  catch (Exception ex) {
	    // FIXME: What to do?
	    ex.printStackTrace();
	  }
	  break;
	}

	case RFC822_PART: {
	  Element msg = document.createElementNS(documentNS, documentPrefix + "Message");
	  body.appendChild(msg);

	  // This is a bit ugly, but whatever
	  MimeMessage saved_message = message;
	  convertMessage(msg, (MimeMessage) content);
	  message = saved_message;
	  break;
	}

	case BASE64_PART: {
	  convertBase64Part(body, content_stream);
	  break;
	}
      }
    }

    private void convertHeaders(Element element,
				Part part,
				Enumeration<?> headers,
				String forced_encoding)
      throws MessagingException {
      while (headers.hasMoreElements()) {
	Header hdr  = (Header) headers.nextElement();
	String name = hdr.getName();

	try {
	  if (name.equalsIgnoreCase("Content-Type")) {
	    // Parse Content-Type
	    ContentType ct = new ContentType(hdr.getValue());

	    if (!xmtpMode) {
	      // Delete the boundary parameter, which is not interesting
	      ct.getParameterList().remove("boundary");
	    }

	    convertResourceHeader(element, "Content-Type",
				  ct.getBaseType(),  ct.getParameterList());
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Content-Disposition")) {
	    // Parse Content-Disposition
	    ContentDisposition cd = new ContentDisposition(hdr.getValue());

	    convertResourceHeader(element, "Content-Disposition",
				  cd.getDisposition(),  cd.getParameterList());
	    continue;
	  }
	  else if (name.equalsIgnoreCase("From")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "From", message.getFrom());
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Sender")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "Sender", new Address[] { message.getSender() });
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Reply-To")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "Reply-To", message.getReplyTo());
	    continue;
	  }
	  else if (name.equalsIgnoreCase("To")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "To", message.getRecipients(Message.RecipientType.TO));
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Cc")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "Cc", message.getRecipients(Message.RecipientType.CC));
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Bcc")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "Bcc", message.getRecipients(Message.RecipientType.BCC));
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Newsgroups")) {
	    // Clean up often misspelled and misformatted header
	    convertAddressHeader(element, "Newsgroups",
				 message.getRecipients(MimeMessage.RecipientType.NEWSGROUPS));
	    continue;
	  }
	  else if (name.equalsIgnoreCase("Date")) {
	    convertPlainHeader(element, "Date", RFC2822_DATEFORMAT.format(message.getSentDate()));
	    continue;
	  }
	}
	catch (ParseException pex) {
	  // Treat header as plain header then
	}

	String value;

	try {
	  value = MimeUtility.decodeText(hdr.getValue());
	}
	catch (UnsupportedEncodingException ueex) {
	  // Never mind
	  value = hdr.getValue();
	}

	if (forced_encoding != null && name.equalsIgnoreCase("Content-Transfer-Encoding")) {
	  value = forced_encoding;
	}

	convertPlainHeader(element, name, value);
      }
    }


    protected void convertPlainHeader(Element element, String name, String value)
      throws MessagingException {
      Element e = document.createElementNS(documentNS, documentPrefix + makeXMLName(name));

      element.appendChild(e);

      if (value.length() != 0) {
	e.setTextContent(value);
      }
    }

    protected void convertResourceHeader(Element element, String name,
					 String value, ParameterList params)
      throws MessagingException {
      Element e = document.createElementNS(documentNS, documentPrefix + makeXMLName(name));

      element.appendChild(e);

      if (xmtpMode) {
	e.setAttributeNS(RDF_NAMESPACE, "web:parseType", "Resource");


	Element e2 = document.createElementNS(RDF_NAMESPACE, "web:value");
	e2.setTextContent(value.toLowerCase());

	e.appendChild(e2);

	for (Enumeration<?> names = params.getNames(); names.hasMoreElements(); ) {
	  String param = (String) names.nextElement();

	  e2 = document.createElementNS(XMTP_NAMESPACE, makeXMLName(param));
	  e2.setTextContent(params.get(param));

	  e.appendChild(e2);
	}
      }
      else {
	e.setTextContent(value.toLowerCase());

	for (Enumeration<?> names = params.getNames(); names.hasMoreElements(); ) {
	  String param = (String) names.nextElement();

	  e.setAttribute(makeXMLName(param), params.get(param));
	}
      }
    }

    protected void convertAddressHeader(Element element, String name, Address[] addresses)
      throws MessagingException {
      Element e = document.createElementNS(documentNS, documentPrefix + makeXMLName(name));
      e.setTextContent(InternetAddress.toString(addresses));

      element.appendChild(e);
    }

    protected void convertTextPart(Element element, String content)
      throws IOException, MessagingException {
      element.setTextContent(content);
    }

    protected void convertDOMPart(Element element, Document doc)
      throws IOException, MessagingException {
      // Add some extra info
      DocumentType doctype = doc.getDoctype();

      if (xmtpMode) {
	element.setAttributeNS(RDF_NAMESPACE, "web:parseType", "Literal");
      }
      else {
	element.setAttribute("version", doc.getXmlVersion());
	element.setAttribute("encoding", doc.getXmlEncoding());
	element.setAttribute("standalone", doc.getXmlStandalone() ? "yes" : "no");

	if (doctype != null) {
	  element.setAttribute("doctype-public", doctype.getPublicId());
	  element.setAttribute("doctype-system", doctype.getSystemId());
	}
      }


      Node adopted = document.adoptNode(doc.getDocumentElement());

      if (adopted == null) {
	adopted = document.importNode(doc.getDocumentElement(), true);
      }
      element.appendChild(adopted);
    }

    protected void convertBase64Part(Element element, InputStream is)
      throws IOException, MessagingException {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      Base64.OutputStream b64os = new Base64.OutputStream(bos, Base64.ENCODE);
      byte[] buffer = new byte[4096];
      int bytes_read;

      while ((bytes_read = is.read(buffer)) != -1) {
	b64os.write(buffer, 0, bytes_read);
      }

      b64os.close();
      element.setTextContent(bos.toString("US-ASCII"));
    }

    private String makeXMLName(String s) {
      char[] chars = s.toCharArray();

      if(!isNameStartChar(chars[0])) {
	chars[0] = '_';
      }

      for (int i = 1; i < chars.length; ++i) {
	if (!isNameChar(chars[i])) {
	  chars[i] = '_';
	}
      }

      return new String(chars);
    }

    private static boolean isNameStartChar(char ch) {
      return (Character.isLetter(ch) || ch == '_');
    }

    private static boolean isNameChar(char ch) {
      return (isNameStartChar(ch) || Character.isDigit(ch) || ch == '.' || ch == '-');
    }


    static final String RDF_NAMESPACE  = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String XMTP_NAMESPACE = "http://www.openhealth.org/xmtp#";
    static final String MIME_NAMESPACE = "urn:x-i-o-s:xmime";

    private static java.text.SimpleDateFormat RFC2822_DATEFORMAT =
      new java.text.SimpleDateFormat("EEE', 'dd' 'MMM' 'yyyy' 'HH:mm:ss' 'Z", Locale.US);

    private Session session;
    private MimeMessage message;
    private boolean xmtpMode;
    private boolean procHTML;
    private boolean addPreamble;

    protected String documentNS;
    protected String documentPrefix;

    private Document document;

    private DOMImplementation domImplementation;
    private DOMImplementationLS domImplementationLS;
}
