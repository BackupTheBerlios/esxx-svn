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

package org.esxx.dbref;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class QueryBuilder {
  public static void main(String[] args)
    throws Exception {

    for (String a: args) {
      System.out.println("Processing dbref " + a);
      QueryBuilder qb = new QueryBuilder(new URI("#" + a));
      List<String>        result = new ArrayList<String>();
      Map<String, String> params = new HashMap<String, String>();

      try {
	System.out.println(qb.getSelectQuery(result, params));
      }
      catch (Exception ex) {
	System.out.println(ex);
      }

      try {
	System.out.println(qb.getInsertQuery(java.util.Arrays.asList(new String[] {
		"c1", "c2", "c3" }), params));
      }
      catch (Exception ex) {
	System.out.println(ex);
      }

      try {
	System.out.println(qb.getUpdateQuery(java.util.Arrays.asList(new String[] {
		"c1", "c2", "c3" }), result, params));
      }
      catch (Exception ex) {
	System.out.println(ex);
      }

      try {
	System.out.println(qb.getDeleteQuery(result, params));
      }
      catch (Exception ex) {
	System.out.println(ex);
      }
    }
  }

  public QueryBuilder(URI uri)
    throws URISyntaxException {

    this.uri = uri;
    dbref = new DBReference(uri.getRawFragment());

    String table = dbref.getTable();

    if (table == null) {
      throw new URISyntaxException(uri.toString(), "Table name missing from URI fragment part");
    }
    else if (!strictTableName.matcher(table).matches()) {
      throw new URISyntaxException(uri.toString(), "'" + table + "' is not a valid table name");
    }

    for (String c : dbref.getColumns()) {
      ensureValidColumnName(c);
    }
  }

  public DBReference getParsedReference() {
    return dbref;
  }

  public boolean isRequiredParam(String param) {
    return dbref.getRequiredParams().containsKey(param);
  }

  public String findRequiredParam(Map<String, String> params) {
    for (String key : params.keySet()) {
      if (isRequiredParam(key)) {
	return key;
      }
    }

    return null;
  }

  public String getSelectQuery(List<String> args, Map<String, String> unhandled_params)
    throws URISyntaxException {

    args.clear();
    unhandled_params.clear();
    unhandled_params.putAll(dbref.getOptionalParams());
    unhandled_params.putAll(dbref.getRequiredParams());

    String order   = unhandled_params.remove("order");
    String reverse = unhandled_params.remove("reverse");
    String offset  = unhandled_params.remove("offset");
    String count   = unhandled_params.remove("count");

    if (dbref.getScope() == DBReference.Scope.SCALAR &&
	dbref.getColumns().size() != 1) {
      throw new URISyntaxException(uri.toString(),
				   "Scalar scope only works with one single column");
    }

    StringBuilder sb = new StringBuilder();

    sb.append("SELECT ");

    if (dbref.getScope() == DBReference.Scope.DISTINCT) {
      sb.append("DISTINCT ");
    }

    if (dbref.getColumns().isEmpty()) {
      sb.append("*");
    }
    else {
      sequence(dbref.getColumns(), true, false, sb);
    }

    sb.append(" FROM ").append(dbref.getTable());

    if (dbref.getFilter() != null) {
      sb.append(" WHERE ");
      where(dbref.getFilter(), sb, args);
    }

    orderBy(order, reverse, sb);
    offsetCount(offset, count, sb);

    return sb.toString();
  }

  public String getInsertQuery(Iterable<String> columns,
			       Map<String, String> unhandled_params)
    throws URISyntaxException {

    unhandled_params.clear();
    unhandled_params.putAll(dbref.getOptionalParams());
    unhandled_params.putAll(dbref.getRequiredParams());

    if (dbref.getColumns().isEmpty()) {
      for (String c : columns) {
	ensureValidColumnName(c);
      }
    }
    else {
      columns = dbref.getColumns();
    }

    if (dbref.getScope() != DBReference.Scope.ALL) {
      throw new URISyntaxException(uri.toString(), dbref.getScope().toString().toLowerCase() +
				   " is not a valid scope when inserting");
    }

    if (dbref.getFilter() != null) {
      throw new URISyntaxException(uri.toString(), "Filters may not be used when inserting");
    }

    StringBuilder sb = new StringBuilder();

    sb.append("INSERT INTO ").append(dbref.getTable()).append(" (");
    sequence(columns, true, false, sb);
    sb.append(") VALUES (");
    sequence(columns, false, true, sb);
    sb.append(")");

    return sb.toString();
  }

  public String getUpdateQuery(Iterable<String> columns,
			       List<String> args, Map<String, String> unhandled_params)
    throws URISyntaxException {

    args.clear();
    unhandled_params.clear();
    unhandled_params.putAll(dbref.getOptionalParams());
    unhandled_params.putAll(dbref.getRequiredParams());

    if (dbref.getColumns().isEmpty()) {
      for (String c : columns) {
	ensureValidColumnName(c);
      }
    }
    else {
      columns = dbref.getColumns();
    }

    if (dbref.getScope() == DBReference.Scope.DISTINCT) {
      throw new URISyntaxException(uri.toString(), dbref.getScope().toString().toLowerCase() +
				   " is not a valid scope when updating");
    }

    if (!columns.iterator().hasNext()) {
      throw new URISyntaxException(uri.toString(), "No columns to update");
    }

    StringBuilder sb = new StringBuilder();

    sb.append("UPDATE ").append(dbref.getTable()).append(" SET ");
    sequence(columns, true, true, sb);

    if (dbref.getFilter() != null) {
      sb.append(" WHERE ");
      where(dbref.getFilter(), sb, args);
    }

    return sb.toString();
  }


  public String getDeleteQuery(List<String> args, Map<String, String> unhandled_params)
    throws URISyntaxException {

    args.clear();
    unhandled_params.clear();
    unhandled_params.putAll(dbref.getOptionalParams());
    unhandled_params.putAll(dbref.getRequiredParams());

    switch (dbref.getScope()) {
    case SCALAR:
    case DISTINCT:
      throw new URISyntaxException(uri.toString(), dbref.getScope().toString().toLowerCase() +
				   " is not a valid scope when deleting");
    case ROW:
    case ALL:
      break;
    }

    if (!dbref.getColumns().isEmpty()) {
      throw new URISyntaxException(uri.toString(), "Columns may not be specified when deleting");
    }

    StringBuilder sb = new StringBuilder();

    sb.append("DELETE FROM ").append(dbref.getTable());

    if (dbref.getFilter() != null) {
      sb.append(" WHERE ");
      where(dbref.getFilter(), sb, args);
    }

    return sb.toString();
  }

  public interface ColumnGetter {
    public Object get(String key);
  }

  public String getMergeQuery(Iterable<String> columns, ColumnGetter cg,
			      List<String> args, Map<String, String> unhandled_params)
    throws URISyntaxException {

    unhandled_params.clear();
    unhandled_params.putAll(dbref.getOptionalParams());
    unhandled_params.putAll(dbref.getRequiredParams());

    String key = unhandled_params.remove("key");

    if (key == null) {
      throw new URISyntaxException(uri.toString(), "Missing 'key' parameter");
    }
    
    ensureValidColumnName(key);

    if (dbref.getColumns().isEmpty()) {
      for (String c : columns) {
	ensureValidColumnName(c);
      }
    }
    else {
      columns = dbref.getColumns();
    }

    if (dbref.getScope() != DBReference.Scope.ALL) {
      throw new URISyntaxException(uri.toString(), dbref.getScope().toString().toLowerCase() +
				   " is not a valid scope when merging");
    }

    if (dbref.getFilter() != null) {
      throw new URISyntaxException(uri.toString(), "Filters may not be used when merging");
    }

    StringBuilder sb = new StringBuilder();
    String       ssp = uri.getSchemeSpecificPart();

    if (ssp.startsWith("h2:")) {
      sb.append("MERGE INTO ").append(dbref.getTable()).append(" (");
      sequence(columns, true, false, sb);
      sb.append(") KEY (").append(key).append(") VALUES (");
      sequence(columns, false, true, sb);
      sb.append(")");
    }
    else if (ssp.startsWith("mysql:")) {
      sb.append("INSERT INTO ").append(dbref.getTable()).append(" (");
      sequence(columns, true, false, sb);
      sb.append(") VALUES (");
      sequence(columns, false, true, sb);
      sb.append(") ON DUPLICATE KEY UPDATE ");
      sequence(columns, true, true, sb);
    }
    else {
      args.add(cg.get(key).toString());

      sb.append("MERGE INTO ").append(dbref.getTable())
	.append(" USING ").append(dbref.getTable())
	.append(" ON ").append(key).append(" = {0}")
	.append(" WHEN MATCHED THEN UPDATE SET ");
      sequence(columns, true, true, sb);
      sb.append(" WHEN NOT MATCHED THEN INSERT (");
      sequence(columns, true, false, sb);
      sb.append(") VALUES (");
      sequence(columns, false, true, sb);
      sb.append(")");
    }

    return sb.toString();
  }


  private void sequence(Iterable<String> iter, boolean col, boolean ref, StringBuilder sb) {
    boolean first = true;

    for (String s : iter) {
      if (first) {
	first = false;
      }
      else {
	sb.append(", ");
      }

      if (col) {
	sb.append('`').append(s).append('`');
      }

      if (col && ref) {
	sb.append(" = ");
      }

      if (ref) {
        sb.append("{").append(s).append("}");
      }
    }
  }

  private void where(DBReference.Filter filter, StringBuilder sb, List<String> args)
    throws URISyntaxException {
    DBReference.Filter.Op op = filter.getOp();

    sb.append("(");

    switch (op) {
    case AND:
    case OR: {
      boolean first = true;

      for (DBReference.Filter f : filter.getChildren()) {
	if (first) {
	  first = false;
	}
	else {
	  sb.append(" ").append(op.toString()).append(" ");
	}

	where(f, sb, args);
      }

      break;
    }

    case NOT:
      if (filter.getChildren().size() != 1) {
	throw new IllegalStateException("Filter.Op." + op + " must have exactly one child");
      }

      sb.append("NOT ");
      where(filter.getChildren().get(0), sb, args);
      break;

    case LT:
    case LE:
    case EQ:
    case NE:
    case GT:
    case GE: {
      if (filter.getChildren().size() != 2 ||
	  filter.getChildren().get(0).getOp() != DBReference.Filter.Op.VAL ||
	  filter.getChildren().get(1).getOp() != DBReference.Filter.Op.VAL) {
	throw new IllegalStateException("Filter.Op." + op + " must have exactly two VAL children");
      }

      String column = filter.getChildren().get(0).getValue();
      ensureValidColumnName(column);
      sb.append(column);

      switch (op) {
      case LT: sb.append(" < ");  break;
      case LE: sb.append(" <= "); break;
      case EQ: sb.append(" = ");  break;
      case NE: sb.append(" != "); break;
      case GT: sb.append(" > ");  break;
      case GE: sb.append(" >= "); break;
      default:
	throw new IllegalStateException("This can't happen");
      }

      sb.append("{").append(args.size()).append("}");

      if (filter.getChildren().get(1).getOp() != DBReference.Filter.Op.VAL) {
	throw new IllegalStateException("Filter.Op." + op + "'s second child must be VAL");
      }

      args.add(filter.getChildren().get(1).getValue());
      break;
    }

    case VAL:
      throw new IllegalStateException("Filter.Op." + op + " should have been handled already");
    }

    sb.append(")");
  }

  private void orderBy(String order, String reverse, StringBuilder sb)
    throws URISyntaxException {
    if (order != null) {
      ensureValidColumnName(order);
      sb.append(" ORDER BY ").append(order);
    }

    if ("".equals(reverse) || Boolean.parseBoolean(reverse)) {
      sb.append(" DESC");
    }
  }

  private void offsetCount(String offset, String count, StringBuilder sb) {
    boolean use_offset_limit = useLimitOffset.matcher(uri.getSchemeSpecificPart()).matches();

    if (use_offset_limit) {
      if (count != null) {
	sb.append(" LIMIT ").append(Integer.parseInt(count));
      }

      if (offset != null) {
	sb.append(" OFFSET ").append(Integer.parseInt(offset));
      }
    }
    else {
      if (offset != null) {
	sb.append(" OFFSET ").append(Integer.parseInt(offset)).append(" ROWS");
      }

      if (count != null) {
	sb.append(" FETCH FIRST ").append(Integer.parseInt(count)).append(" ROWS ONLY");
      }
    }
  }

  private void ensureValidColumnName(String name)
    throws URISyntaxException {
    if (!strictColumnName.matcher(name).matches()) {
      throw new URISyntaxException(uri.toString(), "'" + name + "' is not a valid column name");
    }
  }

  private URI uri;
  private DBReference dbref;

  private static Pattern useLimitOffset   = Pattern.compile("(h2|mysql|postgresql):.*");
  private static Pattern strictColumnName = Pattern.compile("[_A-Za-z][_A-Za-z0-9]*");
  private static Pattern strictTableName  = Pattern.compile("[_A-Za-z][_A-Za-z0-9]*" +
							    "(\\.[_A-Za-z][_A-Za-z0-9]*)*");
}
