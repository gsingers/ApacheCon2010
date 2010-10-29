package com.grantingersoll.intell.index;
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.mahout.classifier.ClassifierResult;
import org.apache.mahout.classifier.bayes.exceptions.InvalidDatastoreException;
import org.apache.mahout.classifier.bayes.model.ClassifierContext;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;

/** A Solr <code>UpdateRequestProcessor</code> that uses the Mahout Bayes
 *  Classifier to add a category label to documents at index time.
 *  <p/>
 *  @see com.grantingersoll.intell.index.BayesUpdateRequestProcessorFactory
 *
 * * Used with permission from "Taming Text": http://lucene.li/1d
 *
 *
 */
public class BayesUpdateRequestProcessor extends UpdateRequestProcessor {

  public static final String NULL = "nullDefault";
  
  ClassifierContext ctx;
  String inputField;
  String outputField;
  String defaultCategory;
  Analyzer analyzer;
  
  public BayesUpdateRequestProcessor(ClassifierContext ctx, Analyzer analyzer,
      String inputField, String outputField, String defaultCategory, UpdateRequestProcessor next) {
    super(next);
    this.ctx = ctx;
    this.analyzer = analyzer;
    this.inputField = inputField;
    this.outputField = outputField;
    this.defaultCategory = defaultCategory;
    
    if (this.defaultCategory == null) {
      this.defaultCategory = NULL;
    }
  }

  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    SolrInputDocument doc = cmd.getSolrInputDocument();
    ClassifierResult result = classifyDocument(doc);
    
    if (result != null && result.getLabel() != NULL) {
      doc.addField(outputField, result.getLabel());
    }
    
    super.processAdd(cmd);
  }

  public ClassifierResult classifyDocument(SolrInputDocument doc) throws IOException {
    SolrInputField field = doc.getField(inputField);
    if (field == null) return null;

    if (!(field.getValue() instanceof String)) return null;
    
    String[] tokens = tokenizeField((String) field.getValue());
    
    try {
      return ctx.classifyDocument(tokens, defaultCategory);
    }
    catch (InvalidDatastoreException e) {
      throw new IOException("Invalid Classifier Datastore", e);
    }
  }
  
  public String[] tokenizeField(String input) throws IOException {
    ArrayList<String> tokenList = new ArrayList<String>(256);
    TokenStream ts = analyzer.tokenStream(inputField, new StringReader(input));
    while (ts.incrementToken()) {
      tokenList.add(ts.getAttribute(CharTermAttribute.class).toString());
    }
    return tokenList.toArray(new String[tokenList.size()]);
  }
}
