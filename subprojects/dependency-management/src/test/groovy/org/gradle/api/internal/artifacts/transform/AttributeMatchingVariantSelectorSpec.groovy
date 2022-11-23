/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform


import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BrokenResolvedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariantSet
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.Describables
import org.gradle.internal.component.AmbiguousVariantSelectionException
import org.gradle.internal.component.model.AttributeMatcher
import org.gradle.util.AttributeTestUtil
import spock.lang.Specification

class AttributeMatchingVariantSelectorSpec extends Specification {

    def consumerProvidedVariantFinder = Mock(ConsumerProvidedVariantFinder)
    def transformedVariantFactory = Mock(TransformedVariantFactory)
    def dependenciesResolverFactory = Mock(ExtraExecutionGraphDependenciesResolverFactory)
    def attributeMatcher = Mock(AttributeMatcher)
    def attributesSchema = Mock(AttributesSchemaInternal) {
        withProducer(_) >> attributeMatcher
        getConsumerDescribers() >> []
    }
    def attributesFactory = AttributeTestUtil.attributesFactory()
    def requestedAttributes = AttributeTestUtil.attributes(['artifactType': 'jar'])

    def variant = Mock(ResolvedVariant) {
        getAttributes() >> AttributeTestUtil.attributes(['artifactType': 'jar'])
        asDescribable() >> Describables.of("mock resolved variant")
    }
    def otherVariant = Mock(ResolvedVariant) {
        getAttributes() >> AttributeTestUtil.attributes(['artifactType': 'classes'])
        asDescribable() >> Describables.of("mock other resolved variant")
    }
    def yetAnotherVariant = Mock(ResolvedVariant) {
        getAttributes() >> AttributeTestUtil.attributes(['artifactType': 'foo'])
        asDescribable() >> Describables.of("mock another resolved variant")
    }

    def factory = Mock(VariantSelector.Factory)

    def 'direct match on variant means no finder interaction'() {
        given:
        def resolvedArtifactSet = Mock(ResolvedArtifactSet)
        def variantSet = variantSetOf([variant])
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(variantSet, factory)

        then:
        result == resolvedArtifactSet
        1 * attributeMatcher.matches(_, _, _) >> [variant]
        1 * variant.getArtifacts() >> resolvedArtifactSet
        0 * consumerProvidedVariantFinder._
    }

    def 'multiple match on variant results in ambiguous exception'() {
        given:
        def otherResolvedVariant = Mock(ResolvedVariant) {
            asDescribable() >> Describables.of('other mocked variant')
            getAttributes() >> otherVariant.getAttributes()
        }
        def variantSet = variantSetOf([variant])
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(variantSet, factory)

        then:
        result instanceof BrokenResolvedArtifactSet
        result.failure instanceof AmbiguousVariantSelectionException

        1 * variantSet.getSchema() >> attributesSchema
        1 * variantSet.getOverriddenAttributes() >> ImmutableAttributes.EMPTY
        2 * attributeMatcher.matches(_, _, _) >> [variant, otherResolvedVariant]
        2 * attributeMatcher.isMatching(_, _, _) >> true
        0 * consumerProvidedVariantFinder._
    }

