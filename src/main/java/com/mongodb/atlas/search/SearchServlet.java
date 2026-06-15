package com.mongodb.atlas.search;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.search.CompoundSearchOperator;
import com.mongodb.client.model.search.SearchCount;
import com.mongodb.client.model.search.SearchHighlight;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchOptions;
import com.mongodb.client.model.search.SearchPath;
import org.bson.*;
import org.bson.conversions.Bson;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class SearchServlet extends HttpServlet {
  private MongoCollection<Document> collection;
  private String indexName;

  private Logger logger;

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

    if (q == null || q.length() == 0) {
      response.sendError(400, "`q` is missing");
      return;
    }
    if (searchFieldsValue == null) {
      response.sendError(400, "`search` fields-list required");
      return;
    }

    if (limit <= 0) {
      response.sendError(400, "`limit` invalid: " + limitValue);
      return;
    }

    List<SearchOperator> filterOperators = new ArrayList<>();
    List<SearchOperator> mustNotOperators = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    if (filters != null) {
      for (String filter : filters) {
        int c = filter.indexOf(':');

        if (c == -1) {
          errors.add("Invalid `filter`: " + filter);
        } else {
          if (filter.charAt(0) == '-') {
            mustNotOperators.add(SearchOperator.of(
                new Document("equals",
                    new Document("path", filter.substring(1, c))
                        .append("value", filter.substring(c + 1)))
            ));
          } else {
            filterOperators.add(SearchOperator.of(
                new Document("equals",
                    new Document("path", filter.substring(0, c))
                        .append("value", filter.substring(c + 1)))
            ));
          }
        }
      }
    }

    if (errors.size() > 0) {
      response.sendError(400, errors.toString());
      return;
    }

    String[] searchFields = searchFieldsValue.split(",");

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
    List<SearchPath> searchPath = new ArrayList<>();
    for (String searchField : searchFields) {
      searchPath.add(SearchPath.fieldPath(searchField));
    }

    List<SearchPath> highlightPath = new ArrayList<>();
    if (highlightFieldsValue != null) {
      String[] highlightFields = highlightFieldsValue.split(",");
      for (String highlightField : highlightFields) {
        highlightPath.add(SearchPath.fieldPath(highlightField));
      }
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

    CompoundSearchOperator operator = SearchOperator.compound()
        .must(List.of(SearchOperator.text(searchPath, List.of(q))));
    if (filterOperators.size() > 0)
      operator = operator.filter(filterOperators);
    if (mustNotOperators.size() > 0)
      operator = operator.mustNot(mustNotOperators);

    SearchOptions options = SearchOptions.searchOptions()
        .option("scoreDetails", debug)
        .index(indexName)
        .count(SearchCount.total())
        .option("sort", sortOption);

    if (highlightPath.size() > 0) {
      options = options.highlight(SearchHighlight.paths(highlightPath));
    }

    Bson searchStage = Aggregates.search(operator, options);

    // $project
    List<Bson> projections = new ArrayList<>();
    if (projectFieldsValue != null) {
      // Don't add _id inclusion or exclusion if no `project` parameter specified
      projections.add(Projections.include(projectFields));
      if (includeId) {
        projections.add(Projections.include("_id"));
      } else {
        projections.add(Projections.excludeId());
      }
    }
    if (debug) {
      projections.add(Projections.meta("_scoreDetails", "searchScoreDetails"));
    }
    if (includeScore) {
      projections.add(Projections.metaSearchScore("_score"));
    }

    if (highlightPath.size() > 0) {
      projections.add(Projections.metaSearchHighlights("_highlights"));
    }

    // Using $facet stage to provide both the documents and $$SEARCH_META data.
    // The $$SEARCH_META data contains the total matching document count, etc

    List<Bson> facetStages = new ArrayList<>();
    facetStages.add(Aggregates.skip(skip));
    facetStages.add(Aggregates.limit(limit));
    if (projections.size() > 0) {
      facetStages.add(Aggregates.project(Projections.fields(projections)));
    }
    Bson facetStage = new Document("$facet",
      new Document("docs", facetStages)
      .append("meta",
        Arrays.asList(new Document("$replaceWith", "$$SEARCH_META"), Aggregates.limit(1)))
    );

    Bson setStage = new Document("$set",
      new Document("meta",
        new Document("$arrayElemAt", new BsonArray(Arrays.asList(
          new BsonString("$meta"), new BsonInt32(0)
        ))))
    );

    List pipeline = List.of(
      searchStage,
      facetStage,
      setStage
    );

//    {
//      "$set": {
//      "meta": {
//        "$arrayElemAt": ["$meta", 0]
//      }
//    }
//    }

    AggregateIterable<Document> aggregationResults = collection.aggregate(pipeline);

    Document responseDoc = new Document();
    responseDoc.put("request", new Document()
        .append("q", q)
        .append("skip", skip)
        .append("limit", limit)
        .append("search", searchFieldsValue)
        .append("project", projectFieldsValue)
        .append("filter", filters==null ? Collections.EMPTY_LIST : List.of(filters))
        .append("sort", sortValue)
        .append("highlight", highlightFieldsValue));

    if (debug) {
      responseDoc.put("debug",
        new BsonDocument("explain",aggregationResults.explain().toBsonDocument()));
    }

    // When using $facet stage, only one "document" is returned,
    // containing the keys specified above: "docs" and "meta"
    Document results = aggregationResults.first();
    if (results != null) {
      for (String s : results.keySet()) {
        responseDoc.put(s,results.get(s));
      }
    }

    response.setContentType("text/json");
    PrintWriter writer = response.getWriter();
    writer.println(responseDoc.toJson());
    writer.close();

    logger.info(request.getServletPath() + "?" + request.getQueryString());
  }
}
