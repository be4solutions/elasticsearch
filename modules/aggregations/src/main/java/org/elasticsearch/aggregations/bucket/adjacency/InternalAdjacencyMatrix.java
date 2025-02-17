/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.aggregations.bucket.adjacency;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.search.aggregations.AggregationReduceContext;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.elasticsearch.search.aggregations.support.SamplingContext;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InternalAdjacencyMatrix extends InternalMultiBucketAggregation<InternalAdjacencyMatrix, InternalAdjacencyMatrix.InternalBucket>
    implements
        AdjacencyMatrix {
    public static class InternalBucket extends InternalMultiBucketAggregation.InternalBucket implements AdjacencyMatrix.Bucket {

        private final String key;
        private long docCount;
        InternalAggregations aggregations;

        public InternalBucket(String key, long docCount, InternalAggregations aggregations) {
            this.key = key;
            this.docCount = docCount;
            this.aggregations = aggregations;
        }

        /**
         * Read from a stream.
         */
        public InternalBucket(StreamInput in) throws IOException {
            key = in.readOptionalString();
            docCount = in.readVLong();
            aggregations = InternalAggregations.readFrom(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalString(key);
            out.writeVLong(docCount);
            aggregations.writeTo(out);
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getKeyAsString() {
            return key;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public InternalAggregations getAggregations() {
            return aggregations;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(CommonFields.KEY.getPreferredName(), key);
            builder.field(CommonFields.DOC_COUNT.getPreferredName(), docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            InternalBucket that = (InternalBucket) other;
            return Objects.equals(key, that.key)
                && Objects.equals(docCount, that.docCount)
                && Objects.equals(aggregations, that.aggregations);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getClass(), key, docCount, aggregations);
        }

        InternalBucket finalizeSampling(SamplingContext samplingContext) {
            return new InternalBucket(
                key,
                samplingContext.scaleUp(docCount),
                InternalAggregations.finalizeSampling(aggregations, samplingContext)
            );
        }

    }

    private final List<InternalBucket> buckets;
    private Map<String, InternalBucket> bucketMap;

    public InternalAdjacencyMatrix(String name, List<InternalBucket> buckets, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
    }

    /**
     * Read from a stream.
     */
    public InternalAdjacencyMatrix(StreamInput in) throws IOException {
        super(in);
        int size = in.readVInt();
        List<InternalBucket> buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            buckets.add(new InternalBucket(in));
        }
        this.buckets = buckets;
        this.bucketMap = null;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeCollection(buckets);
    }

    @Override
    public String getWriteableName() {
        return AdjacencyMatrixAggregationBuilder.NAME;
    }

    @Override
    public InternalAdjacencyMatrix create(List<InternalBucket> buckets) {
        return new InternalAdjacencyMatrix(this.name, buckets, this.metadata);
    }

    @Override
    public InternalBucket createBucket(InternalAggregations aggregations, InternalBucket prototype) {
        return new InternalBucket(prototype.key, prototype.docCount, aggregations);
    }

    @Override
    public List<InternalBucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalBucket getBucketByKey(String key) {
        if (bucketMap == null) {
            bucketMap = Maps.newMapWithExpectedSize(buckets.size());
            for (InternalBucket bucket : buckets) {
                bucketMap.put(bucket.getKey(), bucket);
            }
        }
        return bucketMap.get(key);
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, AggregationReduceContext reduceContext) {
        Map<String, List<InternalBucket>> bucketsMap = new HashMap<>();
        for (InternalAggregation aggregation : aggregations) {
            InternalAdjacencyMatrix filters = (InternalAdjacencyMatrix) aggregation;
            for (InternalBucket bucket : filters.buckets) {
                List<InternalBucket> sameRangeList = bucketsMap.computeIfAbsent(bucket.key, k -> new ArrayList<>(aggregations.size()));
                sameRangeList.add(bucket);
            }
        }

        ArrayList<InternalBucket> reducedBuckets = new ArrayList<>(bucketsMap.size());
        for (List<InternalBucket> sameRangeList : bucketsMap.values()) {
            InternalBucket reducedBucket = reduceBucket(sameRangeList, reduceContext);
            if (reducedBucket.docCount >= 1) {
                reducedBuckets.add(reducedBucket);
            }
        }
        reduceContext.consumeBucketsAndMaybeBreak(reducedBuckets.size());
        reducedBuckets.sort(Comparator.comparing(InternalBucket::getKey));

        return new InternalAdjacencyMatrix(name, reducedBuckets, getMetadata());
    }

    @Override
    public InternalAggregation finalizeSampling(SamplingContext samplingContext) {
        return new InternalAdjacencyMatrix(name, buckets.stream().map(b -> b.finalizeSampling(samplingContext)).toList(), getMetadata());
    }

    @Override
    protected InternalBucket reduceBucket(List<InternalBucket> buckets, AggregationReduceContext context) {
        assert buckets.isEmpty() == false;
        InternalBucket reduced = null;
        for (InternalBucket bucket : buckets) {
            if (reduced == null) {
                reduced = new InternalBucket(bucket.key, bucket.docCount, bucket.aggregations);
            } else {
                reduced.docCount += bucket.docCount;
            }
        }
        final List<InternalAggregations> aggregations = new BucketAggregationList<>(buckets);
        reduced.aggregations = InternalAggregations.reduce(aggregations, context);
        return reduced;
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.startArray(CommonFields.BUCKETS.getPreferredName());
        for (InternalBucket bucket : buckets) {
            bucket.toXContent(builder, params);
        }
        builder.endArray();
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), buckets);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;

        InternalAdjacencyMatrix that = (InternalAdjacencyMatrix) obj;
        return Objects.equals(buckets, that.buckets);
    }
}
