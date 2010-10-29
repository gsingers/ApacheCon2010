package com.grantingersoll.intell.cf;



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

import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.util.Map;
import java.util.HashMap;


/**
 *
 *
 **/
public class WikiContentHandler extends DefaultHandler {
  private boolean inDocId;
  private boolean inDocTitle;
  StringBuilder builder = new StringBuilder();
  private String itemId;
  private String docTitle;

  Map<String, String> map = new HashMap<String, String>();


  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
    if (qName.equals("str") && attributes.getValue("name") != null && attributes.getValue("name").equals("docid")) {
      inDocId = true;
    } else if (qName.equals("arr") && attributes.getValue("name") != null && attributes.getValue("name").equals("doctitle")) {
      inDocTitle = true;
    }

  }

  @Override
  public void characters(char[] chars, int offset, int len) throws SAXException {
    if (inDocId == true || inDocTitle == true) {
      builder.append(chars, offset, len);
    }
  }

  @Override
  public void endElement(String uri, String local, String qName) throws SAXException {
    if (inDocId == true) {
      itemId = builder.toString();
      inDocId = false;
    } else if (inDocTitle == true) {
      docTitle = builder.toString();
      inDocTitle = false;
    }
    if (qName.equals("doc")) {
      //System.out.println("Adding: " + itemId + " title: " + docTitle);
      map.put(itemId, docTitle);
    }
    builder.setLength(0);
  }
}

