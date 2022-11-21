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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
        // TODO: Should we cache the transforms too?
        // This needs performance testing.
        return transformationCache.cache(sources, requested, (src, req) ->
            doFindTransformedVariants(src, req, ImmutableFilteredList.allOf(variantTransforms.getTransforms())));
    }

    private List<TransformedVariant> doFindTransformedVariants(
        List<ImmutableAttributes> sources,
        ImmutableAttributes requested,
        ImmutableFilteredList<ArtifactTransformRegistration> transforms
    ) {
        // The set of transforms which could potentially produce a variant compatible with `requested`.
        ImmutableFilteredList<ArtifactTransformRegistration> candidates =
            transforms.matching(transform -> matcher.isMatching(transform.getTo(), requested));

        AttributeGroupingVariantCollector result = new AttributeGroupingVariantCollector();

        // For each candidate, attempt to find a source variant that the transformation can use as its root.
        for (ArtifactTransformRegistration candidate : candidates) {
            for (int i = 0; i < sources.size(); i++) {
                ImmutableAttributes sourceAttrs = sources.get(i);
                if (matcher.isMatching(sourceAttrs, candidate.getFrom())) {
                    ImmutableAttributes variantAttributes = attributesFactory.concat(sourceAttrs, candidate.getTo());
                    if (matcher.isMatching(variantAttributes, requested)) {
                        result.matched(new TransformedVariant(i, variantAttributes, candidate.getTransformationStep()));
                    }
                }
            }
        }

        // If we found a compatible root transform, return it.
        if (result.hasMatches()) {
            return result.getMatches();
        }

        // Otherwise, for each candidate, attempt to find another chain of transforms which match it's `from` attributes.
        for (int i = 0; i < candidates.size(); i++) {
            ArtifactTransformRegistration candidate = candidates.get(i);

            ImmutableFilteredList<ArtifactTransformRegistration> newTransforms = transforms.withoutIndexFrom(i, candidates);
            ImmutableAttributes requestedPrevious = attributesFactory.concat(requested, candidate.getFrom());
            List<TransformedVariant> inputVariants = doFindTransformedVariants(sources, requestedPrevious, newTransforms);

            for (TransformedVariant inputVariant : inputVariants) {
                ImmutableAttributes variantAttributes = attributesFactory.concat(inputVariant.getAttributes().asImmutable(), candidate.getTo());
                result.matched(new TransformedVariant(inputVariant, variantAttributes, candidate.getTransformationStep()));
            }
        }

        return result.getMatches();
    }

    /**
     * Collects {@link TransformedVariant}s, grouping them by their attributes. For a given attribute set, only the variants
     * with the smallest transform depth are saved.
     */
    private static class AttributeGroupingVariantCollector {

        private final Map<AttributeContainerInternal, List<TransformedVariant>> matches = new LinkedHashMap<>(1);

        public void matched(TransformedVariant variant) {
            List<TransformedVariant> group = matches.computeIfAbsent(variant.getAttributes(), attrs -> new ArrayList<>(1));

            if (group.isEmpty()) {
                group.add(variant);
            } else {
                // All variants in a group have the same depth.
                int depth = group.get(0).getDepth();
                if (variant.getDepth() == depth){
                    group.add(variant);
                } else if (variant.getDepth() < depth) {
                    group.clear();
                    group.add(variant);
                }
            }
        }

        public boolean hasMatches() {
            return matches.values().stream().anyMatch(group -> !group.isEmpty());
        }

        public List<TransformedVariant> getMatches() {
            return matches.values().stream().flatMap(List::stream).collect(Collectors.toList());
        }
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
