/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.search;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.core.cluster.WorkQueue;
import org.sonar.core.persistence.Dto;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ExecutionException;

public abstract class BaseIndex<D, E extends Dto<K>, K extends Serializable>
  implements Index<D, E, K> {

  private static final Logger LOG = LoggerFactory.getLogger(BaseIndex.class);

  private final ESNode node;
  private final BaseNormalizer<E, K> normalizer;
  private final IndexDefinition indexDefinition;

  protected BaseIndex(IndexDefinition indexDefinition, BaseNormalizer<E, K> normalizer,
                      WorkQueue workQueue, ESNode node) {
    this.normalizer = normalizer;
    this.node = node;
    this.indexDefinition = indexDefinition;
  }

  @Override
  public String getIndexName() {
    return this.indexDefinition.getIndexName();
  }

  @Override
  public String getIndexType() {
    return this.indexDefinition.getIndexType();
  }

  protected Client getClient() {
    return node.client();
  }

  protected ESNode getNode() {
    return this.node;
  }

  /* Component Methods */

  @Override
  public void start() {

    /* Setup the index if necessary */
    initializeIndex();
  }

  @Override
  public void stop() {

  }

  /* Cluster And ES Stats/Client methods */

  private void initializeManagementIndex() {
    LOG.info("Setup of Management Index for ES");

    String index = indexDefinition.getManagementIndex();

    IndicesExistsResponse indexExistsResponse = getClient().admin().indices()
      .prepareExists(index).execute().actionGet();

    if (!indexExistsResponse.isExists()) {
      getClient().admin().indices().prepareCreate(index)
        .setSettings(ImmutableSettings.builder()
          .put("mapper.dynamic", true)
          .put("number_of_replicas", 1)
          .put("number_of_shards", 1)
          .build())
        .get();
    }
  }

  protected void initializeIndex() {

    initializeManagementIndex();

    String index = this.getIndexName();

    IndicesExistsResponse indexExistsResponse = getClient().admin().indices()
      .prepareExists(index).execute().actionGet();
    try {

      if (!indexExistsResponse.isExists()) {
        LOG.info("Setup of {} for type {}", this.getIndexName(), this.getIndexType());
        getClient().admin().indices().prepareCreate(index)
          .setSettings(getIndexSettings())
          .execute().actionGet();

      }




      LOG.info("Update of index {} for type {}", this.getIndexName(), this.getIndexType());
      getClient().admin().indices().preparePutMapping(index)
        .setType(getIndexType())
        .setIgnoreConflicts(true)
        .setSource(mapDomain())
        .get();

    } catch (Exception e) {
      throw new IllegalStateException("Invalid configuration for index " + this.getIndexName(), e);
    }
  }

  public IndexStat getIndexStat() {
    IndexStat stat = new IndexStat();

    /** get total document count */
    stat.setDocumentCount(
      getClient().prepareCount(this.getIndexName())
        .setQuery(QueryBuilders.matchAllQuery())
        .get().getCount()
    );

    /** get Management information */
    stat.setLastUpdate(getLastSynchronization());
    return stat;
  }

  /* Synchronization methods */

  private void setLastSynchronization() {
    Date time = new Date();
    if (time.after(getLastSynchronization())) {
      LOG.info("Updating synchTime updating");
      getClient().prepareUpdate()
        .setId(indexDefinition.getIndexName())
        .setType(indexDefinition.getManagementType())
        .setIndex(indexDefinition.getManagementIndex())
        .setDoc("updatedAt", time)
        .get();
    }
  }

  @Override
  public Date getLastSynchronization() {
    return (java.util.Date) getClient().prepareGet()
      .setIndex(indexDefinition.getManagementIndex())
      .setId(this.getIndexName())
      .setType(indexDefinition.getManagementType())
      .get().getField("updatedAt").getValue();
  }

  /* Index management methods */

  protected abstract String getKeyValue(K key);

  protected abstract Settings getIndexSettings() throws IOException;

  protected abstract Map mapProperties();

  protected abstract Map mapKey();

  protected Map mapDomain() {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("dynamic", false);
    mapping.put("_id", mapKey());
    mapping.put("properties", mapProperties());
    LOG.debug("Index Mapping {}", mapping.get("properties"));
    return mapping;
  }

  protected Map mapField(IndexField field) {
    return mapField(field, true);
  }

  protected Map mapField(IndexField field, boolean allowRecursive) {
    if (field.type() == IndexField.Type.TEXT) {
      return mapTextField(field, allowRecursive);
    } else if (field.type() == IndexField.Type.STRING) {
      return mapStringField(field, allowRecursive);
    } else if (field.type() == IndexField.Type.BOOLEAN) {
      return mapBooleanField(field);
    } else if (field.type() == IndexField.Type.OBJECT) {
      return mapNestedField(field);
    } else if (field.type() == IndexField.Type.DATE) {
      return mapDateField(field);
    } else if (field.type() == IndexField.Type.NUMERIC) {
      return mapNumericField(field);
    } else {
      throw new IllegalStateException("Mapping does not exist for type: " + field.type());
    }
  }

  protected Map mapNumericField(IndexField field){
      return ImmutableMap.of("type", "double");
  }

  protected Map mapBooleanField(IndexField field) {
    return ImmutableMap.of("type", "boolean");
  }

  protected Map mapNestedField(IndexField field) {
    Map<String, Object> mapping = new HashMap<String, Object>();
    mapping.put("type", "nested");
    Map<String, Object> mappings = new HashMap<String, Object>();
    for (IndexField nestedField : field.nestedFields()) {
      if (nestedField != null) {
        mappings.put(nestedField.field(), mapField(nestedField));
      }
    }
    mapping.put("properties", mappings);
    return mapping;
  }

  protected Map mapDateField(IndexField field) {
    return ImmutableMap.of(
      "type", "date",
      "format", "date_time");
  }

  protected boolean needMultiField(IndexField field) {
    return ((field.type() == IndexField.Type.TEXT
      || field.type() == IndexField.Type.STRING)
      && (field.sortable() || field.searchable()));
  }

  protected Map mapSortField(IndexField field) {
    return ImmutableMap.of(
      "type", "string",
      "index", "analyzed",
      "analyzer", "sortable");
  }

  protected Map mapGramsField(IndexField field) {
    return ImmutableMap.of(
      "type", "string",
      "index", "analyzed",
      "index_analyzer", "index_grams",
      "search_analyzer", "search_grams");
  }

  protected Map mapWordsField(IndexField field) {
    return ImmutableMap.of(
      "type", "string",
      "index", "analyzed",
      "index_analyzer", "index_words",
      "search_analyzer", "search_words");
  }

  protected Map mapMultiField(IndexField field) {
    Map<String, Object> mapping = new HashMap<String, Object>();
    if (field.sortable()) {
      mapping.put(IndexField.SORT_SUFFIX, mapSortField(field));
    }
    if (field.searchable()) {
      if (field.type() != IndexField.Type.TEXT) {
        mapping.put(IndexField.SEARCH_PARTIAL_SUFFIX, mapGramsField(field));
      }
      mapping.put(IndexField.SEARCH_WORDS_SUFFIX, mapWordsField(field));
    }
    mapping.put(field.field(), mapField(field, false));
    return mapping;
  }

  protected Map mapStringField(IndexField field, boolean allowRecursive) {
    Map<String, Object> mapping = new HashMap<String, Object>();
    // check if the field needs to be MultiField
    if (allowRecursive && needMultiField(field)) {
      mapping.put("type", "multi_field");
      mapping.put("fields", mapMultiField(field));
    } else {
      mapping.put("type", "string");
      mapping.put("index", "analyzed");
      mapping.put("index_analyzer", "keyword");
      mapping.put("search_analyzer", "whitespace");
    }
    return mapping;
  }

  protected Map mapTextField(IndexField field, boolean allowRecursive) {
    Map<String, Object> mapping = new HashMap<String, Object>();
    // check if the field needs to be MultiField
    if (allowRecursive && needMultiField(field)) {
      mapping.put("type", "multi_field");
      mapping.put("fields", mapMultiField(field));
    } else {
      mapping.put("type", "string");
      mapping.put("index", "not_analyzed");
    }
    return mapping;
  }

  @Override
  public void refresh() {
    getClient()
      .admin()
      .indices()
      .prepareRefresh(this.getIndexName())
      .get();
  }

  /* Base CRUD methods */

  protected abstract D toDoc(Map<String, Object> fields);

  public D getByKey(K key) {
    GetResponse response = getClient().prepareGet()
      .setType(this.getIndexType())
      .setIndex(this.getIndexName())
      .setId(this.getKeyValue(key))
      .setRouting(this.getKeyValue(key))
      .get();
    if (response.isExists()) {
      return toDoc(response.getSource());
    }
    return null;
  }

  protected void updateDocument(Collection<UpdateRequest> requests, K key) throws Exception {
    LOG.debug("UPDATE _id:{} in index {}", key, this.getIndexName());
    BulkRequestBuilder bulkRequest = getClient().prepareBulk();
    for (UpdateRequest request : requests) {
      bulkRequest.add(request
        .index(this.getIndexName())
        .id(this.getKeyValue(key))
        .type(this.getIndexType()));
    }
    bulkRequest.get();
  }


  @Override
  public void upsert(Object obj, K key) throws Exception {
    this.updateDocument(this.normalizer.normalize(obj, key), key);
  }

  @Override
  public void upsertByDto(E item) {
    try {
      this.updateDocument(normalizer.normalize(item), item.getKey());
    } catch (Exception e) {
      LOG.error("Could not update document for index {}: {}",
        this.getIndexName(), e.getMessage(), e);
    }
  }

  @Override
  public void upsertByKey(K key) {
    try {
      this.updateDocument(normalizer.normalize(key), key);
    } catch (Exception e) {
      LOG.error("Could not update document for index {}: {}",
        this.getIndexName(), e.getMessage(), e);
    }
  }

  private void deleteDocument(K key) throws ExecutionException, InterruptedException {
    LOG.debug("DELETE _id:{} in index {}", key, this.getIndexName());
    getClient()
      .prepareDelete()
      .setIndex(this.getIndexName())
      .setType(this.getIndexType())
      .setId(this.getKeyValue(key))
      .get();
  }

  @Override
  public void delete(Object obj, K key) throws Exception {
    throw new IllegalStateException("Cannot delete nested Object from ES. Should be using Update");
  }

  @Override
  public void deleteByKey(K key) {
    try {
      this.deleteDocument(key);
    } catch (Exception e) {
      LOG.error("Could not DELETE _id = '{}' for index '{}': {}",
        this.getKeyValue(key), this.getIndexName(), e.getMessage());
    }
  }

  @Override
  public void deleteByDto(E item) {
    try {
      this.deleteDocument(item.getKey());
    } catch (Exception e) {
      LOG.error("Could not DELETE _id:{} for index {}: {}",
        this.getKeyValue(item.getKey()), this.getIndexName(), e.getMessage());
    }
  }

  /* ES QueryHelper Methods */


  protected void addMatchField(XContentBuilder mapping, String field, String type) throws IOException {
    mapping.startObject(field)
      .field("type", type)
      .field("index", "not_analyzed")
      .endObject();
  }

  protected BoolFilterBuilder addMultiFieldTermFilter(BoolFilterBuilder filter, @Nullable Collection<String> values, String... fields) {
    if (values != null && !values.isEmpty()) {
      BoolFilterBuilder valuesFilter = FilterBuilders.boolFilter();
      for (String value : values) {
        Collection<FilterBuilder> filterBuilders = new ArrayList<FilterBuilder>();
        for (String field : fields) {
          filterBuilders.add(FilterBuilders.termFilter(field, value));
        }
        valuesFilter.should(FilterBuilders.orFilter(filterBuilders.toArray(new FilterBuilder[filterBuilders.size()])));
      }
      filter.must(valuesFilter);
    }
    return filter;
  }


  protected BoolFilterBuilder addTermFilter(BoolFilterBuilder filter, String field, @Nullable Collection<String> values) {
    if (values != null && !values.isEmpty()) {
      BoolFilterBuilder valuesFilter = FilterBuilders.boolFilter();
      for (String value : values) {
        FilterBuilder valueFilter = FilterBuilders.termFilter(field, value);
        valuesFilter.should(valueFilter);
      }
      filter.must(valuesFilter);
    }
    return filter;
  }

  protected BoolFilterBuilder addTermFilter(BoolFilterBuilder filter, String field, @Nullable String value) {
    if (value != null && !value.isEmpty()) {
      filter.must(FilterBuilders.termFilter(field, value));
    }
    return filter;
  }
}
