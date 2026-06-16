package com.mongodb.search;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SearchServlet extends HttpServlet {
  private MongoCollection<Document> collection;
  private String indexName;

  private Logger logger;

  private static DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);

    logger = Logger.getLogger(config.getServletName());

    String uri = System.getenv("MONGODB_URI");
    if (uri == null) {
      throw new ServletException("MONGODB_URI must be specified");
    }

    String databaseName = config.getInitParameter("database");
    String collectionName = config.getInitParameter("collection");
    indexName = config.getInitParameter("index");

    MongoClient mongoClient = MongoClients.create(uri);
    MongoDatabase database = mongoClient.getDatabase(databaseName);
    collection = database.getCollection(collectionName);

    logger.info("Servlet " + config.getServletName() + " initialized: " + databaseName + " / " + collectionName + " / " + indexName);
  }

  /**
   * @param request  an {@link HttpServletRequest} object that contains the request the client has made of the servlet
   * @param response an {@link HttpServletResponse} object that contains the response the servlet sends to the client
   *
   * <p>
   *    /path?q=&lt;query&gt;
   *         &search=&lt;fields to search&gt;
   *         [&skip=N]
   *         [&limit=X]
   *         [&project=&lt;fields to return&gt;]
   *         [&filter=genres:Adventure&filter=&lt;field_name&gt;:&lt;field_value&gt;]
   *         [&highlight=&lt;fields to highlight&gt;]
   *         [&debug=true]
   *         [&facet.string.&lt;label&gt;=&lt;field names&gt];
   *         [&facet.string.&lt;label&gt;.numBuckets=N]
   *         [&facet.number.&lt;label&gt;=&lt;field names&gt];
   *         [&facet.number.&lt;label&gt;.boundaries=&lt;number list&gt;]
   *         [&facet.number.&lt;label&gt;.default=&lt;other label&gt;]
   *         [&facet.date.&lt;label&gt;=&lt;field names&gt];
   *         [&facet.date.&lt;label&gt;.boundaries=&lt;date list&gt;]
   *         [&facet.date.&lt;label&gt;.default=&lt;other label&gt;]
   *
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String q = request.getParameter("q");
    String searchFieldsValue = request.getParameter("search");
    String limitValue = request.getParameter("limit");
    String skipValue = request.getParameter("skip");
    String projectFieldsValue = request.getParameter("project");
    String debugValue = request.getParameter("debug");
    String[] filters = request.getParameterMap().get("filter");
    String sortValue = request.getParameter("sort");
    String highlightFieldsValue = request.getParameter("highlight");

    // Validate params
    int limit = Math.min(25, limitValue == null ? 10 : Integer.parseInt(limitValue));
    int skip = Math.min(100, skipValue == null ? 0 : Integer.parseInt(skipValue));
    boolean debug = Boolean.parseBoolean(debugValue);

    if (q == null || q.isEmpty()) {
      q = "";
    }

    // With limit=0, switch pipeline to use $searchMeta rather than $search
    boolean searchMeta = false;
    if (limit <= 0) {
      searchMeta = true;
    }

    ArrayList filterOperators = new ArrayList();
    ArrayList mustNotOperators = new ArrayList();
    List<String> errors = new ArrayList();
    if (filters != null) {
      for (String filter : filters) {
        int c = filter.indexOf(':');

        if (c == -1) {
          errors.add("Invalid `filter`: " + filter);
        } else {
          if (filter.charAt(0) == '-') {
            mustNotOperators.add(new Document("equals",
                    new Document("path", filter.substring(1, c))
                        .append("value", filter.substring(c + 1)))
            );
          } else {
            filterOperators.add(new Document("equals",
                    new Document("path", filter.substring(0, c))
                        .append("value", filter.substring(c + 1)))
            );
          }
        }
      }
    }

    if (!errors.isEmpty()) {
      response.sendError(400, errors.toString());
      return;
    }

    List<String> projectFields = new ArrayList<>();
    if (projectFieldsValue != null) {
      projectFields.addAll(List.of(projectFieldsValue.split(",")));
    }

    boolean includeId = false;
    if (projectFields.contains("_id")) {
      includeId = true;
      projectFields.remove("_id");
    }

    boolean includeScore = false;
    if (projectFields.contains("_score")) {
      includeScore = true;
      projectFields.remove("_score");
    }

    // $search
    List highlightPath = new ArrayList();
    if (highlightFieldsValue != null) {
      Collections.addAll(highlightPath, highlightFieldsValue.split(","));
    }

    // e.g. sort=year asc,rating desc => { "year": 1, rating: -1 }
    Document sortOption = new Document();
    if (sortValue != null) {
      String[] sortSpecs = sortValue.split(",");
      for (String sortSpec : sortSpecs) {
        // sortSpec = "field asc|desc"
        String[] fieldAndDirection = sortSpec.split(" ");
        if (fieldAndDirection.length != 2) {
          response.sendError(400, "`sort` spec invalid: " + sortSpec);
          return;
        }

        String fieldName = fieldAndDirection[0];
        String direction = fieldAndDirection[1];
        if (!direction.equals("asc") && !direction.equals("desc")) {
          response.sendError(400, "`sort` spec invalid: " + sortSpec);
          return;
        }

        if (fieldName.equals("_score")) {
          sortOption.append("unused",
              new Document("$meta","searchScore").append("order", direction.equals("asc") ? 1 : -1));
        } else {
          sortOption.append(fieldName, direction.equals("asc") ? 1 : -1);
        }
      }
    } else {
      // Sort by descending score by default
      // {unused: {$meta: "searchScore", order: -1}}
      sortOption.append("unused",
          new Document("$meta","searchScore").append("order", -1));
    }

    Document compoundDoc = new Document();
    if (!mustNotOperators.isEmpty()) {
      compoundDoc.put("mustNot", mustNotOperators);
    }
    if (!filterOperators.isEmpty()) {
      compoundDoc.put("filter", filterOperators);
      }
    if (!q.isEmpty()) {
      Document pathDoc;
      if (searchFieldsValue != null) {
        String[] searchFields = searchFieldsValue.split(",");
        List searchPath = new ArrayList<>();
        Collections.addAll(searchPath, searchFields);
        pathDoc = new Document("path", searchPath);
      } else {
        pathDoc = new Document("path", new Document("wildcard", "*"));
      }

      compoundDoc.put("must", new Document("text", pathDoc.append("query", q)));
    }
    Document compound = compoundDoc.isEmpty() ? null : new Document("compound", compoundDoc);

    // ------
    // FACETS
    // ------
    Map<String, String[]> requestMap = request.getParameterMap();
    HashMap<String,String> stringFacetMap = new HashMap<>();
    HashMap<String,String> numberFacetMap = new HashMap<>();
    HashMap<String,String> dateFacetMap = new HashMap<>();
    for (String key : requestMap.keySet()) {
      // facet.string.str_years=year
      // facet.string.str_years.numBuckets
      if (key.startsWith("facet.string.")) {
        String suffix = key.substring("facet.string.".length());
        if (!suffix.contains(".")) {
          stringFacetMap.put(suffix, request.getParameter(key));
        }
      }

      // facet.number.num_years=year
      // facet.number.num_years.boundaries=0,1800
      // facet.number.num_years.default=other
      if (key.startsWith("facet.number.")) {
        String suffix = key.substring("facet.number.".length());
        if (!suffix.contains(".")) {
          numberFacetMap.put(suffix, request.getParameter(key));
        }
      }

      if (key.startsWith("facet.date.")) {
        String suffix = key.substring("facet.date.".length());
        if (!suffix.contains(".")) {
          dateFacetMap.put(suffix, request.getParameter(key));
        }
      }
    }

    Document facetsSpecs = new Document();
    for (String facetStringLabel : stringFacetMap.keySet()) {
      Document facetSpec = new Document("type", "string").append("path", stringFacetMap.get(facetStringLabel));
      String numBucketsValue = request.getParameter("facet.string." + facetStringLabel + ".numBuckets");
      if (numBucketsValue != null) {
        facetSpec.put("numBuckets", Integer.parseInt(numBucketsValue));
      }
      facetsSpecs.put(facetStringLabel, facetSpec);
    }

    for (String facetNumberLabel : numberFacetMap.keySet()) {
      Document facetSpec = new Document("type", "number").append("path", numberFacetMap.get(facetNumberLabel));

      String boundariesValue = request.getParameter("facet.number." + facetNumberLabel + ".boundaries");
      ArrayList boundaries = new ArrayList();
      for (String boundaryValue : boundariesValue.split(",")) {
        boundaries.add(Double.parseDouble(boundaryValue));
      }
      facetSpec.put("boundaries", boundaries);

      String defaultValue = request.getParameter("facet.number." + facetNumberLabel + ".default");
      if (defaultValue != null) {
        facetSpec.put("default", defaultValue);
      }

      facetsSpecs.put(facetNumberLabel, facetSpec);
    }

    for (String facetDateLabel : dateFacetMap.keySet()) {
      Document facetSpec = new Document("type", "date").append("path", dateFacetMap.get(facetDateLabel));

      String boundariesValue = request.getParameter("facet.date." + facetDateLabel + ".boundaries");
      ArrayList boundaries = new ArrayList();
      for (String boundaryValue : boundariesValue.split(",")) {
          try {
              boundaries.add(dateFormat.parse(boundaryValue));
          } catch (ParseException e) {
              throw new RuntimeException(e);
          }
      }
      facetSpec.put("boundaries", boundaries);

      String defaultValue = request.getParameter("facet.date." + facetDateLabel + ".default");
      if (defaultValue != null) {
        facetSpec.put("default", defaultValue);
      }

      facetsSpecs.put(facetDateLabel, facetSpec);
    }

    Document facetCollector = null;
    if (!facetsSpecs.isEmpty()) {
      Document facets = new Document("facets", facetsSpecs);
      if (compound != null) {
        facets.put("operator", compound);
      }
      facetCollector = new Document("facet", facets);
    }

    Document searchStageDoc= new Document("scoreDetails", debug)
                            .append("index", indexName)
                            .append("count", new Document("type", "total"))
                            .append("sort", sortOption);

    if (!highlightPath.isEmpty()) {
      searchStageDoc.put("highlight", new Document("path",highlightPath));
    }

      if (facetCollector == null) {
        searchStageDoc.putAll(compound);
    } else {
        searchStageDoc.putAll(facetCollector);
    }

    Document searchStage = new Document(searchMeta ? "$searchMeta" : "$search", searchStageDoc);

    // $project
    Document projections = new Document();
    if (projectFieldsValue != null) {
      // Don't add _id inclusion or exclusion if no `project` parameter specified
      for (String projectField : projectFields) {
        projections.put(projectField,1);
      }
      if (includeId) {
        projections.put("_id",1);
      } else {
        projections.put("_id",0);
      }
    }
    if (debug) {
      projections.put("_scoreDetails", new Document("$meta", "searchScoreDetails"));
    }
    if (includeScore) {
      projections.put("_score", new Document("$meta", "searchScore"));
    }

    if (highlightPath.size() > 0) {
      projections.put("_highlights", new Document("$meta", "searchHighlights"));
    }

    // Using $facet stage to provide both the documents and $$SEARCH_META data.
    // The $$SEARCH_META data contains the total matching document count, etc

    List facetStages = new ArrayList();
    if (projections.size() > 0) {
      facetStages.add(new Document("$project", projections));
    }
    Document facetStage = new Document("$facet",
      new Document("docs", facetStages)
      .append("meta",
        Arrays.asList(new Document("$limit", 1), new Document("$replaceWith", "$$SEARCH_META")))
    );

    // Pull "meta" data one item array up to main meta level
    Document setStage = new Document("$set",
      new Document("meta",
        new Document("$arrayElemAt", Arrays.asList(
          "$meta", 0
        )))
    );

    List pipeline = new ArrayList();
    pipeline.add(searchStage);
    if (!searchMeta) {
      pipeline.add(new Document("$skip", skip));
      pipeline.add(new Document("$limit", limit));
      pipeline.add(facetStage);
      pipeline.add(setStage);
    }

    logger.info("pipeline = " + toJSON(pipeline));
    AggregateIterable<Document> aggregationResults = collection.aggregate(pipeline);

    Document responseDoc = new Document();
    responseDoc.put("request", new Document() // TODO: Add facet parameters to this output
        .append("q", q)
        .append("skip", skip)
        .append("limit", limit)
        .append("search", searchFieldsValue)
        .append("project", projectFieldsValue)
        .append("filter", filters==null ? Collections.EMPTY_LIST : List.of(filters))
        .append("sort", sortValue)
        .append("highlight", highlightFieldsValue));

    if (debug) {
      Document debugDoc = new Document("explain",aggregationResults.explain().toBsonDocument());
      responseDoc.put("debug", debugDoc);
    }

    // When using $facet stage, only one "document" is returned,
    // containing the keys specified above: "docs" and "meta"
    Document results = aggregationResults.first();
    if (results != null) {
      Document responseSections = new Document();
      for (String s : results.keySet()) {
        responseSections.put(s, results.get(s));
      }

      if (searchMeta) {
        responseDoc.put("meta", responseSections);
      } else {
        responseDoc.putAll(responseSections);
      }
    }

    response.setContentType("text/json");
    PrintWriter writer = response.getWriter();
    writer.println(responseDoc.toJson());
    writer.close();

    logger.info(request.getServletPath() + "?" + request.getQueryString());
  }

  public static String toJSON(List array) {
    Document wrapperDoc = new Document("wrapper", array);

    // Convert wrapper to JSON and strip out the `{"wrapper":` and `}` parts
    String rawJson = wrapperDoc.toJson();
    String finalJson = rawJson.substring(11, rawJson.length() - 1);

    return finalJson;    
  }
//  public static String bsonArrayToJson(BsonArray bsonArray) {
//    // 1. Wrap the array inside a temporary BsonDocument
//    BsonDocument tempDoc = new BsonDocument("arrayWrapper", bsonArray);
//
//    // 2. Convert the document to a standard JSON string
//    String rawJson = tempDoc.toJson();
//
//    // 3. Extract the JSON array string out of the objectwrapper
//    // Input looks like: {"arrayWrapper": [ ... ]}
//    int startPos = rawJson.indexOf(":") + 2;
//    int endPos = rawJson.length() - 1;
//
//    return rawJson.substring(startPos, endPos).trim();
//  }

}
