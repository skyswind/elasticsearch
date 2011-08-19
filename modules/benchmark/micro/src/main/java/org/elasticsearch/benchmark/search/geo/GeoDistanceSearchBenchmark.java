/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.benchmark.search.geo;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.SizeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.search.geo.GeoDistance;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;

/**
 */
public class GeoDistanceSearchBenchmark {

    public static void main(String[] args) throws Exception {

        Node node = NodeBuilder.nodeBuilder().node();
        Client client = node.client();

        ClusterHealthResponse clusterHealthResponse = client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        if (clusterHealthResponse.timedOut()) {
            System.err.println("Failed to wait for green status, bailing");
            System.exit(1);
        }

        final long NUM_DOCS = SizeValue.parseSizeValue("1m").singles();
        final long NUM_WARM = 50;
        final long NUM_RUNS = 100;

        if (client.admin().indices().prepareExists("test").execute().actionGet().exists()) {
            System.out.println("Found an index, count: " + client.prepareCount("test").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count());
        } else {
            String mapping = XContentFactory.jsonBuilder().startObject().startObject("type1")
                    .startObject("properties").startObject("location").field("type", "geo_point").field("lat_lon", true).endObject().endObject()
                    .endObject().endObject().string();
            client.admin().indices().prepareCreate("test")
                    .setSettings(ImmutableSettings.settingsBuilder().put("number_of_shards", 1).put("number_of_replicas", 0))
                    .addMapping("type1", mapping)
                    .execute().actionGet();

            System.err.println("--> Indexing [" + NUM_DOCS + "]");
            for (long i = 0; i < NUM_DOCS; ) {
                client.prepareIndex("test", "type1", Long.toString(i++)).setSource(jsonBuilder().startObject()
                        .field("name", "New York")
                        .startObject("location").field("lat", 40.7143528).field("lon", -74.0059731).endObject()
                        .endObject()).execute().actionGet();

                // to NY: 5.286 km
                client.prepareIndex("test", "type1", Long.toString(i++)).setSource(jsonBuilder().startObject()
                        .field("name", "Times Square")
                        .startObject("location").field("lat", 40.759011).field("lon", -73.9844722).endObject()
                        .endObject()).execute().actionGet();

                // to NY: 0.4621 km
                client.prepareIndex("test", "type1", Long.toString(i++)).setSource(jsonBuilder().startObject()
                        .field("name", "Tribeca")
                        .startObject("location").field("lat", 40.718266).field("lon", -74.007819).endObject()
                        .endObject()).execute().actionGet();

                // to NY: 1.258 km
                client.prepareIndex("test", "type1", Long.toString(i++)).setSource(jsonBuilder().startObject()
                        .field("name", "Soho")
                        .startObject("location").field("lat", 40.7247222).field("lon", -74).endObject()
                        .endObject()).execute().actionGet();

                // to NY: 8.572 km
                client.prepareIndex("test", "type1", Long.toString(i++)).setSource(jsonBuilder().startObject()
                        .field("name", "Brooklyn")
                        .startObject("location").field("lat", 40.65).field("lon", -73.95).endObject()
                        .endObject()).execute().actionGet();

                if ((i % 10000) == 0) {
                    System.err.println("--> indexed " + i);
                }
            }
            System.err.println("Done indexed");
            client.admin().indices().prepareFlush("test").execute().actionGet();
            client.admin().indices().prepareRefresh().execute().actionGet();
        }

        System.err.println("--> Warming up (ARC)");
        long start = System.currentTimeMillis();
        for (int i = 0; i < NUM_WARM; i++) {
            run(client, GeoDistance.ARC);
        }
        long totalTime = System.currentTimeMillis() - start;
        System.out.println("--> Warmup (ARC) " + (totalTime / NUM_WARM) + "ms");

        System.err.println("--> Perf (ARC)");
        start = System.currentTimeMillis();
        for (int i = 0; i < NUM_RUNS; i++) {
            run(client, GeoDistance.ARC);
        }
        totalTime = System.currentTimeMillis() - start;
        System.out.println("--> Perf (ARC) " + (totalTime / NUM_RUNS) + "ms");

        System.err.println("--> Warming up (PLANE)");
        start = System.currentTimeMillis();
        for (int i = 0; i < NUM_WARM; i++) {
            run(client, GeoDistance.PLANE);
        }
        totalTime = System.currentTimeMillis() - start;
        System.out.println("--> Warmup (PLANE) " + (totalTime / NUM_WARM) + "ms");

        System.err.println("--> Perf (PLANE)");
        start = System.currentTimeMillis();
        for (int i = 0; i < NUM_RUNS; i++) {
            run(client, GeoDistance.PLANE);
        }
        totalTime = System.currentTimeMillis() - start;
        System.out.println("--> Perf (PLANE) " + (totalTime / NUM_RUNS) + "ms");

        node.close();
    }

    public static void run(Client client, GeoDistance geoDistance) {
        client.prepareSearch() // from NY
                .setSearchType(SearchType.COUNT)
                .setQuery(filteredQuery(matchAllQuery(), geoDistanceFilter("location")
                        .distance("2km")
                        .geoDistance(geoDistance)
                        .point(40.7143528, -74.0059731)))
                .execute().actionGet();
    }
}