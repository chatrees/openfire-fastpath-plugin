/*
 * Copyright (C) 1999-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.xmpp.workgroup.spi.routers;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.jivesoftware.xmpp.workgroup.Workgroup;
import org.jivesoftware.xmpp.workgroup.request.Request;
import org.jivesoftware.xmpp.workgroup.request.UserRequest;
import org.jivesoftware.xmpp.workgroup.routing.RequestRouter;
import org.jivesoftware.xmpp.workgroup.utils.ModelUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The WordMatcheRouter using Lucense index to search individual metadata as specified
 * in routing rules.
 */
public class WordMatchRouter extends RequestRouter {

    private static final Logger Log = LoggerFactory.getLogger(WordMatchRouter.class);
    
    private boolean stemmingEnabled;
    private Analyzer analyzer;

    /**
     * Constructs a new word match router.
     */
    public WordMatchRouter() {
        analyzer = new StandardAnalyzer();
    }

    /**
     * Returns true if stemming will be applied to keywords. Stemming is a mechanism
     * for matching multiple versions of the same word. For example, when stemming is
     * enabled the word "cats" will match "cat" and "thrill" will match "thrilling".
     * So, stemming makes the keyword list easier to manage when you to be notified
     * of any version of a particular word.<p/>
     * 
     * The stemming implementation uses the Porter algorithm, which is only suitable
     * for English text. If your content is non-english, stemming should be disabled.
     *
     * @return true if stemming is enabled.
     */
    public boolean isStemmingEnabled() {
        return stemmingEnabled;
    }

    /**
     * Toggles whether stemming will be applied to keywords. Stemming is a mechanism
     * for matching multiple versions of the same word. For example, when stemming is
     * enabled the word "cats" will match "cat" and "thrill" will match "thrilling".<p/>
     * 
     * The stemming implementation uses the Porter algorithm, which is only suitable
     * for English text. If your content is non-english, stemming should be disabled.
     *
     * @param stemmingEnabled true if stemming should be enabled.
     */
    public void setStemmingEnabled(boolean stemmingEnabled) {
        // If not changing the value, do nothing.
        if (this.stemmingEnabled == stemmingEnabled) {
            return;
        }
        if (stemmingEnabled) {
            // Turn of stemming.
            this.stemmingEnabled = true;
            analyzer = new StemmingAnalyzer();
        }
        else {
            // Turn off stemming.
            this.stemmingEnabled = false;
            analyzer = new StandardAnalyzer();
        }
    }

    @Override
    public boolean handleRequest(Workgroup workgroup, UserRequest request) {
        return false;
    }

    public boolean search(Workgroup workgroup, Request request, String queryString) {
        return checkForHits(request.getMetaData(), queryString);
    }

    /**
     * Returns true if the query string matches results in the request map.
     *
     * @param requestMap the map of request meta data. Each map key is a String with a value
     *      of a list of Strings.
     * @param queryString the query to test against the map.
     * @return true if the query string matches the request.
     */
    public boolean checkForHits(Map<String, List<String>> requestMap, String queryString) {
        // Enable stemming.
        setStemmingEnabled(true);

        boolean foundMatch = false;
        try {
            // Create an in-memory directory.
            ByteBuffersDirectory dir = new ByteBuffersDirectory();
            // Index the message.
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            IndexWriter writer = new IndexWriter(dir, config);

            BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();
            Document doc = new Document();

            for (String key: requestMap.keySet()) {
                List<String> keyValue = requestMap.get(key);
                if (keyValue != null) {
                    StringBuilder builder = new StringBuilder();
                    for (String value : keyValue) {
                        if (ModelUtil.hasLength(value)) {
                            builder.append(value);
                            builder.append(" ");
                        }
                    }

                    // Add to Search Indexer
                    doc.add(new TextField(key, builder.toString(), Field.Store.YES));

                    QueryParser parser = new QueryParser(key, analyzer);
                    Query query = parser.parse(queryString);
                    booleanQueryBuilder.add(query, BooleanClause.Occur.MUST);
                }
            }

            writer.addDocument(doc);
            writer.close();

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                // Create a searcher, try to find a match.
                IndexSearcher searcher = new IndexSearcher(reader);
                BooleanQuery booleanQuery = booleanQueryBuilder.build();
                TopDocs topDocs = searcher.search(booleanQuery, 10);
                // Check to see if a match was found.
                if (topDocs.totalHits.value > 0) {
                    foundMatch = true;
                }
            }
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }

        return foundMatch;
    }

    /**
     * A Lucene Analyzer that does stemming.
     */
    private class StemmingAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new StandardTokenizer();
            TokenStream lowerCaseFilter = new LowerCaseFilter(tokenizer);
            StopFilter stopFilter = new StopFilter(lowerCaseFilter, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
            PorterStemFilter porterStemFilter = new PorterStemFilter(stopFilter);
            return new TokenStreamComponents(tokenizer, porterStemFilter);
        }
    }
}
