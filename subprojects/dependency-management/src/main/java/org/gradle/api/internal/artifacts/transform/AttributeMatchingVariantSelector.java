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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BrokenResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariantSet;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.AttributeDescriber;
import org.gradle.api.internal.attributes.AttributeValue;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.internal.Cast;
import org.gradle.internal.component.AmbiguousVariantSelectionException;
import org.gradle.internal.component.NoMatchingVariantSelectionException;
import org.gradle.internal.component.VariantSelectionException;
import org.gradle.internal.component.model.AttributeMatcher;
import org.gradle.internal.component.model.AttributeMatchingExplanationBuilder;
import org.gradle.internal.component.model.DescriberSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class AttributeMatchingVariantSelector implements VariantSelector {
    private final ConsumerProvidedVariantFinder consumerProvidedVariantFinder;
    private final AttributesSchemaInternal schema;
    private final ImmutableAttributesFactory attributesFactory;
    private final TransformedVariantFactory transformedVariantFactory;
    private final ImmutableAttributes requested;
    private final boolean ignoreWhenNoMatches;

    private final boolean selectFromAllVariants;
    private final ExtraExecutionGraphDependenciesResolverFactory dependenciesResolver;

    AttributeMatchingVariantSelector(
            ConsumerProvidedVariantFinder consumerProvidedVariantFinder,
            AttributesSchemaInternal schema,
            ImmutableAttributesFactory attributesFactory,
            TransformedVariantFactory transformedVariantFactory,
            AttributeContainerInternal requested,
            boolean ignoreWhenNoMatches,
            boolean selectFromAllVariants, ExtraExecutionGraphDependenciesResolverFactory dependenciesResolver
    ) {
        this.consumerProvidedVariantFinder = consumerProvidedVariantFinder;
        this.schema = schema;
        this.attributesFactory = attributesFactory;
        this.transformedVariantFactory = transformedVariantFactory;
        this.requested = requested.asImmutable();
        this.ignoreWhenNoMatches = ignoreWhenNoMatches;
        this.selectFromAllVariants = selectFromAllVariants;
        this.dependenciesResolver = dependenciesResolver;
    }

    @Override
    public String toString() {
        return "Variant selector for " + requested;
    }

    @Override
    public ImmutableAttributes getRequestedAttributes() {
        return requested;
    }

    @Override
    public ResolvedArtifactSet select(ResolvedVariantSet producer, Factory factory) {
        try {
            return doSelect(producer, factory, AttributeMatchingExplanationBuilder.logging());
        } catch (VariantSelectionException t) {
            return new BrokenResolvedArtifactSet(t);
        } catch (Exception t) {
            return new BrokenResolvedArtifactSet(VariantSelectionException.selectionFailed(producer, t));
        }
    }

    private ResolvedArtifactSet doSelect(ResolvedVariantSet producer, Factory factory, AttributeMatchingExplanationBuilder explanationBuilder) {
        AttributeMatcher matcher = schema.withProducer(producer.getSchema());
        ImmutableAttributes componentRequested = attributesFactory.concat(requested, producer.getOverriddenAttributes());
        final List<ResolvedVariant> variants;
        if (selectFromAllVariants) {
            variants = ImmutableList.copyOf(producer.getAllVariants());
        } else {
            variants = ImmutableList.copyOf(producer.getVariants());
        }

        List<? extends ResolvedVariant> matches = matcher.matches(variants, componentRequested, explanationBuilder);
        if (matches.size() == 1) {
            return matches.get(0).getArtifacts();
        } else if (matches.size() > 1) {
            // Request is ambiguous. Rerun matching again, except capture an explanation this time for reporting.
            TraceDiscardedVariants newExpBuilder = new TraceDiscardedVariants();
            matches = matcher.matches(variants, componentRequested, newExpBuilder);

            Set<ResolvedVariant> discarded = Cast.uncheckedCast(newExpBuilder.discarded);
            AttributeDescriber describer = DescriberSelector.selectDescriber(componentRequested, schema);
            throw new AmbiguousVariantSelectionException(describer, producer.asDescribable().getDisplayName(), componentRequested, matches, matcher, discarded);
        }

        // We found no matches. Attempt to construct artifact transform chains which produce matching variants.
        List<TransformedVariant> transformedVariants = consumerProvidedVariantFinder.findTransformedVariants(variants, componentRequested);

        // If we have multiple potential artifact transform variants which can match our requested attributes, attempt to choose the best.
        if (transformedVariants.size() > 1) {
            transformedVariants = findBestTransformChains(matcher, transformedVariants, componentRequested, explanationBuilder);
        }

        if (transformedVariants.size() == 1) {
            TransformedVariant result = transformedVariants.get(0);
            ResolvedVariant root = variants.get(result.getRootIndex());
            return factory.asTransformed(root, result, dependenciesResolver, transformedVariantFactory);
        }

        if (!transformedVariants.isEmpty()) {
            throw new AmbiguousTransformException(producer.asDescribable().getDisplayName(), componentRequested, variants, transformedVariants);
        }

        if (ignoreWhenNoMatches) {
            return ResolvedArtifactSet.EMPTY;
        }

        throw new NoMatchingVariantSelectionException(producer.asDescribable().getDisplayName(), componentRequested, variants, matcher, DescriberSelector.selectDescriber(componentRequested, schema));
    }

    private List<TransformedVariant> findBestTransformChains(AttributeMatcher matcher, List<TransformedVariant> candidates, ImmutableAttributes componentRequested, AttributeMatchingExplanationBuilder explanationBuilder) {
        // Perform attribute matching against the potential transformed variant candidates.
        // One of the potential transform chains may produce a more preferable variant compared to another.
        candidates = disambiguateWithSchema(matcher, candidates, componentRequested, explanationBuilder);

        if (candidates.size() == 1) {
            return candidates;
        }

        if (candidates.size() == 2) {
            // Short circuit logic when only 2 candidates
            return compareCandidates(matcher, candidates.get(0), candidates.get(1))
                .map(Collections::singletonList)
                .orElse(candidates);
        }

        List<TransformedVariant> shortestTransforms = new ArrayList<>(candidates.size());
        candidates = new ArrayList<>(candidates);
        candidates.sort(Comparator.comparingInt(TransformedVariant::getDepth));

        // Need to remember if a further element was matched by an earlier one, no need to consider it then
        boolean[] hasBetterMatch = new boolean[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            if (hasBetterMatch[i]) {
                continue;
            }
            boolean candidateIsDifferent = true;
            TransformedVariant current = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                if (hasBetterMatch[j]) {
                    continue;
                }
                int index = j; // Needed to use inside lambda below
                candidateIsDifferent = compareCandidates(matcher, current, candidates.get(index)).map(candidate -> {
                    if (candidate != current) {
                        // The other is better, current is not part of result
                        return false;
                    } else {
                        // The other is disambiguated by current, never consider other again
                        hasBetterMatch[index] = true;
                    }
                    return true;
                }).orElse(true);
            }
            if (candidateIsDifferent) {
                shortestTransforms.add(current);
            }
        }
        return shortestTransforms;
    }

    private List<TransformedVariant> disambiguateWithSchema(AttributeMatcher matcher, List<TransformedVariant> candidates, ImmutableAttributes componentRequested, AttributeMatchingExplanationBuilder explanationBuilder) {
        List<TransformedVariant> matches = matcher.matches(candidates, componentRequested, explanationBuilder);
        if (matches.size() > 0) {
            return matches;
        }
        return candidates;
    }

    private Optional<TransformedVariant> compareCandidates(AttributeMatcher matcher, TransformedVariant firstCandidate, TransformedVariant secondCandidate) {
        if (matcher.isMatching(firstCandidate.getAttributes(), secondCandidate.getAttributes()) ||
            matcher.isMatching(secondCandidate.getAttributes(), firstCandidate.getAttributes())) {
            if (firstCandidate.getDepth() >= secondCandidate.getDepth()) {
                return Optional.of(secondCandidate);
            } else {
                return Optional.of(firstCandidate);
            }
        }
        return Optional.empty();
    }

    private static class TraceDiscardedVariants implements AttributeMatchingExplanationBuilder {

        private final Set<HasAttributes> discarded = Sets.newHashSet();

        @Override
        public boolean canSkipExplanation() {
            return false;
        }

        @Override
        public <T extends HasAttributes> void candidateDoesNotMatchAttributes(T candidate, AttributeContainerInternal requested) {
            recordDiscardedCandidate(candidate);
        }

        public <T extends HasAttributes> void recordDiscardedCandidate(T candidate) {
            discarded.add(candidate);
        }

        @Override
        public <T extends HasAttributes> void candidateAttributeDoesNotMatch(T candidate, Attribute<?> attribute, Object requestedValue, AttributeValue<?> candidateValue) {
            recordDiscardedCandidate(candidate);
        }
    }
}