    def 'selects matching variant and creates wrapper'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def variantSet = variantSetOf([variant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(variantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant], requestedAttributes) >> transformedVariants
        1 * factory.asTransformed(variant, transformedVariants[0], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'can disambiguate sub chains'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def multiVariantSet = variantSetOf([variant, otherVariant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes),
            transformedVariant(otherVariant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * attributeMatcher.isMatching(requestedAttributes, requestedAttributes) >> true
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant], requestedAttributes) >> transformedVariants
        1 * attributeMatcher.matches(_, _, _) >> { args -> args[0] }
        1 * factory.asTransformed(variant, transformedVariants[0], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'can disambiguate 2 equivalent chains by picking shortest'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def multiVariantSet = variantSetOf([variant, otherVariant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes),
            transformedVariant(otherVariant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * attributeMatcher.isMatching(requestedAttributes, requestedAttributes) >> true
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant], requestedAttributes) >> transformedVariants
        1 * attributeMatcher.matches(_, _, _) >> { args -> args[0] }
        1 * factory.asTransformed(variant, transformedVariants[0], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'can leverage schema disambiguation'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def multiVariantSet = variantSetOf([variant, otherVariant])
        def transformedVariants = [
            transformedVariant(variant, otherVariant.getAttributes()),
            transformedVariant(otherVariant, variant.getAttributes())
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant], requestedAttributes) >> transformedVariants
        1 * attributeMatcher.matches(_, _, _) >> { args -> [args[0].get(0)] }
        1 * factory.asTransformed(variant, transformedVariants[0], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'can disambiguate between three chains when one subset of both others'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def multiVariantSet = variantSetOf([variant, otherVariant, yetAnotherVariant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes),
            transformedVariant(otherVariant, requestedAttributes),
            transformedVariant(yetAnotherVariant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        2 * attributeMatcher.isMatching(requestedAttributes, requestedAttributes) >> true
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant, yetAnotherVariant], requestedAttributes) >> transformedVariants
        1 * attributeMatcher.matches(_, _, _) >> { args -> args[0] }
        1 * factory.asTransformed(yetAnotherVariant, transformedVariants[2], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'can disambiguate 3 equivalent chains by picking shortest'() {
        given:
        def transformed = Mock(ResolvedArtifactSet)
        def multiVariantSet = variantSetOf([variant, otherVariant, yetAnotherVariant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes),
            transformedVariant(otherVariant, requestedAttributes),
            transformedVariant(yetAnotherVariant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result == transformed

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant, yetAnotherVariant], requestedAttributes) >> transformedVariants
        2 * attributeMatcher.isMatching(requestedAttributes, requestedAttributes) >> true
        1 * attributeMatcher.matches(_, _, _) >> { args -> args[0] }
        1 * factory.asTransformed(otherVariant, transformedVariants[1], dependenciesResolverFactory, transformedVariantFactory) >> transformed
    }

    def 'cannot disambiguate 3 chains when 2 different'() {
        given:
        def multiVariantSet = variantSetOf([variant, otherVariant, yetAnotherVariant])
        def transformedVariants = [
            transformedVariant(variant, requestedAttributes),
            transformedVariant(otherVariant, requestedAttributes),
            transformedVariant(yetAnotherVariant, requestedAttributes)
        ]
        def selector = new AttributeMatchingVariantSelector(consumerProvidedVariantFinder, attributesSchema, attributesFactory, transformedVariantFactory, requestedAttributes, false, false, dependenciesResolverFactory)

        when:
        def result = selector.select(multiVariantSet, factory)

        then:
        result instanceof BrokenResolvedArtifactSet
        result.failure instanceof AmbiguousTransformException

        1 * attributeMatcher.matches(_, _, _) >> Collections.emptyList()
        1 * consumerProvidedVariantFinder.findTransformedVariants([variant, otherVariant, yetAnotherVariant], requestedAttributes) >> transformedVariants
        1 * attributeMatcher.matches(_, _, _) >> { args -> args[0] }
        3 * attributeMatcher.isMatching(requestedAttributes, requestedAttributes) >>> [false, false, true]
    }

    ResolvedVariantSet variantSetOf(List<ResolvedVariant> variants) {
        return Mock(ResolvedVariantSet) {
            asDescribable() >> Describables.of("mock producer")
            getVariants() >> variants
            getOverriddenAttributes() >> ImmutableAttributes.EMPTY
        }
    }

    TransformedVariant transformedVariant(ResolvedVariant root, AttributeContainerInternal attributes) {
        ImmutableAttributes attrs = attributes.asImmutable()
        TransformationStep step = Mock(TransformationStep) {
            getDisplayName() >> ""
        }
        VariantDefinition definition = Mock(VariantDefinition) {
            getTransformation() >> step
            getTargetAttributes() >> attrs
        }
        return new TransformedVariant(root, definition)
    }
}
