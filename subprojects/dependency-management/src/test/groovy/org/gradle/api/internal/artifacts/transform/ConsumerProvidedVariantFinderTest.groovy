/*
 * Copyright 2016 the original author or authors.
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

import com.google.common.collect.ImmutableList
import junit.framework.AssertionFailedError
import org.gradle.api.Action
import org.gradle.api.Transformer
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.ArtifactTransformRegistration
import org.gradle.api.internal.artifacts.VariantTransformRegistry
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.internal.Deferrable
import org.gradle.internal.Try
import org.gradle.internal.component.model.AttributeMatcher
import org.gradle.util.AttributeTestUtil
import spock.lang.Issue
import spock.lang.Specification

class ConsumerProvidedVariantFinderTest extends Specification {
    def matcher = Mock(AttributeMatcher)
    def schema = Mock(AttributesSchemaInternal)
    def immutableAttributesFactory = AttributeTestUtil.attributesFactory()
    def transformRegistrations = Mock(VariantTransformRegistry)

    def c1 = AttributeTestUtil.attributes(a1: "1", a2: 1)
    def c2 = AttributeTestUtil.attributes(a1: "1", a2: 2)
    def c3 = AttributeTestUtil.attributes(a1: "1", a2: 3)

    ConsumerProvidedVariantFinder matchingCache
    def setup() {
        schema.matcher() >> matcher
        matchingCache = new ConsumerProvidedVariantFinder(transformRegistrations, schema, immutableAttributesFactory)
    }

    /**
     * Match all AttributeContainer that contains the same attributes.
     *
     * This method is for writing argument constraint in spock interaction. When search for
     * chains, ConsumerProvidedVariantFinder may create a new instance of the AttributeContainer
     * to call {@link AttributeMatcher#isMatching(AttributeContainerInternal, AttributeContainerInternal)}.
     * So we cannot use the origin object instance to write method specification in spock interaction.
     */
    def attributesIs(AttributeContainer except, Map<String, Object> vals) {
        def attrs = AttributeTestUtil.attributes(vals).asMap()
        return except.keySet().size() == attrs.size() && attrs.every { entry ->
            except.getAttribute(entry.key) == entry.value
        }
    }

    def "selects transform that can produce variant that is compatible with requested"() {
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def reg3 = registration(c2, c3, {})
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]

        given:
        transformRegistrations.transforms >> [reg1, reg2, reg3]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.size() == 1
        result.first().attributes == c2
        result.first().transformation.is(reg2.transformationStep)

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> true
        0 * matcher._
    }

    def "selects all transforms that can produce variant that is compatible with requested"() {
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def reg3 = registration(c2, c3, {})
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]

        given:
        transformRegistrations.transforms >> [reg1, reg2, reg3]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.size() == 2
        result*.attributes == [c3, c2]
        result*.transformation == [reg1.transformationStep, reg2.transformationStep]

        and:
        1 * matcher.isMatching(c3, requested) >> true
        1 * matcher.isMatching(c2, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c2) >> false
        0 * matcher._
    }

    def "transform match is reused"() {
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]

        given:
        transformRegistrations.transforms >> [reg1, reg2]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        def match = result.first()
        match.transformation.is(reg2.transformationStep)

        and:
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> true
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> true
        0 * matcher._

        when:
        def result2 = matchingCache.findTransformedVariants(variants, requested)

        then:
        def match2 = result2.first()
        match2.attributes.is(match.attributes)
        match2.transformation.is(match.transformation)

        and:
        0 * matcher._
    }

    def "selects chain of transforms that can produce variant that is compatible with requested"() {
        def c4 = AttributeTestUtil.attributes([a1: "4"])
        def c5 = AttributeTestUtil.attributes([a1: "5"])
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]
        def reg1 = registration(c1, c3, { throw new AssertionFailedError() })
        def reg2 = registration(c1, c2, { File f -> [new File(f.name + ".2a"), new File(f.name + ".2b")] })
        def reg3 = registration(c4, c5, { File f -> [new File(f.name + ".5")] })

        given:
        transformRegistrations.transforms >> [reg1, reg2, reg3]

        when:
        def matchResult = matchingCache.findTransformedVariants(variants, requested)

        then:
        def match = matchResult.first()
        match != null

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> false
        1 * matcher.isMatching(c5, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c4) >> false
        1 * matcher.isMatching(c3, { attributesIs(it, [a1: "4"]) }) >> false
        1 * matcher.isMatching(c2, { attributesIs(it, [a1: "4"]) }) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> true
        0 * matcher._

        when:
        def result = run(match.transformation, initialSubject("in.txt"))

        then:
        result.files == [new File("in.txt.2a.5"), new File("in.txt.2b.5")]
        0 * _
    }

    TransformationSubject run(Transformation transformation, TransformationSubject initialSubject) {
        def steps = [] as List<TransformationStep>
        transformation.visitTransformationSteps { step ->
            steps.add(step)
        }
        def first = steps.first()
        def invocation = first.createInvocation(initialSubject, Mock(TransformUpstreamDependencies), null)
        steps.drop(1).forEach { step ->
            invocation = invocation
                .flatMap { result ->
                    result
                        .map(subject -> step.createInvocation(subject, Mock(TransformUpstreamDependencies), null))
                        .getOrMapFailure(failure -> Deferrable.completed(Try.failure(failure)))
                }
        }
        return invocation.completeAndGet().get()
    }

    def "prefers direct transformation over indirect"() {
        def c4 = AttributeTestUtil.attributes([a1: "4"])
        def c5 = AttributeTestUtil.attributes([a1: "5"])
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def reg3 = registration(c4, c5, {})

        given:
        transformRegistrations.transforms >> [reg1, reg2, reg3]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.first().transformation.is(reg3.transformationStep)

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> true
        1 * matcher.isMatching(c5, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> false
        1 * matcher.isMatching(variants[0].getAttributes(), c4) >> true
        0 * matcher._
    }

    def "prefers shortest chain of transforms #registrationsIndex"() {
        def transform1 = Mock(Transformer)
        def transform2 = Mock(Transformer)
        def c4 = AttributeTestUtil.attributes([a1: "4"])
        def c5 = AttributeTestUtil.attributes([a1: "5"])
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]
        def reg1 = registration(c2, c3, {})
        def reg2 = registration(c2, c4, transform1)
        def reg3 = registration(c3, c4, {})
        def reg4 = registration(c4, c5, transform2)
        def registrations = [reg1, reg2, reg3, reg4]

        def requestedForReg4 = immutableAttributesFactory.concat(requested, c4)

        given:
        transformRegistrations.transforms >> [registrations[registrationsIndex[0]], registrations[registrationsIndex[1]], registrations[registrationsIndex[2]], registrations[registrationsIndex[3]]]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.size() == 1

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c4, requested) >> false
        1 * matcher.isMatching(c5, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c4) >> false
        1 * matcher.isMatching(c3, requestedForReg4) >> false
        1 * matcher.isMatching(c4, requestedForReg4) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c2) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c3) >> false
        0 * matcher._

        when:
        def files = run(result.first().transformation, initialSubject("a")).files

        then:
        files == [new File("d"), new File("e")]
        transform1.transform(new File("a")) >> [new File("b"), new File("c")]
        transform2.transform(new File("b")) >> [new File("d")]
        transform2.transform(new File("c")) >> [new File("e")]

        where:
        registrationsIndex << (0..3).permutations()
    }

    @Issue("gradle/gradle#7061")
    def "selects chain of transforms that only all the attributes are satisfied"() {
        def c4 = AttributeTestUtil.attributes([a1: "2", a2: 2])
        def c5 = AttributeTestUtil.attributes([a1: "2", a2: 3])
        def c6 = AttributeTestUtil.attributes([a1: "2"])
        def c7 = AttributeTestUtil.attributes([a1: "3"])
        def requested = AttributeTestUtil.attributes([a1: "3", a2: 3])
        def variants = [
            variant([a1: "1", a2: 1])
        ]
        def reg1 = registration(c1, c4, {})
        def reg2 = registration(c1, c5, {})
        def reg3 = registration(c6, c7, {})

        given:
        transformRegistrations.transforms >> [reg1, reg2, reg3]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.size() == 1

        and:
        1 * matcher.isMatching(c4, requested) >> false
        1 * matcher.isMatching(c5, requested) >> false
        1 * matcher.isMatching(c7, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c6) >> false
        1 * matcher.isMatching(c4, { attributesIs(it, [a1: "2", a2: 3]) }) >> false // "2" 3 ; c5
        1 * matcher.isMatching(c5, { attributesIs(it, [a1: "2", a2: 3]) }) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), c1) >> true
        0 * matcher._

        expect:
        result.size() == 1
    }

    def "returns empty list when no transforms are available to produce requested variant"() {
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]

        given:
        transformRegistrations.transforms >> [reg1, reg2]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.empty

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> false
        0 * matcher._
    }

    def "caches negative match"() {
        def reg1 = registration(c1, c3, {})
        def reg2 = registration(c1, c2, {})
        def requested = AttributeTestUtil.attributes([a1: "requested"])
        def variants = [
            variant([a1: "source"])
        ]

        given:
        transformRegistrations.transforms >> [reg1, reg2]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.empty

        and:
        1 * matcher.isMatching(c3, requested) >> false
        1 * matcher.isMatching(c2, requested) >> false
        0 * matcher._

        when:
        def result2 = matchingCache.findTransformedVariants(variants, requested)

        then:
        result2.empty

        and:
        0 * matcher._
    }

    def "does not match on unrelated transform"() {
        def from = AttributeTestUtil.attributes([a2: 1])
        def to = AttributeTestUtil.attributes([a2: 42])

        def variants = [
            variant([a1: "source"])
        ]
        def requested = AttributeTestUtil.attributes([a1: "hello"])

        def reg1 = registration(from, to, {})

        def concatTo = immutableAttributesFactory.concat(variants[0].getAttributes().asImmutable(), to)

        given:
        transformRegistrations.transforms >> [reg1]

        when:
        def result = matchingCache.findTransformedVariants(variants, requested)

        then:
        result.empty

        and:
        1 * matcher.isMatching(to, requested) >> true
        1 * matcher.isMatching(variants[0].getAttributes(), from) >> true
        1 * matcher.isMatching(concatTo, requested) >> false
        0 * matcher._
    }

    private TransformationSubject initialSubject(String path) {
        def artifact = Stub(ResolvableArtifact) {
            getFile() >> new File(path)
        }
        TransformationSubject.initial(artifact)
    }

    private ArtifactTransformRegistration registration(AttributeContainer from, AttributeContainer to, Transformer<List<File>, File> transformer) {
        def reg = Stub(ArtifactTransformRegistration)
        reg.from >> from
        reg.to >> to
        def transformationStep = Stub(TransformationStep)
        _ * transformationStep.visitTransformationSteps(_) >> { Action action -> action.execute(transformationStep) }
        _ * transformationStep.createInvocation(_ as TransformationSubject, _ as TransformUpstreamDependencies, null) >> { TransformationSubject subject, TransformUpstreamDependencies dependenciesResolver, services ->
            return Deferrable.completed(Try.successful(subject.createSubjectFromResult(ImmutableList.copyOf(subject.files.collectMany { transformer.transform(it) }))))
        }
        _ * reg.transformationStep >> transformationStep
        reg
    }

    private ResolvedVariant variant(Map<String, Object> attributes) {
        return Mock(ResolvedVariant) {
            getAttributes() >> AttributeTestUtil.attributes(attributes)
        }
    }
}
