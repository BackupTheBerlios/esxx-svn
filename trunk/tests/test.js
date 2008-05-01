
function MyApp(e) {
  this.local = "an instance variable";
}

MyApp.prototype.handleError = function(ex) {
  esxx.debug.println("**** START ERROR HANDLER ****");
  esxx.debug.println(ex);
  esxx.debug.println("**** END ERROR HANDLER ****");
  return <html><body>{ex}</body></html>
};

MyApp.prototype.handleGet = function(req) {
  esxx.debug.println("**** START GET HANDLER ****")

  //    var ldap = new URI("ldap://ldap.blom.org/ou=People,dc=blom,dc=org??sub?(sn=Blom)");
  //   var ldap = new URI("ldap://ldap.blom.org/ou=Groups,dc=blom,dc=org??sub");

  //   ldap["java.naming.security.authentication"] = "simple";
  //   ldap["java.naming.security.principal"] = "uid=martin,ou=People,dc=blom,dc=org";
  //   ldap["java.naming.security.credentials"] = "********";
  //   esxx.debug.println(ldap.load());

  //     var db = new URI("jdbc:postgresql:esxx?user=esxx&password=secret");
    
  //     return db.query("select * from customers where country = {c}", {
  // 	c : "Sweden"
  // 	  });

  //     var mail = new URI("Testmail-3.eml");
  //     return mail.load("message/rfc822; x-format=esxx;x-process-html=false");
  //     var mailto = new URI("mailto:martin@blom.org?subject=XML%20Message");
  //     mailto.save(<xml>This is <empasis>XML</empasis>.</xml>, "text/xml");

  esxx.debug.println("**** END GET HANDLER ****");

  default xml namespace = "http://www.w3.org/1999/xhtml";
  return new Response("text/html", 
		      <html><body><p>Hello, world och Örjan!</p><div/></body></html>);
};

MyApp.prototype.xsltCallback = function() {
  return "Result from MyApp.xsltCallback: " + this.local;
};
