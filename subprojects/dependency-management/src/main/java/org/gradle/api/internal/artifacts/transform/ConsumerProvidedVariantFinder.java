/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.internal.artifacts.ArtifactTransformRegistration;
import org.gradle.api.internal.artifacts.VariantTransformRegistry;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.internal.collections.ImmutableFilteredList;
import org.gradle.internal.component.model.AttributeMatcher;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Finds all the variants that can be created from a given set of producer variants using
 * the consumer's variant transformations. Transformations can be chained. If multiple
 * chains can lead to the same outcome, the shortest path is selected.
 *
 * Caches the results, as often the same request is made for many components in a
 * dependency graph.
 */
public class ConsumerProvidedVariantFinder {
    private final VariantTransformRegistry variantTransforms;
    private final ImmutableAttributesFactory attributesFactory;
    private final CachingAttributeContainerMatcher matcher;
    private final TransformationCache transformationCache;

    public ConsumerProvidedVariantFinder(
        VariantTransformRegistry variantTransforms,
        AttributesSchemaInternal schema,
        ImmutableAttributesFactory attributesFactory
    ) {
        this.variantTransforms = variantTransforms;
        this.attributesFactory = attributesFactory;
        this.matcher = new CachingAttributeContainerMatcher(schema.matcher());
        this.transformationCache = new TransformationCache();
    }

    public List<TransformedVariant> findTransformedVariants(List<ResolvedVariant> sources, ImmutableAttributes requested) {
        return transformationCache.cache(sources, requested, (src, req) ->
            doFindTransformedVariants(src, req, ImmutableFilteredList.allOf(variantTransforms.getTransforms())));
    }

    static class ChainNode {
        final ChainNode next;
        final ArtifactTransformRegistration transform;
        public ChainNode(@Nullable ChainNode next, ArtifactTransformRegistration transform) {
            this.next = next;
            this.transform = transform;
        }
    }

    static class ChainState {
        final ChainNode chain;
        final ImmutableAttributes requested;
        final ImmutableFilteredList<ArtifactTransformRegistration> transforms;
        public ChainState(@Nullable ChainNode chain, ImmutableAttributes requested, ImmutableFilteredList<ArtifactTransformRegistration> transforms) {
            this.chain = chain;
            this.requested = requested;
            this.transforms = transforms;
        }
    }

    private List<TransformedVariant> doFindTransformedVariants(
        List<ImmutableAttributes> sources,
        ImmutableAttributes requested,
        ImmutableFilteredList<ArtifactTransformRegistration> transforms
    ) {
        Queue<ChainState> toProcess = new ArrayDeque<>();
        toProcess.add(new ChainState(null, requested, transforms));

        Queue<ChainState> nextLevel = new ArrayDeque<>();
        List<TransformedVariant> results = new ArrayList<>(1);

        while (results.isEmpty() && !toProcess.isEmpty()) {
            while (!toProcess.isEmpty()) {
                // The set of transforms which could potentially produce a variant compatible with `requested`.
                ChainState state = toProcess.poll();
                ImmutableFilteredList<ArtifactTransformRegistration> candidates =
                    state.transforms.matching(transform -> matcher.isMatching(transform.getTo(), state.requested));

                // For each candidate, attempt to find a source variant that the transformation can use as its root.
                for (ArtifactTransformRegistration candidate : candidates) {
                    for (int i = 0; i < sources.size(); i++) {
                        ImmutableAttributes sourceAttrs = sources.get(i);
                        if (matcher.isMatching(sourceAttrs, candidate.getFrom())) {
                            ImmutableAttributes rootAttrs = attributesFactory.concat(sourceAttrs, candidate.getTo());
                            if (matcher.isMatching(rootAttrs, state.requested)) {

                                TransformedVariant out = new TransformedVariant(i, rootAttrs, candidate.getTransformationStep());

                                ChainNode node = state.chain;
                                while (node != null) {
                                    ImmutableAttributes variantAttributes = attributesFactory.concat(out.getAttributes(), node.transform.getTo());
                                    out = new TransformedVariant(out, variantAttributes, node.transform.getTransformationStep());
                                    node = node.next;
                                }

                                results.add(out);
                            }
                        }
                    }
                }

                // If we have a result at this level, don't bother building the next level's states.
                if (!results.isEmpty()) {
                    continue;
                }

                // If we can't find any solutions at this level, construct new states for processing at the next depth.
                for (int i = 0; i < candidates.size(); i++) {
                    ArtifactTransformRegistration candidate = candidates.get(i);
                    nextLevel.add(new ChainState(
                        new ChainNode(state.chain, candidate),
                        attributesFactory.concat(state.requested, candidate.getFrom()),
                        state.transforms.withoutIndexFrom(i, candidates)
                    ));
                }
            }

            Queue<ChainState> tmp = toProcess;
            toProcess = nextLevel;
            nextLevel = tmp;
        }

        return results;
    }

    private static class TransformationCache {
        private final ConcurrentHashMap<CacheKey, List<TransformedVariant>> cache = new ConcurrentHashMap<>();

        private List<TransformedVariant> cache(
            List<ResolvedVariant> sources, ImmutableAttributes requested,
            BiFunction<List<ImmutableAttributes>, ImmutableAttributes, List<TransformedVariant>> action
        ) {
            ArrayList<ImmutableAttributes> variantAttributes = new ArrayList<>(sources.size());
            for (ResolvedVariant variant : sources) {
                variantAttributes.add(variant.getAttributes().asImmutable());
            }
            return cache.computeIfAbsent(new CacheKey(variantAttributes, requested), key -> action.apply(key.variantAttributes, key.requested));
        }

        private static class CacheKey {
            private final List<ImmutableAttributes> variantAttributes;
            private final ImmutableAttributes requested;
            private final int hashCode;

            public CacheKey(List<ImmutableAttributes> variantAttributes, ImmutableAttributes requested) {
                this.variantAttributes = variantAttributes;
                this.requested = requested;
                this.hashCode = variantAttributes.hashCode() ^ requested.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                CacheKey cacheKey = (CacheKey) o;
                return variantAttributes.equals(cacheKey.variantAttributes) && requested.equals(cacheKey.requested);
            }

            @Override
            public int hashCode() {
                return hashCode;
            }
        }
    }

    /**
     * Caches calls to {@link AttributeMatcher#isMatching(AttributeContainerInternal, AttributeContainerInternal)}
     */
    private static class CachingAttributeContainerMatcher {
        private final AttributeMatcher matcher;
        private final ConcurrentHashMap<CacheKey, Boolean> cache = new ConcurrentHashMap<>();

        public CachingAttributeContainerMatcher(AttributeMatcher matcher) {
            this.matcher = matcher;
        }

        public boolean isMatching(AttributeContainerInternal candidate, AttributeContainerInternal requested) {
            return cache.computeIfAbsent(new CacheKey(candidate, requested), key -> matcher.isMatching(key.candidate, key.requested));
        }

        private static class CacheKey {
            private final AttributeContainerInternal candidate;
            private final AttributeContainerInternal requested;
            private final int hashCode;

            public CacheKey(AttributeContainerInternal candidate, AttributeContainerInternal requested) {
                this.candidate = candidate;
                this.requested = requested;
                this.hashCode = candidate.hashCode() ^ requested.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                CacheKey cacheKey = (CacheKey) o;
                return candidate.equals(cacheKey.candidate) && requested.equals(cacheKey.requested);
            }

            @Override
            public int hashCode() {
                return hashCode;
            }
        }
    }
}
