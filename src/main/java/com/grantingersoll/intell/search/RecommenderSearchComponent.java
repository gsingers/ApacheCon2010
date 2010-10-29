package com.grantingersoll.intell.search;

import com.grantingersoll.intell.cf.WikiContentHandler;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldSelector;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.model.file.FileDataModel;
import org.apache.mahout.cf.taste.impl.recommender.GenericItemBasedRecommender;
import org.apache.mahout.cf.taste.impl.similarity.LogLikelihoodSimilarity;
import org.apache.mahout.cf.taste.recommender.ItemBasedRecommender;
import org.apache.mahout.cf.taste.recommender.RecommendedItem;
import org.apache.mahout.cf.taste.similarity.ItemSimilarity;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;


/**
 *
 *
 **/
public class RecommenderSearchComponent extends SearchComponent {

  public static final String COMPONENT_NAME = "recs";
  private transient static Logger log = LoggerFactory.getLogger(RecommenderSearchComponent.class);
  protected ItemBasedRecommender recommender;

  @Override
  public void init(NamedList args) {
    SolrParams params = SolrParams.toSolrParams(args);
    //String docsTitles = params.get("docsTitles");
    String recs = params.get("recommendations");

    //create the data model
    FileDataModel dataModel = null;
    try {
      /*InputSource is = new InputSource(new FileInputStream(docsTitles));
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setValidating(false);
      SAXParser sp = factory.newSAXParser();
      WikiContentHandler handler = new WikiContentHandler();
      sp.parse(is, handler);*/
      dataModel = new FileDataModel(new File(recs));
      log.info("Loaded: " + recs);
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Couldn't load: " + recs, e);
    }
    //Create an ItemSimilarity
    ItemSimilarity itemSimilarity = new LogLikelihoodSimilarity(dataModel);
    //Create an Item Based Recommender
    recommender = new GenericItemBasedRecommender(dataModel, itemSimilarity);
  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    if (!rb.req.getParams().getBool(COMPONENT_NAME, false)) {
      return;
    }
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {

    SolrParams params = rb.req.getParams();
    if (!params.getBool(COMPONENT_NAME, false)) {
      return;
    }
    String userStr = params.get("userId");
    long userId = Long.MIN_VALUE;
    if (userStr != null) {
      userId = Long.parseLong(userStr);
    }
    int numRequests = params.getInt("rec.rows", 5);
    try {
      NamedList recsNL = new NamedList();
      rb.rsp.add("recs", recsNL);
      List<RecommendedItem> recommendations;
      if (userId != Long.MIN_VALUE){
        recommendations = recommender.recommend(userId, numRequests);
        NamedList theRecs = new NamedList();
        toNamedList(theRecs, recommendations);
        recsNL.add(String.valueOf(userId), theRecs);
      } /*else {
        DocIterator docIterator = rb.getResults().docList.iterator();
        //we need the Solr unique ids, not the Lucene ids
        SchemaField keyField = rb.req.getSchema().getUniqueKeyField();
        String uniqFieldName = keyField.getName();
        Set<String> fields = Collections.singleton(uniqFieldName);
        SolrIndexSearcher searcher = rb.req.getSearcher();
        while (docIterator.hasNext()) {
          Integer id = docIterator.next();
          Document doc = searcher.doc(id, fields);
          //do this in the proper Solr way by
          String uniqId = keyField.getType().toExternal(doc.getFieldable(uniqFieldName));
          //assumes it's a long, since that is what we need
          Long uniqLong = Long.parseLong(uniqId);
          recommendations = recommender.recommend(uniqLong, numRequests);
          NamedList theRecs = new NamedList();
          toNamedList(theRecs, recommendations);
          recsNL.add(uniqId, theRecs);

        }
      }*/


    } catch (TasteException e) {
      log.error("Exception", e);
    }
  }

  public void toNamedList(NamedList theRecs, List<RecommendedItem> recommendations) {
    for (RecommendedItem recommendation : recommendations) {
      theRecs.add(String.valueOf(recommendation.getItemID()), recommendation.getValue());
    }
  }

  @Override
  public String getDescription() {
    return "SearchComponent for getting recommendations from Mahout";
  }

    @Override
  public String getVersion() {
    return "$Revision: $";
  }

  @Override
  public String getSourceId() {
    return "$Id:$";
  }

  @Override
  public String getSource() {
    return "$URL:$";
  }

}
